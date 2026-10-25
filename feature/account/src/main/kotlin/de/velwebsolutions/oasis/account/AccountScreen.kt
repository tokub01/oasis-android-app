package de.velwebsolutions.oasis.account

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import de.velwebsolutions.oasis.account.ui.auth.SignedOutFlow
import de.velwebsolutions.oasis.account.ui.auth.VerifyEmailContentScreen
import de.velwebsolutions.oasis.account.ui.components.ScopedViewModels
import de.velwebsolutions.oasis.account.ui.member.ComplaintScreen
import de.velwebsolutions.oasis.account.ui.member.GlossaryScreen
import de.velwebsolutions.oasis.account.ui.member.SuspendedScreen
import de.velwebsolutions.oasis.account.ui.member.VotesScreen
import de.velwebsolutions.oasis.account.ui.privacy.HiddenWordsScreen
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyCenterScreen
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyCheckupScreen
import de.velwebsolutions.oasis.account.ui.safety.DevicesScreen
import de.velwebsolutions.oasis.account.ui.safety.ReportScreen
import de.velwebsolutions.oasis.account.ui.safety.SafetyScreen
import de.velwebsolutions.oasis.account.ui.settings.SettingsScreen
import de.velwebsolutions.oasis.account.ui.welcome.WelcomeScreen
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.ScreenChrome
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator

/** Entry point of the Account module: the screen for each [AccountRoute] (mapped in the app). */
@Composable
fun AccountScreen(route: AccountRoute) {
    when (route) {
        AccountRoute.Settings -> SettingsScreen()

        AccountRoute.Privacy -> PrivacyCenterScreen()

        AccountRoute.PrivacyCheckup -> PrivacyCheckupScreen()

        AccountRoute.HiddenWords -> HiddenWordsScreen()

        AccountRoute.Devices -> DevicesScreen()

        AccountRoute.Welcome -> WelcomeScreen()

        AccountRoute.Safety -> SafetyScreen()

        is AccountRoute.Report -> ReportScreen(route.url)

        AccountRoute.Glossary -> GlossaryScreen()

        AccountRoute.Suspended -> SuspendedScreen()

        is AccountRoute.Complaint -> ComplaintScreen(route.decisionId)

        // The remaining route: Abstimmungen.
        else -> VotesScreen()
    }
}

/** Sign-in, registration and password reset while nobody is signed in. */
@Composable
fun SignedOutScreen() {
    SignedOutFlow()
}

/**
 * The e-mail confirmation screen after registration. Its link to the settings (to correct the address or delete the
 * account) opens on a stack of its own, since the tabs are not there yet; the account pages pushed from there show on
 * it too.
 */
@Composable
fun VerifyEmailScreen() {
    val app = LocalNavigator.current
    val local = remember(app) { Navigator().also { it.openWeb = { url -> app.openWeb(url) } } }
    val stack = local.stack(local.selectedTab)
    BackHandler(enabled = stack.isNotEmpty()) { local.back() }
    ScopedViewModels {
        CompositionLocalProvider(LocalNavigator provides local) {
            val top = stack.lastOrNull()
            if (top is AccountRoute) {
                CompositionLocalProvider(LocalScreenChrome provides ScreenChrome(onBack = { local.back() })) {
                    AccountScreen(top)
                }
            } else {
                CompositionLocalProvider(LocalScreenChrome provides ScreenChrome()) {
                    VerifyEmailContentScreen(onSettings = { local.open(AccountRoute.Settings) })
                }
            }
        }
    }
}
