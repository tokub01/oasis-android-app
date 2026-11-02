package de.velwebsolutions.oasis.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.velwebsolutions.oasis.app.shell.AppHost
import de.velwebsolutions.oasis.chat.core.ChatVault
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.design.components.openWebPage
import de.velwebsolutions.oasis.design.navigation.Navigator
import javax.inject.Inject

/**
 * The main activity (a FragmentActivity, so the chat can show the androidx BiometricPrompt): hosts [AppHost.Content] and
 * passes links (app links, `oasis://`, push taps) and shares from other apps to the navigator – each intent once, so an
 * activity Android creates again (rotation, dark mode) or reopens from the recent apps keeps the screens
 * ([AppHost.created]). While the chat is unlocked, Android's recent apps show no picture of it
 * ([AppHost.recentsScreenshots], Android 13 and later; before, the window is secure meanwhile: [AppHost.secureWhileUnlocked]).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var navigator: Navigator

    @Inject lateinit var apiClient: ApiClient

    @Inject lateinit var chatVault: ChatVault

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        navigator.openWeb = { url -> openWebPage(this, url) }
        setContent { AppHost.Content(navigator, apiClient) }
        AppHost.created(intent, savedInstanceState, navigator)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hideFromRecentsWhileChatUnlocked()
        } else {
            AppHost.recentsScreenshots(lifecycleScope, chatVault.unlockedFlow) { enabled -> AppHost.secureWhileUnlocked(window, !enabled) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppHost.open(intent, navigator)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun hideFromRecentsWhileChatUnlocked() {
        // Only the picture in the recent apps depends on it: a call the system refuses must not end the activity.
        AppHost.recentsScreenshots(lifecycleScope, chatVault.unlockedFlow) { enabled ->
            runCatching { setRecentsScreenshotEnabled(enabled) }
        }
    }
}
