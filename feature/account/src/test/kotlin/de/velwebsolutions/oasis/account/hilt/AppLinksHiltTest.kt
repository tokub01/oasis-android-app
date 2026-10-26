package de.velwebsolutions.oasis.account.hilt

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountLinks
import de.velwebsolutions.oasis.account.AccountLinksViewModel
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.account.SignedOutScreen
import de.velwebsolutions.oasis.design.navigation.IncomingLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Links from mails opened in the app: „Neues Passwort“ and the code sign-in signed out, the confirmation signed in. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLinksHiltTest : DeviceScreenTest() {
    private val resetLink = "https://get-oasis.com/passwort-zuruecksetzen/tok?email=mira%40demo.oasis.tld"

    @Test
    fun theResetLinkSetsANewPasswordAndLeadsToTheSignIn() {
        navigator.handle(resetLink)
        showWithDevice { SignedOutScreen() }
        waitForTag("reset.password")
        field("E-Mail-Adresse").assertTextContains("mira@demo.oasis.tld")
        compose.onNodeWithTag("reset.submit").assertIsNotEnabled()
        assertNull("the flow took the link", navigator.pendingLink)
        shot("reset-password")
        recreate()
        waitForTag("reset.password")
        field("E-Mail-Adresse").assertTextContains("mira@demo.oasis.tld")

        stub(
            "POST",
            "auth/reset-password",
            """{"message":"x","errors":{"password":["Das Passwort muss mindestens 10 Zeichen lang sein."]}}""",
            status = 422
        )
        type("Neues Passwort", "kurz")
        type("Neues Passwort wiederholen", "kurz")
        compose.onNodeWithTag("reset.submit").tap()
        waitForText("Das Passwort muss mindestens 10 Zeichen lang sein.")
        stub("POST", "auth/reset-password", "{}", status = 429)
        compose.onNodeWithTag("reset.submit").tap()
        waitForText("Zu viele Versuche. Bitte warte einen Moment.")
        stub("POST", "auth/reset-password", """{"message":"Dein Passwort wurde zurückgesetzt."}""")
        compose.onNodeWithTag("reset.submit").tap()
        waitForText("Dein Passwort wurde zurückgesetzt.")
        shot("reset-password-done")
        compose.onNodeWithTag("reset.signin").tap()
        waitForTag("signin.button")
    }

    @Test
    fun theCodeLinkOpensTheCodeSignInAndTheConfirmationWaitsForTheSignIn() {
        navigator.handle("https://get-oasis.com/anmelden/code")
        showWithDevice { SignedOutScreen() }
        waitForTag("logincode.send")
        navigator.handle(resetLink)
        waitForTag("reset.back")
        compose.onNodeWithTag("reset.back").tap()
        waitForTag("signin.button")
        navigator.handle("https://get-oasis.com/email/bestaetigen/5/a1b2?expires=1&signature=s")
        compose.waitForIdle()
        assertTrue(navigator.pendingLink is IncomingLink.VerifyEmail)
    }

    @Test
    fun signedInTheConfirmationLinkConfirmsAndOtherAccountLinksLeave() {
        signIn(MIRA.copy(emailVerified = false))
        stub(
            "POST",
            "auth/email/verify-link",
            """{"message":"x","errors":{"link":["Der Link ist abgelaufen oder gehört zu einem anderen Konto. Lass dir einen neuen schicken."]}}""",
            status = 422
        )
        show { AccountLinks() }
        navigator.handle("https://get-oasis.com/email/bestaetigen/5/a1b2?expires=1&signature=s")
        waitForText("Der Link ist abgelaufen", substring = true)
        screenShot("account-link-refused")
        compose.onNodeWithText("OK").press()
        stub("POST", "auth/email/verify-link", """{"message":"Danke!","data":${Fixtures.ME}}""")
        navigator.handle("https://get-oasis.com/email/bestaetigen/5/a1b2?expires=1&signature=s")
        waitForText(AccountLinksViewModel.CONFIRMED)
        assertEquals(true, session.me?.emailVerified)
        compose.onNodeWithText("OK").press()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(AccountLinksViewModel.CONFIRMED)).fetchSemanticsNodes().isEmpty() }

        navigator.handle(resetLink)
        compose.waitForIdle()
        assertEquals(listOf(resetLink), web)
        navigator.handle("https://get-oasis.com/anmelden/code")
        compose.waitForIdle()
        assertNull(navigator.pendingLink)
    }
}
