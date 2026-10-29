package de.velwebsolutions.oasis.app.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import de.velwebsolutions.oasis.app.MainActivity
import de.velwebsolutions.oasis.app.ui.RootScreen
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.share.PendingShare
import de.velwebsolutions.oasis.core.share.ShareInbox
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the activity and the application do, outside the two classes Hilt rewrites (MainActivity, OasisApplication), so
 * it is plain code that runs and is measured in tests.
 */
object AppHost {
    /** The web URL a notification opens. */
    const val EXTRA_URL = "de.velwebsolutions.oasis.url"

    /** The link an intent carries: an app link or `oasis://`, our push extra, or FCM's own data extra `url`. */
    fun url(intent: Intent?): String? = intent?.dataString ?: intent?.getStringExtra(EXTRA_URL) ?: intent?.getStringExtra("url")

    /** Set by [de.velwebsolutions.oasis.app.ShareReceiverActivity]: a share waits for "Teilen mit …". */
    const val EXTRA_SHARE = "de.velwebsolutions.oasis.share"

    /** What an intent to the main activity opens: "Teilen mit …" after a share, otherwise its link (if any). */
    fun open(intent: Intent?, navigator: Navigator) {
        if (intent?.getBooleanExtra(EXTRA_SHARE, false) == true) {
            navigator.handle(ChatRoute.Share)
            return
        }
        url(intent)?.let(navigator::handle)
    }

    /**
     * What a new main activity opens: its intent on a fresh start ([open]). An activity Android creates again – after a
     * rotation, a change of dark mode, font or window size, or from saved state – leaves the navigator as it is: its
     * intent was handled already, and handling it again would close the sheet, reset the tab and reopen "Teilen mit …".
     * The same goes for one reopened from the recent apps: Android hands it the intent that once started the task (an
     * old link, a push tap or a share long taken).
     */
    fun created(intent: Intent?, savedState: Bundle?, navigator: Navigator) {
        val fromRecents = intent != null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedState == null && !fromRecents) open(intent, navigator)
    }

    /**
     * After a sign-out the next member starts on the roots of the tabs, not on the screens of the one who left (a
     * search, profiles, a conversation, an open sheet, a waiting link). The navigator is reset once the session is signed
     * out – not while the server still signs the device out, which would change the screens under the member – and links
     * opened while nobody is signed in (a Tropfen from a mail) still wait for the sign-in. Runs as long as the app.
     */
    suspend fun resetAfterSignOut(states: StateFlow<SessionState>, navigator: Navigator) {
        var signedIn = false
        states.collect { state ->
            if (signedIn && state == SessionState.SignedOut) navigator.reset()
            signedIn = state is SessionState.SignedIn
        }
    }

    /**
     * While the chat's keys are open, Android's recent apps keep no picture of the app ([setEnabled] is the activity's
     * `setRecentsScreenshotEnabled`, Android 13 and later): the picture taken on the way to the background would still
     * show the decrypted conversation after the chat locked there. Runs in [scope], the activity's, as long as it lives.
     */
    fun recentsScreenshots(scope: CoroutineScope, chatUnlocked: StateFlow<Boolean>, setEnabled: (Boolean) -> Unit) {
        scope.launch { chatUnlocked.collect { unlocked -> setEnabled(!unlocked) } }
    }

    /**
     * A share from another app: copied into the cache off the main thread (the read permission ends with the receiving
     * activity), kept in memory as [PendingShare] – also while the member still has to sign in or unlock – and the app
     * opened at "Teilen mit …". Nothing usable shared: nothing happens.
     */
    suspend fun receiveShare(activity: Activity, intent: Intent?) {
        val share = intent?.let { withContext(Dispatchers.IO) { ShareInbox.read(activity, it) } } ?: return
        PendingShare.offer(share)
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .putExtra(EXTRA_SHARE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    /** The image loader that loads members-only images with the API client's headers. */
    fun imageLoader(context: Context, apiClient: ApiClient): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { apiClient.okHttp })) }
        .build()

    /** The app's whole UI: theme, navigator, API client and the root screen. */
    @Composable
    fun Content(navigator: Navigator, apiClient: ApiClient) {
        OasisTheme {
            CompositionLocalProvider(LocalNavigator provides navigator, LocalApiClient provides apiClient) {
                RootScreen()
            }
        }
    }
}
