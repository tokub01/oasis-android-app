package de.velwebsolutions.oasis.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import de.velwebsolutions.oasis.account.ConfirmPhoneScreen
import de.velwebsolutions.oasis.account.SignedOutScreen
import de.velwebsolutions.oasis.account.VerifyEmailScreen
import de.velwebsolutions.oasis.app.ui.AppDestination
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.live.ChatToastOverlay
import de.velwebsolutions.oasis.live.HintsButton

/**
 * The feature screens the shell places: the real ones in the app, stand-ins in the shell's UI tests (the features have
 * their own tests).
 */
interface AppScreens {
    @Composable
    fun SignedOut()

    @Composable
    fun VerifyEmail()

    @Composable
    fun ConfirmPhone()

    @Composable
    fun Destination(route: AppRoute)

    @Composable
    fun Bell()

    @Composable
    fun ChatNotice()
}

/** The feature modules' screens. */
object FeatureScreens : AppScreens {
    @Composable
    override fun SignedOut() = SignedOutScreen()

    @Composable
    override fun VerifyEmail() = VerifyEmailScreen()

    @Composable
    override fun ConfirmPhone() = ConfirmPhoneScreen()

    @Composable
    override fun Destination(route: AppRoute) = AppDestination(route)

    @Composable
    override fun Bell() = HintsButton()

    @Composable
    override fun ChatNotice() = ChatToastOverlay()
}

/** The screens of the running app. */
val LocalAppScreens = staticCompositionLocalOf<AppScreens> { FeatureScreens }
