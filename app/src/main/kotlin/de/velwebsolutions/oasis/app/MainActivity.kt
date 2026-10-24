package de.velwebsolutions.oasis.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import de.velwebsolutions.oasis.app.shell.AppHost
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.design.components.openWebPage
import de.velwebsolutions.oasis.design.navigation.Navigator
import javax.inject.Inject

/**
 * The only activity (a FragmentActivity, so the chat can show the androidx BiometricPrompt): hosts [AppHost.Content] and
 * passes links (app links, `oasis://`, push taps) to the navigator.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var navigator: Navigator

    @Inject lateinit var apiClient: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        navigator.openWeb = { url -> openWebPage(this, url) }
        setContent { AppHost.Content(navigator, apiClient) }
        AppHost.url(intent)?.let(navigator::handle)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppHost.url(intent)?.let(navigator::handle)
    }
}
