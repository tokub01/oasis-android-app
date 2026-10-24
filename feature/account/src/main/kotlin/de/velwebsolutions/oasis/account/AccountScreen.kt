package de.velwebsolutions.oasis.account

import androidx.compose.runtime.Composable
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.navigation.AccountRoute

/** Entry point of the Account module: the screen for each [AccountRoute] (mapped in the app). */
@Composable
fun AccountScreen(route: AccountRoute) {
    OasisScreen(title = route.toString()) { LoadingView() }
}

/** Sign-in, registration and password reset while nobody is signed in. */
@Composable
fun SignedOutScreen() {
    OasisScreen(title = "Anmelden") { LoadingView() }
}

/** The e-mail confirmation screen after registration. */
@Composable
fun VerifyEmailScreen() {
    OasisScreen(title = "E-Mail bestätigen") { LoadingView() }
}
