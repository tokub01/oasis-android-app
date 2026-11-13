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

/**
 * The whole app: restores the session, then shows sign-in, the e-mail confirmation, the lock screen, the mobile-number
 * confirmation, "Mitglied werden" (no membership yet) or the tabs. New members land in the tabs too; the Quelle offers
 * the welcome tour with its card „Mach deine Startseite lebendig“ (docs/oasis-accounts.md, „Nach der Registrierung“).
 */
@Composable
fun RootScreen(viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val screens = LocalAppScreens.current
    when (val current = state) {
        is SessionState.SignedIn -> {
            val me = current.me
            screens.AccountLinks()
            when {
                !me.emailVerified -> screens.VerifyEmail()

                me.isSuspended -> StandaloneStack(AccountRoute.Suspended)

                // Every account but staff confirms a mobile number by SMS before it pays or uses Oasis (web: app.phone).
                !me.phoneVerified -> screens.ConfirmPhone()

                // Members-only: without a paid or gifted membership only "Mitglied werden" and the account pages.
                !me.mayUseOasis -> PaywallScreen(viewModel)

                else -> MainTabs(me, viewModel)
            }
        }

        SessionState.SignedOut -> screens.SignedOut()

        // SessionState.Loading: the stored token is being checked.
        else -> LoadingView(Modifier.fillMaxSize().oasisScreenBackground(), "Mildcrowd startet …")
    }
}
