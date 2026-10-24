package de.velwebsolutions.oasis.app.shell

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import de.velwebsolutions.oasis.app.ui.RootScreen
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme

/**
 * What the activity and the application do, outside the two classes Hilt rewrites (MainActivity, OasisApplication), so
 * it is plain code that runs and is measured in tests.
 */
object AppHost {
    /** The web URL a notification opens. */
    const val EXTRA_URL = "de.velwebsolutions.oasis.url"

    /** The link an intent carries: an app link or `oasis://`, our push extra, or FCM's own data extra `url`. */
    fun url(intent: Intent?): String? = intent?.dataString ?: intent?.getStringExtra(EXTRA_URL) ?: intent?.getStringExtra("url")

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
