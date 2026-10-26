package de.velwebsolutions.oasis.account.hilt

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.ConfirmPhoneScreen
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.account.PasswordlessFixtures
import de.velwebsolutions.oasis.core.auth.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mobile-number step after the e-mail confirmation (web: ConfirmPhone): number, SMS code, on to the app. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfirmPhoneHiltTest : DeviceScreenTest() {
    @Test
    fun aNumberGetsItsCodeAndTheConfirmedNumberMovesTheAppOn() {
        signIn(MIRA.copy(phoneVerified = false))
        stub("GET", "account/phone", PasswordlessFixtures.phone())
        stub("POST", "account/phone", """{"message":"x","errors":{"phone_number":["Das ist keine gültige Handynummer."]}}""", status = 422)
        showWithDevice { ConfirmPhoneScreen() }
        waitForText("Noch ein Schritt.")
        waitForText("Bitte bestätige deine Handynummer.")
        waitForText("Zum Beispiel 0176 12345678.")
        compose.onNodeWithTag("confirmphone.code").assertDoesNotExist()
        shot("confirm-phone")
        type("Handynummer", "12")
        compose.onNodeWithTag("confirmphone.send").tap()
        waitForText("Das ist keine gültige Handynummer.")

        stub("POST", "account/phone", PasswordlessFixtures.phone(pending = "+49 176 ••• 78"), status = 202)
        field("Handynummer").assertTextContains("12")
        compose.onNodeWithTag("confirmphone.send").tap()
        waitForText("Wir haben dir einen Code per SMS geschickt. Er gilt 10 Minuten.")
        waitForText("Andere Nummer oder neuer Code")
        waitForTag("confirmphone.code")
        shot("confirm-phone-code")

        stub("POST", "account/phone/confirm", "{}", status = 429)
        type("Code aus der SMS an +49 176 ••• 78", "111111")
        compose.onNodeWithTag("confirmphone.confirm").tap()
        waitForText("Zu viele Versuche. Bitte warte einen Moment.")
        stub("POST", "account/phone/confirm", PasswordlessFixtures.phone(number = "+49 176 ••• 78"))
        stub("GET", "auth/me", """{"data":${Fixtures.ME}}""")
        compose.onNodeWithTag("confirmphone.confirm").tap()
        compose.waitUntil(5_000) { session.me?.phoneVerified == true }
    }

    @Test
    fun withoutSmsThereIsOnlyTheHintAndSigningOut() {
        signIn(MIRA.copy(phoneVerified = false))
        stub("GET", "account/phone", PasswordlessFixtures.phone(available = false))
        stub("POST", "auth/logout", "", status = 204)
        showWithDevice { ConfirmPhoneScreen() }
        waitForText("SMS sind gerade nicht verfügbar. Bitte versuche es später noch einmal.")
        compose.onNodeWithTag("confirmphone.send").assertDoesNotExist()
        compose.onNodeWithText("Handynummer").assertDoesNotExist()
        shot("confirm-phone-no-sms")
        compose.onNodeWithTag("confirmphone.signout").tap()
        compose.waitUntil(5_000) { session.state.value == SessionState.SignedOut }
        assertEquals(SessionState.SignedOut, session.state.value)
    }
}
