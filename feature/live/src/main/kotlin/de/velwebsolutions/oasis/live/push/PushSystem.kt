package de.velwebsolutions.oasis.live.push

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the system says about notifications for Oasis. */
enum class PushAuthorization { NotDetermined, Denied, Authorized }

/**
 * The operating system side of push: permission, the FCM token, the notification settings (PushSystem on iOS).
 * Abstracted so the registrar can be tested on the JVM.
 */
interface PushSystem {
    /** Whether Firebase is set up in this build (the app ships a google-services.json). */
    val isConfigured: Boolean

    /** The permission state; the prompt itself is shown by the screen (it needs an activity). */
    fun authorization(): PushAuthorization

    /** The screen asked for POST_NOTIFICATIONS and got this answer. */
    fun permissionAnswered(granted: Boolean)

    /** This installation's FCM token, or null when Firebase is not set up or does not answer. */
    suspend fun token(): String?

    /** Forgets the FCM token (push switched off on this device). */
    suspend fun deleteToken()

    /** Opens the Android notification settings of Oasis (to allow notifications after denying them). */
    fun openSettings()
}

/** Where the registrar remembers this installation's device id and the member's choice (UserDefaults on iOS). */
interface PushStore {
    var deviceId: Int?
    var optedOut: Boolean
}

/** [PushStore] in private preferences. */
class PreferencesPushStore(context: Context) : PushStore {
    private val preferences = context.getSharedPreferences("oasis.push", Context.MODE_PRIVATE)

    override var deviceId: Int?
        get() = preferences.getInt(DEVICE_ID, -1).takeIf { it >= 0 }
        set(value) {
            preferences.edit { if (value == null) remove(DEVICE_ID) else putInt(DEVICE_ID, value) }
        }

    override var optedOut: Boolean
        get() = preferences.getBoolean(OPTED_OUT, false)
        set(value) {
            preferences.edit { putBoolean(OPTED_OUT, value) }
        }

    private companion object {
        const val DEVICE_ID = "device-id"
        const val OPTED_OUT = "opted-out"
    }
}

/** The token of a finished Firebase task, or null when Firebase could not hand one out. */
internal fun tokenOf(task: Task<String>): String? = if (task.isSuccessful) task.result else null

/**
 * The real system: notification permission (asked at runtime from Android 13 on), Firebase Cloud Messaging only when
 * the build is configured for it – without google-services.json Firebase has no default app and is never touched.
 */
class AndroidPushSystem(private val context: Context) : PushSystem {
    private val preferences = context.getSharedPreferences("oasis.push", Context.MODE_PRIVATE)

    override val isConfigured: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    override fun authorization(): PushAuthorization {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }
        return when {
            allowed -> PushAuthorization.Authorized

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !preferences.getBoolean(
                ASKED,
                false
            ) -> PushAuthorization.NotDetermined

            else -> PushAuthorization.Denied
        }
    }

    override fun permissionAnswered(granted: Boolean) {
        preferences.edit { putBoolean(ASKED, true) }
    }

    // Firebase 26 marks the token calls deprecated; they are still the documented way to read and drop the FCM token.
    @Suppress("DEPRECATION")
    override suspend fun token(): String? {
        if (!isConfigured) return null
        // isConfigured guarantees Firebase's default app, so getInstance() cannot fail here.
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                continuation.resume(tokenOf(task))
            }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun deleteToken() {
        if (!isConfigured) return
        suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { continuation.resume(Unit) }
        }
    }

    override fun openSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val ASKED = "permission-asked"
    }
}
