package de.velwebsolutions.oasis.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dagger.hilt.android.AndroidEntryPoint
import de.velwebsolutions.oasis.app.ui.RootScreen
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.components.openWebPage
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import javax.inject.Inject

/** The only activity: hosts the Compose tree and passes links (app links, `oasis://`, push taps) to the navigator. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var navigator: Navigator

    @Inject lateinit var apiClient: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        navigator.openWeb = { url -> openWebPage(this, url) }
        setContent {
            OasisTheme {
                CompositionLocalProvider(LocalNavigator provides navigator, LocalApiClient provides apiClient) {
                    RootScreen()
                }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A link or a tapped notification (`url` extra, set by the push service). */
    private fun handle(intent: Intent?) {
        val url = intent?.dataString ?: intent?.getStringExtra(EXTRA_URL) ?: return
        navigator.handle(url)
    }

    companion object {
        /** The web URL a notification opens. */
        const val EXTRA_URL = "de.velwebsolutions.oasis.url"
    }
}
