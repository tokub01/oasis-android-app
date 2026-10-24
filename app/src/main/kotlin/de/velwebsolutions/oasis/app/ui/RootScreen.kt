package de.velwebsolutions.oasis.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.app.shell.LocalAppScreens
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.oasisScreenBackground
import de.velwebsolutions.oasis.design.navigation.AccountRoute

/** The whole app: restores the session, then shows sign-in, the e-mail confirmation, the lock screen or the tabs. */
@Composable
fun RootScreen(viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val screens = LocalAppScreens.current
    when (val current = state) {
        SessionState.Loading -> LoadingView("Oasis startet …", Modifier.fillMaxSize().oasisScreenBackground())

        SessionState.SignedOut -> screens.SignedOut()

        is SessionState.SignedIn -> {
            val me = current.me
            when {
                !me.emailVerified -> screens.VerifyEmail()

                me.isSuspended -> StandaloneStack(AccountRoute.Suspended)

                // New members start with the welcome tour, like the web's Quelle sends them to /app/willkommen.
                !me.onboarded -> StandaloneStack(AccountRoute.Welcome)

                else -> MainTabs(me, viewModel)
            }
        }
    }
}
