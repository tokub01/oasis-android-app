package de.velwebsolutions.oasis.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import de.velwebsolutions.oasis.app.shell.AppScreens
import de.velwebsolutions.oasis.app.shell.LiveShell
import de.velwebsolutions.oasis.app.shell.PurchaseObserver
import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.auth.Registration
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.auth.SignInResult
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.navigation.AppRoute
import kotlinx.coroutines.flow.MutableStateFlow

val mira = Me(7, "mira", "Mira Sol", "MS", "mira@example.org")

/** The live module as the shell sees it, recording what the shell asks for. */
class FakeLive : LiveShell {
    override val hints = MutableStateFlow(0)
    override val chats = MutableStateFlow(0)
    val started = mutableListOf<Int>()
    var foregrounds = 0
    var showingChats: Boolean? = null
    var stops = 0

    override suspend fun start(userId: Int) {
        started += userId
    }

    override suspend fun foreground() {
        foregrounds++
    }

    override fun setShowingChats(showing: Boolean) {
        showingChats = showing
    }

    override fun stop() {
        stops++
    }
}

/** Counts how often the shell listens for purchases. */
class FakePurchases : PurchaseObserver {
    var observed = 0

    override suspend fun observe() {
        observed++
    }
}

/** Answers `me` with [me] (or fails with [error]). */
class FakeAuth(var me: Me = mira, var error: Exception? = null) : AuthApi {
    var loggedOut = false

    override suspend fun login(login: String, password: String, deviceName: String) = SignInResult.SignedIn("t", me())

    override suspend fun twoFactor(challenge: String, code: String?, recoveryCode: String?) = login("", "", "")

    override suspend fun registrationForm() = RegistrationForm("hp", "tf", "t")

    override suspend fun register(registration: Registration, form: RegistrationForm, deviceName: String) = login("", "", "")

    override suspend fun forgotPassword(email: String) = "ok"

    override suspend fun me(): Me = error?.let { throw it } ?: me

    override suspend fun logout() {
        loggedOut = true
    }

    override suspend fun resendVerification() = Unit
}

/** Stand-ins for the feature screens: each shows its name, so the shell's routing can be read off the screen. */
object FakeScreens : AppScreens {
    @Composable
    override fun SignedOut() = Text("Anmelden-Screen")

    @Composable
    override fun VerifyEmail() = Text("E-Mail-Screen")

    @Composable
    override fun Destination(route: AppRoute) = OasisScreen(title = "Screen $route") { Text("Inhalt $route") }

    @Composable
    override fun Bell() = Text("Glocke")

    @Composable
    override fun ChatNotice() = Text("Chat-Hinweis")
}
