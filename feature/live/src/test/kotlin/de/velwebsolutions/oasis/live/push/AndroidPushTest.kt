package de.velwebsolutions.oasis.live.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.RemoteMessage
import de.velwebsolutions.oasis.core.model.PushPreview
import de.velwebsolutions.oasis.core.model.ToneHint
import de.velwebsolutions.oasis.core.network.CommonApi
import de.velwebsolutions.oasis.live.FakeRealtime
import de.velwebsolutions.oasis.live.LiveCoordinator
import de.velwebsolutions.oasis.live.LiveJson
import de.velwebsolutions.oasis.live.registrar
import de.velwebsolutions.oasis.live.repository
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Android side of push: channels, notifications, the permission, the stored choice and incoming messages. */
@RunWith(RobolectricTestRunner::class)
class AndroidPushTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun allowNotifications() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun registerLauncher() {
        val launcher = ComponentName(app.packageName, "de.velwebsolutions.oasis.MainActivity")
        val packages = shadowOf(app.packageManager)
        packages.addActivityIfNotPresent(launcher)
        packages.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun createsTheChannelsOnce() {
        PushNotifications.ensureChannel(app)
        PushNotifications.ensureChannel(app)
        assertEquals(listOf("chat", "hints"), manager.notificationChannels.map { it.id }.sorted())
        assertEquals("Chat-Nachrichten", manager.getNotificationChannel("chat").name)
        assertEquals("Hinweise", manager.getNotificationChannel("hints").name)
    }

    @Test
    fun showsNothingWithoutThePermission() {
        PushNotifications.show(app, PushContent("Oasis", "Neu", null))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun showsHintsAndChatMessagesInTheirChannelsAndOpensTheTarget() {
        allowNotifications()
        registerLauncher()
        PushNotifications.show(app, PushContent("Jonas Weber", "Hat geantwortet.", "https://get-oasis.com/tropfen/12", "oasis-hinweise"), 1)
        PushNotifications.show(app, PushContent("Oasis", "Du hast eine neue Chat-Nachricht.", null, "oasis-chat"), 2)
        val (hint, chat) = posted().sortedBy { it.channelId == "chat" }
        assertEquals("hints", hint.channelId)
        assertEquals(Notification.CATEGORY_SOCIAL, hint.category)
        assertEquals("oasis-hinweise", hint.group)
        val opened = shadowOf(hint.contentIntent).savedIntent
        assertEquals("https://get-oasis.com/tropfen/12", opened.getStringExtra(PushNotifications.EXTRA_URL))
        assertEquals("chat", chat.channelId)
        assertEquals(Notification.CATEGORY_MESSAGE, chat.category)
        assertFalse(shadowOf(chat.contentIntent).savedIntent.hasExtra(PushNotifications.EXTRA_URL))
    }

    @Test
    fun opensNothingWithoutALauncherActivity() {
        allowNotifications()
        assertNull(PushNotifications.openIntent(app, "https://get-oasis.com/app/hinweise"))
        PushNotifications.show(app, PushContent("Oasis", "Neu", null))
        assertNull(posted().single().contentIntent)
        assertNull(posted().single().group)
    }

    @Test
    @Config(sdk = [32])
    fun needsNoPermissionBeforeAndroid13() {
        PushNotifications.show(app, PushContent("Oasis", "Neu", null))
        assertEquals(1, posted().size)
        assertEquals(PushAuthorization.Authorized, AndroidPushSystem(app).authorization())
        manager.let { shadowOf(it).setNotificationsEnabled(false) }
        assertEquals(PushAuthorization.Denied, AndroidPushSystem(app).authorization())
    }

    @Test
    fun asksOnceForThePermissionFromAndroid13On() {
        val system = AndroidPushSystem(app)
        assertEquals(PushAuthorization.NotDetermined, system.authorization())
        system.permissionAnswered(false)
        assertEquals(PushAuthorization.Denied, system.authorization())
        allowNotifications()
        assertEquals(PushAuthorization.Authorized, system.authorization())
    }

    @Test
    fun hasNoTokenWithoutFirebaseAndOpensTheNotificationSettings() = runBlocking {
        val system = AndroidPushSystem(app)
        assertFalse(system.isConfigured)
        assertNull(system.token())
        system.deleteToken()
        system.openSettings()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun remembersTheDeviceAndTheChoice() {
        val store = PreferencesPushStore(app)
        assertNull(store.deviceId)
        assertFalse(store.optedOut)
        store.deviceId = 7
        store.optedOut = true
        val again = PreferencesPushStore(app)
        assertEquals(7, again.deviceId)
        assertTrue(again.optedOut)
        again.deviceId = null
        assertNull(PreferencesPushStore(app).deviceId)
    }

    private class FakeCommonApi(val preview: PushPreview?) : CommonApi {
        val asked = mutableListOf<String>()

        override suspend fun toneHint(text: String, field: String): ToneHint = error("not used")

        override suspend fun pushPreview(reference: String): PushPreview? {
            asked += reference
            return preview
        }
    }

    private fun message(vararg data: Pair<String, String>, title: String? = null) = RemoteMessage(
        Bundle().apply {
            data.forEach { (key, value) -> putString(key, value) }
            if (title != null) {
                putString("gcm.n.e", "1")
                putString("gcm.n.title", title)
                putString("gcm.n.body", "Öffne Oasis")
            }
        }
    )

    @Test
    fun showsAPushWithTheNamedTextAndRefreshesTheCounters() {
        allowNotifications()
        val transport = StubTransport(
            StubTransport.Stub("GET", "live/counts", json = LiveJson.COUNTS),
            StubTransport.Stub("GET", "app-config", json = LiveJson.CONFIG)
        )
        val live = LiveCoordinator(repository(transport), FakeRealtime(), registrar(), scope)
        runBlocking { live.start(1) }
        val common = FakeCommonApi(PushPreview("Jonas Weber", "Hat auf deinen Tropfen geantwortet."))
        val messages = PushMessages(app, common, live, registrar(), scope)
        val reference = "Abc123Abc123Abc123Abc123Abc123Abc123Abc1"
        messages.received(message("title" to "Oasis", "body" to "Es gibt Neues.", "preview" to reference))
        assertEquals(listOf(reference), common.asked)
        val shown = posted().single()
        assertEquals("Jonas Weber", shown.extras.getString(Notification.EXTRA_TITLE))

        messages.received(message(title = "Neue Nachricht"))
        assertEquals("without reference nothing is asked", 1, common.asked.size)
        assertNotNull(posted().firstOrNull { it.extras.getString(Notification.EXTRA_TITLE) == "Neue Nachricht" })
        messages.tokenChanged()
        live.stop()
    }

    @Test
    fun asksFirebaseForTheTokenOnceItIsSetUp() = runBlocking {
        FirebaseApp.initializeApp(
            app,
            FirebaseOptions.Builder().setApplicationId("1:1:android:1").setApiKey("test-key").setProjectId("oasis-test").build()
        )
        val system = AndroidPushSystem(app)
        assertTrue(system.isConfigured)
        // Firebase answers on the main looper, which the test turns by hand.
        val token = async(Dispatchers.IO) { system.token() }
        val deleted = async(Dispatchers.IO) { system.deleteToken() }
        repeat(500) {
            if (!token.isCompleted || !deleted.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
        }
        assertNull("without Google Play services there is no token", withTimeout(1_000) { token.await() })
        withTimeout(1_000) { deleted.await() }
    }

    @Test
    fun readsTheTokenOnlyFromASuccessfulTask() {
        assertEquals("fcm", tokenOf(Tasks.forResult("fcm")))
        assertNull(tokenOf(Tasks.forException(IllegalStateException("no Play services"))))
    }

    @Test
    fun opensNothingWhenNoSettingsScreenAnswers() {
        shadowOf(app).checkActivities(true)
        AndroidPushSystem(app).openSettings()
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
