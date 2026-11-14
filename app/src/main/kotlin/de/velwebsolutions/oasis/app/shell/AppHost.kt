package de.velwebsolutions.oasis.app.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.NotificationManagerCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import de.velwebsolutions.oasis.app.MainActivity
import de.velwebsolutions.oasis.app.ui.RootScreen
import de.velwebsolutions.oasis.chat.core.LookPhotos
import de.velwebsolutions.oasis.chat.core.LookStore
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.screentime.ScreenTime
import de.velwebsolutions.oasis.core.share.PendingShare
import de.velwebsolutions.oasis.core.share.ShareInbox
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
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

    /** The link an intent carries: an app link or `mildcrowd://`, our push extra, or FCM's own data extra `url`. */
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
     * Mildcrowd came to the front with a share still waiting (files that did not fit into the last message): "Teilen mit …"
     * opens in the Chats tab, unless it is open already (iOS `ChatShares.openIfPending`).
     */
    fun openPendingShare(navigator: Navigator) {
        if (PendingShare.share.value == null || navigator.stack(MainTab.Chats).lastOrNull() == ChatRoute.Share) return
        navigator.handle(ChatRoute.Share)
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
     * opened while nobody is signed in (a Post from a mail) still wait for the sign-in, and so does a share made then;
     * one still waiting at the sign-out is discarded with its cache copies. [onSignedOut] clears what the device keeps
     * of the member besides ([clearAfterSignOut]). Runs as long as the app.
     */
    suspend fun resetAfterSignOut(states: StateFlow<SessionState>, navigator: Navigator, onSignedOut: () -> Unit) {
        var signedIn = false
        states.collect { state ->
            if (signedIn && state == SessionState.SignedOut) {
                navigator.reset()
                // What was shared for the member who left is not for the next one: the copies go too.
                PendingShare.discard()
                onSignedOut()
            }
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
     * Before Android 13 there is no `setRecentsScreenshotEnabled`: while the chat is unlocked the window is secure
     * instead ([secure]), so the picture in the recent apps – taken on the way to the background, before the vault
     * locks there – shows nothing of the decrypted chat (iOS covers the chat whenever the app is not active). Screenshots
     * are blocked meanwhile too. A window that was secure for another reason (recovery codes) is left so.
     */
    fun secureWhileUnlocked(window: Window, secure: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (secure) {
            if (window.attributes.flags and flag == 0) {
                window.addFlags(flag)
                securedForChat = true
            }
        } else if (securedForChat) {
            window.clearFlags(flag)
            securedForChat = false
        }
    }

    /** Whether [secureWhileUnlocked] made the window secure (and so may clear it again). */
    private var securedForChat = false

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

    /**
     * After a sign-out: the images the member saw (memory and disk cache – also ones a moderation quarantine withdrew
     * since) and the notifications with their previews go, like on iOS. The chat vault locks through its own listener.
     * The chat backgrounds and own background photos belong to the member who left, so they go too, and so does the
     * counted time („Deine Zeit in Mildcrowd“).
     */
    fun clearAfterSignOut(context: Context, lookStore: LookStore, lookPhotos: LookPhotos, scope: CoroutineScope, screenTime: ScreenTime) {
        clearAfterSignOut(context)
        screenTime.clear()
        lookPhotos.deleteAll()
        scope.launch { lookStore.clear() }
    }

    /** The images and notifications of [clearAfterSignOut]. */
    fun clearAfterSignOut(context: Context) {
        val images = SingletonImageLoader.get(context)
        images.memoryCache?.clear()
        images.diskCache?.clear()
        NotificationManagerCompat.from(context).cancelAll()
    }

    /** Light or dark icons in the status and navigation bar, as the page under them. */
    fun systemBars(activity: ComponentActivity, dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
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
