package de.velwebsolutions.oasis.account.hilt

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.account.SignedOutScreen
import de.velwebsolutions.oasis.account.VerifyEmailScreen
import de.velwebsolutions.oasis.core.auth.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Signed out: sign-in with the second factor, the lost second factor, the forgotten password, registration; then the e-mail check. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthScreensHiltTest : HiltScreenTest() {
    private val form = """{"data":{"honeypot_field":"website_url","token_field":"form_started","form_token":"abc","trial_days":14}}"""

    private fun back() = activity.onBackPressedDispatcher.onBackPressed()

    @Test
    fun signsInWithTheSecondFactorAfterVisitingTheRecovery() {
        stub("GET", "auth/register", form)
        stub("POST", "auth/login", """{"message":"x","errors":{"login":["Diese Zugangsdaten stimmen nicht."]}}""", status = 422)
        show { SignedOutScreen() }
        waitForText("14 Tage kostenlos testen")
        shot("signin")
        type("E-Mail oder Benutzername", "mira")
        compose.onNodeWithContentDescription("E-Mail oder Benutzername").performImeAction()
        type("Passwort", "geheim")
        compose.onNodeWithContentDescription("Passwort").performImeAction()
        waitForText("Diese Zugangsdaten stimmen nicht.")
        stub("POST", "auth/login", """{"message":"Server Error"}""", status = 500)
        type("Passwort", "x")
        compose.onNodeWithTag("signin.button").tap()
        waitForText("Server Error", substring = true)
        stub("POST", "auth/login", """{"two_factor":true,"challenge":"chal-1"}""")
        type("Passwort", "y")
        compose.onNodeWithTag("signin.button").tap()
        waitForTag("twofactor.code")
        shot("twofactor")

        compose.onNodeWithTag("twofactor.lost").tap()
        waitForTag("recovery.submit")
        stub("POST", "auth/two-factor-recovery", """{"message":"x","errors":{"form":["Bitte lade die Seite neu."]}}""", status = 422)
        type("E-Mail-Adresse", "mira@demo.oasis.tld")
        compose.onNodeWithContentDescription("E-Mail-Adresse").performImeAction()
        type("Passwort", "geheim")
        compose.onNodeWithContentDescription("Passwort").performImeAction()
        waitForText("Bitte lade die Seite neu.")
        stub("POST", "auth/two-factor-recovery", """{"message":"Server Error"}""", status = 500)
        compose.onNodeWithTag("recovery.submit").tap()
        compose.waitUntil(5_000) { transport.requests.count { it.path == "auth/two-factor-recovery" } == 2 }
        stub("POST", "auth/two-factor-recovery", """{"message":"Wir haben dir einen Link geschickt."}""")
        type("Passwort", "geheim")
        compose.onNodeWithTag("recovery.submit").tap()
        waitForText("Wir haben dir einen Link geschickt.")
        shot("twofactor-recovery")
        back()

        stub("POST", "auth/two-factor", """{"message":"x","errors":{"challenge":["Die Anmeldung ist abgelaufen."]}}""", status = 422)
        type("Sechsstelliger Code", "123456")
        compose.onNodeWithContentDescription("Sechsstelliger Code").performImeAction()
        waitForText("Die Anmeldung ist abgelaufen.")
        stub("POST", "auth/two-factor", """{"message":"Server Error"}""", status = 500)
        type("Oder ein Wiederherstellungscode (optional)", "abcd-efgh")
        compose.onNodeWithContentDescription("Oder ein Wiederherstellungscode (optional)").performImeAction()
        compose.waitUntil(5_000) { transport.requests.count { it.path == "auth/two-factor" } == 2 }
        stub("POST", "auth/two-factor", """{"token":"new","data":${Fixtures.ME}}""")
        compose.onNodeWithTag("twofactor.submit").tap()
        compose.waitUntil(5_000) { session.state.value is SessionState.SignedIn }
    }

    @Test
    fun theForgottenPasswordIsAnsweredNeutrallyAndLeadsBack() {
        stub("GET", "auth/register", """{"message":"Server Error"}""", status = 500)
        stub("POST", "auth/forgot-password", """{"message":"x","errors":{"email":["Bitte gib eine E-Mail-Adresse ein."]}}""", status = 422)
        show { SignedOutScreen() }
        waitForText("30 Tage kostenlos testen")
        compose.onNodeWithTag("forgot.button").tap()
        waitForTag("forgot.submit")
        type("E-Mail-Adresse deines Kontos", "x")
        compose.onNodeWithContentDescription("E-Mail-Adresse deines Kontos").performImeAction()
        waitForText("Bitte gib eine E-Mail-Adresse ein.")
        stub("POST", "auth/forgot-password", """{"message":"Server Error"}""", status = 500)
        compose.onNodeWithTag("forgot.submit").tap()
        compose.waitUntil(5_000) { transport.requests.count { it.path == "auth/forgot-password" } == 2 }
        stub("POST", "auth/forgot-password", """{"message":"Wenn es ein Konto gibt, bekommst du eine E-Mail."}""")
        compose.onNodeWithTag("forgot.submit").tap()
        waitForText("Wenn es ein Konto gibt, bekommst du eine E-Mail.")
        shot("forgot")
        compose.onNodeWithText("Zurück zur Anmeldung").tap()
        waitForTag("signin.button")
    }

    @Test
    fun registersWithTheBirthDateTheTermsAndTheLegalPages() {
        stub("GET", "auth/register", form)
        stub("POST", "auth/register", """{"message":"x","errors":{"username":["Dieser Benutzername ist schon vergeben."]}}""", status = 422)
        show { SignedOutScreen() }
        waitForTag("register.button")
        compose.onNodeWithTag("register.button").tap()
        waitForTag("register.submit")
        shot("register")
        listOf("Wie sollen andere dich nennen?" to "Mira", "Benutzername" to "mira", "E-Mail-Adresse" to "mira@demo.oasis.tld")
            .forEach { (label, text) ->
                type(label, text)
                compose.onNodeWithContentDescription(label).performImeAction()
            }
        type("Passwort", "geheim-geheim")
        compose.onNodeWithContentDescription("Passwort").performImeAction()
        type("Passwort wiederholen", "geheim-geheim")
        compose.onNodeWithTag("register.submit").tap()
        waitForText("Das Geburtsdatum ist erforderlich.")
        compose.onNodeWithTag("register.birthDate").tap()
        screenShot("register-birthdate")
        compose.onNodeWithText("Abbrechen").press()
        compose.onNodeWithTag("register.birthDate").tap()
        compose.onNodeWithTag("register.birthDate.take").press()
        compose.onNodeWithTag("register.terms").tap()
        listOf("Community-Regeln", "AGB", "Datenschutz").forEach { compose.onNodeWithText(it).tap() }
        assertEquals(3, web.size)
        compose.onNodeWithContentDescription("Passwort wiederholen").performScrollTo().performImeAction()
        waitForText("Dieser Benutzername ist schon vergeben.")
        shot("register-refused")
        stub("POST", "auth/register", """{"message":"x","errors":{"form":["Bitte lade das Formular neu."]}}""", status = 422)
        compose.onNodeWithTag("register.submit").tap()
        waitForText("Bitte lade das Formular neu.")
        stub("POST", "auth/register", """{"message":"Server Error"}""", status = 500)
        stub("GET", "auth/register", """{"message":"Server Error"}""", status = 500)
        compose.onNodeWithTag("register.submit").tap()
        compose.waitUntil(5_000) { transport.requests.count { it.path == "auth/register" && it.request.method == "POST" } == 3 }
        compose.onNodeWithText("Anmelden").tap()
        waitForTag("signin.button")
        compose.onNodeWithTag("register.button").tap()
        waitForTag("register.submit")
        back()
        waitForTag("signin.button")
    }

    @Test
    fun theRegistrationFormThatCannotLoad() {
        stub("GET", "auth/register", """{"message":"Server Error"}""", status = 500)
        show { SignedOutScreen() }
        waitForTag("register.button")
        compose.onNodeWithTag("register.button").tap()
        waitForText("Server Error", substring = true)
    }

    @Test
    fun theUnconfirmedAddressIsCheckedResentAndCorrectedInTheSettings() {
        val unverified = Fixtures.ME.replace("\"email_verified\":true", "\"email_verified\":false")
        signIn(MIRA.copy(emailVerified = false))
        stub("GET", "auth/me", """{"data":$unverified}""")
        stub("POST", "auth/email/verification-notification", """{"message":"Too Many Attempts."}""", status = 429)
        stub("GET", "account", Fixtures.account)
        stub("POST", "auth/logout", "{}")
        show { VerifyEmailScreen() }
        waitForTag("verify.check")
        compose.onNodeWithTag("verify.check").tap()
        waitForText("Deine Adresse ist noch nicht bestätigt.", substring = true)
        compose.onNodeWithTag("verify.resend").tap()
        waitForRequest("POST", "auth/email/verification-notification")
        stub("POST", "auth/email/verification-notification", "{}")
        compose.onNodeWithTag("verify.resend").tap()
        waitForText("Wir haben dir einen neuen Link geschickt.")
        shot("verify")
        compose.onNodeWithText("Adresse ändern oder Konto löschen").tap()
        waitForTag("settings.saveProfile")
        compose.onNodeWithTag("help.agb").tap()
        assertTrue(web.single().endsWith("/agb"))
        compose.onNodeWithContentDescription("Zurück").press()
        waitForTag("verify.signout")
        compose.onNodeWithText("Adresse ändern oder Konto löschen").tap()
        waitForTag("settings.saveProfile")
        back()
        waitForTag("verify.signout")
        compose.onNodeWithTag("verify.signout").tap()
        waitForRequest("POST", "auth/logout")
    }
}
