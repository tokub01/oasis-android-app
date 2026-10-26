package de.velwebsolutions.oasis.account.hilt

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.account.FakePasskeys
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.account.PasswordlessFixtures
import de.velwebsolutions.oasis.account.SignedOutScreen
import de.velwebsolutions.oasis.account.platform.LocalPasskeyAuthenticator
import de.velwebsolutions.oasis.account.platform.LocalSmsConsentStarter
import de.velwebsolutions.oasis.account.platform.PasskeyFailure
import de.velwebsolutions.oasis.account.platform.SmsConsentStarter
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Without a password at sign-in: the code by e-mail or SMS (filled from the SMS) and passkeys. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PasswordlessSignInHiltTest : DeviceScreenTest() {
    private val allWays = """{"data":{"login_code_channels":["email","sms"],"sms_available":true,"passkeys":true}}"""

    @Test
    fun aRefusedPasswordLeadsToTheCodeByEmailAndOnToTheSecondFactor() {
        stub("GET", "auth/methods", allWays)
        stub("POST", "auth/login", """{"message":"x","errors":{"login":["Diese Zugangsdaten stimmen nicht."]}}""", status = 422)
        showWithDevice { SignedOutScreen() }
        waitForTag("signin.passkey")
        shot("signin-passwordless")
        type("E-Mail oder Benutzername", "mira")
        type("Passwort", "falsch")
        compose.onNodeWithTag("signin.button").tap()
        waitForTag("signin.codeHint")
        shot("signin-code-hint")
        compose.onNodeWithText("Melde dich mit einem Code per E-Mail an.").tap()
        waitForTag("logincode.send")
        field("E-Mail oder Benutzername").assertTextContains("mira")
        shot("logincode")

        stub(
            "POST",
            "auth/login-code",
            """{"message":"x","errors":{"login":["Bitte gib deine E-Mail-Adresse oder deinen Benutzernamen ein."]}}""",
            status = 422
        )
        compose.onNodeWithTag("logincode.send").tap()
        waitForText("Bitte gib deine E-Mail-Adresse oder deinen Benutzernamen ein.")
        stub("POST", "auth/login-code", "{}", status = 429)
        compose.onNodeWithContentDescription("E-Mail oder Benutzername", substring = true).performImeAction()
        waitForText("Zu viele Versuche. Bitte warte einen Moment.")
        stub("POST", "auth/login-code", "", status = 204)
        compose.onNodeWithTag("logincode.send").tap()
        waitForText("Wenn es ein Konto mit diesen Angaben gibt, haben wir dir gerade einen Code per E-Mail geschickt. Er gilt 10 Minuten.")
        assertTrue("no SMS is awaited for an e-mail code", smsRequests.isEmpty())
        compose.onNodeWithTag("logincode.signin").assertIsNotEnabled()
        shot("logincode-sent")

        stub(
            "POST",
            "auth/login-code/verify",
            """{"message":"x","errors":{"code":["Der Code stimmt nicht oder ist abgelaufen. Prüf ihn noch einmal oder lass dir einen neuen schicken."]}}""",
            status = 422
        )
        type("Sechsstelliger Code", "123 456")
        compose.onNodeWithTag("logincode.signin").tap()
        waitForText("Der Code stimmt nicht oder ist abgelaufen.", substring = true)
        shot("logincode-wrong")
        stub("POST", "auth/login-code/verify", "{}", status = 429)
        compose.onNodeWithContentDescription("Sechsstelliger Code", substring = true).performImeAction()
        waitForText("Zu viele Versuche. Bitte warte einen Moment.")
        compose.onNodeWithTag("logincode.resend").tap()
        compose.waitUntil(5_000) { transport.requests.count { it.path == "auth/login-code" } == 4 }
        type("Sechsstelliger Code", "654321")
        stub("POST", "auth/login-code/verify", """{"two_factor":true,"challenge":"chal-code"}""")
        compose.onNodeWithTag("logincode.signin").tap()
        waitForTag("twofactor.code")
        back()
        waitForTag("logincode.change")
        compose.onNodeWithTag("logincode.change").tap()
        waitForTag("logincode.send")
        compose.onNodeWithTag("logincode.password").tap()
        waitForTag("signin.button")
    }

    @Test
    fun theSmsCodeIsFilledFromTheSmsAndSignsIn() {
        stub("GET", "auth/methods", allWays)
        showWithDevice { SignedOutScreen() }
        waitForTag("signin.codeSms")
        compose.onNodeWithTag("signin.codeSms").tap()
        waitForText("Kein Passwort nötig: Wir schicken dir einen Code.")
        waitForText("Wohin sollen wir den Code schicken?")
        compose.onNodeWithTag("logincode.channel.Email").tap()
        compose.onNodeWithTag("logincode.channel.Sms").tap()
        shot("logincode-channel")
        compose.onNodeWithTag("logincode.send").assertIsNotEnabled()
        type("E-Mail oder Benutzername", "mira")
        stub("POST", "auth/login-code", """{"message":"x","errors":{"channel":["SMS ist gerade nicht verfügbar."]}}""", status = 422)
        compose.onNodeWithTag("logincode.send").tap()
        waitForText("SMS ist gerade nicht verfügbar.")
        stub("POST", "auth/login-code", "", status = 204)
        compose.onNodeWithTag("logincode.send").tap()
        waitForTag("logincode.code")
        waitForText("Schau auf dein Handy, der Code gilt 10 Minuten.")
        compose.waitUntil(5_000) { smsRequests.isNotEmpty() }
        shareSms("Dein Oasis-Anmeldecode: 999 999.", allowed = false)
        field("Sechsstelliger Code").assertTextContains("")
        shareSms("Dein Oasis-Anmeldecode: 123 456. Gültig 10 Minuten.")
        compose.onNodeWithTag("logincode.code", useUnmergedTree = true).assertExists()
        waitForText("123456")
        shot("logincode-sms")
        stub("POST", "auth/login-code/verify", """{"token":"sms-token","data":${Fixtures.ME}}""")
        compose.onNodeWithTag("logincode.signin").tap()
        compose.waitUntil(5_000) { session.state.value is SessionState.SignedIn }
        assertEquals("""{"login":"mira","code":"123456","device_name":"Test"}""", transport.lastBody("auth/login-code/verify").toString())
    }

    @Test
    fun aPasskeySignsInAndItsFailuresAreTold() {
        stub("GET", "auth/methods", allWays)
        stub("POST", "auth/passkey/options", PasswordlessFixtures.REQUEST)
        showWithDevice { SignedOutScreen() }
        waitForTag("signin.passkey")
        passkeys.failure = PasskeyFailure.Cancelled()
        compose.onNodeWithTag("signin.passkey").tap()
        waitForText("Die Anmeldung mit Passkey wurde abgebrochen.")
        compose.onNodeWithContentDescription("Schließen").press()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("status.error")).fetchSemanticsNodes().isEmpty() }
        passkeys.failure = PasskeyFailure.NoPasskey()
        compose.onNodeWithTag("signin.passkey").tap()
        waitForText("Auf diesem Gerät ist kein Passkey für Oasis gespeichert.", substring = true)
        screenShot("signin-passkey-missing")
        compose.onNodeWithContentDescription("Schließen").press()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("status.error")).fetchSemanticsNodes().isEmpty() }
        passkeys.failure = null
        stub("POST", "auth/passkey", """{"token":"pk","data":${Fixtures.ME}}""")
        compose.onNodeWithTag("signin.passkey").tap()
        compose.waitUntil(5_000) { session.state.value is SessionState.SignedIn }
    }

    @Test
    fun withoutPasskeysOnTheDeviceTheButtonStaysAway() {
        stub("GET", "auth/methods", allWays)
        show { CompositionLocalProvider(LocalPasskeyAuthenticator provides { FakePasskeys(isAvailable = false) }) { SignedOutScreen() } }
        waitForTag("signin.codeSms")
        compose.onNodeWithTag("signin.passkey").assertDoesNotExist()
    }
}
