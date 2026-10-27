package de.velwebsolutions.oasis.app.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import de.velwebsolutions.oasis.app.MainActivity
import de.velwebsolutions.oasis.app.ui.RootScreen
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.share.PendingShare
import de.velwebsolutions.oasis.core.share.ShareInbox
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import kotlinx.coroutines.Dispatchers
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
