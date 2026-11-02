package de.velwebsolutions.oasis.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import de.velwebsolutions.oasis.app.shell.AppHost
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the main activity leaves to the shell: intents once, a clean navigator after a sign-out, the recent-apps picture. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppHostTest {
    /** The real graph of MainActivityTest binds links to the debug server; these tests use get-oasis.com links. */
    @org.junit.Before
    fun resetOwnHost() {
        de.velwebsolutions.oasis.design.navigation.DeepLink.ownHost = null
    }

    @Test
    fun `an activity Android creates again leaves the screens its intent opened`() {
        val navigator = Navigator()
        AppHost.created(null, null, navigator)
        assertTrue("without an intent nothing opens", MainTab.entries.all { navigator.stack(it).isEmpty() })
        val share = Intent().putExtra(AppHost.EXTRA_SHARE, true)
        AppHost.created(share, null, navigator)
        assertEquals(listOf(ChatRoute.Share), navigator.stack(MainTab.Chats).toList())
        navigator.open(ChatRoute.Conversation(5))
        navigator.present(NetworkRoute.Compose())
        navigator.sheetDismissible = false
        AppHost.created(share, Bundle(), navigator)
        assertEquals(
            "the chat picked for the share stays",
            listOf(ChatRoute.Share, ChatRoute.Conversation(5)),
            navigator.stack(MainTab.Chats).toList()
        )
        assertEquals("the composer stays open", NetworkRoute.Compose(), navigator.sheet)
        AppHost.created(Intent(share).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), null, navigator)
        assertEquals("reopened from the recent apps, the task's first intent is not handled again", NetworkRoute.Compose(), navigator.sheet)
    }

    @Test
    fun `after a sign-out the next member starts on the roots, a link opened before signing in waits`() = runTest {
        val session = Session(InMemoryTokenStore(), "Pixel", TestScope()).apply { auth = FakeAuth() }
        val navigator = Navigator()
        var cleared = 0
        backgroundScope.launch { AppHost.resetAfterSignOut(session.state, navigator) { cleared++ } }
        runCurrent()
        navigator.handle("https://get-oasis.com/tropfen/4")
        session.restore()
        runCurrent()
        session.signIn("mira", "geheim")
        runCurrent()
        session.update(mira.copy(displayName = "Mira S."))
        runCurrent()
        assertEquals("starting and signing in keep it", listOf(NetworkRoute.Post(4)), navigator.stack(MainTab.Source).toList())
        navigator.handle(NetworkRoute.Search("tee"))
        navigator.open(NetworkRoute.Profile("jonas"))
        navigator.present(ChatRoute.NewConversationWith("jonas"))
        navigator.sheetDismissible = false
        navigator.handle("https://get-oasis.com/anmelden/code")
        de.velwebsolutions.oasis.core.share.PendingShare.offer(de.velwebsolutions.oasis.core.share.Share(emptyList(), "von Mira"))
        session.signOut()
        runCurrent()
        assertEquals(MainTab.Source, navigator.selectedTab)
        assertTrue(MainTab.entries.all { navigator.stack(it).isEmpty() })
        assertNull(navigator.sheet)
        assertTrue(navigator.sheetDismissible)
        assertNull(navigator.pendingLink)
        assertNull("a share waiting for Mira is not for the next member", de.velwebsolutions.oasis.core.share.PendingShare.share.value)
        assertEquals("images and notifications go once per sign-out", 1, cleared)
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test
    fun `a sign-out clears the image cache, the notifications with previews and the chat look`() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val loader = coil3.ImageLoader.Builder(context)
            .diskCache {
                coil3.disk.DiskCache.Builder().directory(
                    okio.Path.Companion.run {
                        context.cacheDir.resolve("images-test").toOkioPath()
                    }
                ).build()
            }
            .build()
        coil3.SingletonImageLoader.setUnsafe(loader)
        val disk = checkNotNull(loader.diskCache)
        disk.openEditor("photo")!!.apply {
            disk.fileSystem.write(data) { writeUtf8("Bild") }
            commit()
        }
        assertTrue(disk.size > 0)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("t", "t", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(
            1,
            android.app.Notification.Builder(
                context,
                "t"
            ).setContentText("Mira: Hallo").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        )
        assertEquals(1, org.robolectric.Shadows.shadowOf(manager).allNotifications.size)

        // The chat backgrounds and own photos of the member who left go as well.
        val look = de.velwebsolutions.oasis.chat.core.MemoryLook()
        look.set("1:bg:default", "oase")
        look.write("1:photo:default", byteArrayOf(1))
        AppHost.clearAfterSignOut(context, look, look, this)
        runCurrent()
        assertNull(look.get("1:bg:default"))
        assertNull(look.read("1:photo:default"))
        assertEquals(0L, disk.size)
        assertEquals(0L, loader.memoryCache?.size ?: 0L)
        assertTrue(org.robolectric.Shadows.shadowOf(manager).allNotifications.isEmpty())

        // A loader without caches has nothing to clear.
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(context).memoryCache(null).diskCache(null).build())
        AppHost.clearAfterSignOut(context)
    }

    @Test
    fun `while the chat is unlocked the recent apps keep no picture of the app`() = runTest {
        val unlocked = MutableStateFlow(false)
        val enabled = mutableListOf<Boolean>()
        AppHost.recentsScreenshots(backgroundScope, unlocked) { enabled += it }
        runCurrent()
        unlocked.value = true
        runCurrent()
        unlocked.value = false
        runCurrent()
        assertEquals(listOf(true, false, true), enabled)
    }

    @Test
    fun `before Android 13 the window is secure while the chat is unlocked`() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val window = activity.window
        fun secure() = window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
        AppHost.secureWhileUnlocked(window, false)
        assertFalse("nothing to clear that the chat did not set", secure())
        AppHost.secureWhileUnlocked(window, true)
        assertTrue(secure())
        AppHost.secureWhileUnlocked(window, true)
        AppHost.secureWhileUnlocked(window, false)
        assertFalse(secure())

        // Secure for another reason (recovery codes on screen): the chat leaves it so.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        AppHost.secureWhileUnlocked(window, true)
        AppHost.secureWhileUnlocked(window, false)
        assertTrue(secure())
    }
}
