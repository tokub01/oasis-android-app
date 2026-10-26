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

/** The passkey and mobile-number blocks of the settings, with the password confirmation in between. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PasswordlessSettingsHiltTest : DeviceScreenTest() {
    private fun settingsWithPasskeys(list: String = PasswordlessFixtures.PASSKEYS) {
        signIn()
        stub("GET", "account", Fixtures.account)
        stub("GET", "account/passkeys", list)
        stub("GET", "account/phone", PasswordlessFixtures.phone(available = false))
    }

    @Test
    fun aPasskeyIsAddedAfterThePasswordAndNamed() {
        settingsWithPasskeys()
        stub("POST", "account/passkeys/options", PasswordlessFixtures.LOCKED, status = 423)
        stub("POST", "account/confirm-password", Fixtures.account)
        passkeys.answer = PasswordlessFixtures.ATTESTATION
        showWithDevice { AccountScreen(AccountRoute.Settings) }
        waitForTag("passkey.3")
        compose.onNodeWithTag("passkey.3").performScrollTo()
        shot("settings-passkeys")
        compose.onNodeWithTag("passkeys.add").tap()
        waitForTag("confirm.password")
        stub("POST", "account/passkeys/options", PasswordlessFixtures.CREATION)
        type("Passwort", "geheim")
        compose.onNodeWithTag("confirm.submit").press()
        waitForTag("passkey.name")
        field("Name").assertTextContains("Pixel 9")
        screenShot("settings-passkey-name")
        stub("POST", "account/passkeys", "", status = 201)
        stub("GET", "account/passkeys", """{"data":[{"id":4,"name":"Pixel 9"}]}""")
        compose.onNodeWithTag("passkey.name.save").press()
        waitForText("Der Passkey ist gespeichert. Du kannst dich ab jetzt damit anmelden.")
        waitForTag("passkey.4")
        assertTrue(
            transport.requests.last {
                it.path == "account/passkeys" && it.request.method == "POST"
            }.body!!.contains("\"name\":\"Pixel 9\"")
        )

        passkeys.failure = PasskeyFailure.AlreadyRegistered()
        compose.onNodeWithTag("passkeys.add").tap()
        waitForText("Auf diesem Gerät gibt es schon einen Passkey für dein Konto.")
        compose.onNodeWithContentDescription("Schließen").press()
        passkeys.failure = null
        stub("POST", "account/passkeys", """{"message":"x","errors":{"challenge":["Die Anfrage ist abgelaufen."]}}""", status = 422)
        compose.onNodeWithTag("passkeys.add").tap()
        waitForTag("passkey.name.dismiss")
        compose.onNodeWithTag("passkey.name.dismiss").press()
        waitForText("Die Anfrage ist abgelaufen.")
    }

    @Test
    fun passkeysAreRenamedAndRemovedAfterThePassword() {
        settingsWithPasskeys()
        stub("PATCH", "account/passkeys/3", """{"message":"x","errors":{"name":["Bitte gib dem Passkey einen Namen."]}}""", status = 422)
        stub("DELETE", "account/passkeys/3", PasswordlessFixtures.LOCKED, status = 423)
        stub("POST", "account/confirm-password", """{"message":"x","errors":{"password":["Das Passwort stimmt nicht."]}}""", status = 422)
        showWithDevice { AccountScreen(AccountRoute.Settings) }
        waitForTag("passkey.3.rename")
        compose.onNodeWithTag("passkey.3.rename").tap()
        waitForTag("passkey.name")
        field("Name").performTextClearance()
        compose.onNodeWithTag("passkey.name.save").press()
        waitForText("Bitte gib dem Passkey einen Namen.")
        screenShot("settings-passkey-rename")
        compose.onNodeWithTag("passkey.name.dismiss").press()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("passkey.name")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("passkey.3.rename").tap()
        stub("PATCH", "account/passkeys/3", """{"data":{"id":3,"name":"Handy"}}""")
        field("Name").performTextClearance()
        field("Name").performTextInput("Pixel 9 (Arbeit)")
        compose.onNodeWithTag("passkey.name.save").press()
        waitForText("Der Name des Passkeys ist gespeichert.")
        assertEquals("""{"name":"Pixel 9 (Arbeit)"}""", transport.lastBody("account/passkeys/3").toString())

        compose.onNodeWithTag("passkey.3.remove").tap()
        waitForText("Diesen Passkey entfernen?", substring = true)
        screenShot("settings-passkey-remove")
        compose.onNodeWithText("Abbrechen").press()
        compose.onNodeWithTag("passkey.3.remove").tap()
        compose.onNode(hasText("Entfernen") and !hasTestTag("passkey.3.remove") and !hasTestTag("passkey.1.remove")).press()
        waitForTag("confirm.password")
        type("Passwort", "falsch")
        compose.onNodeWithTag("confirm.submit").press()
        waitForText("Das Passwort stimmt nicht.")
        stub("POST", "account/confirm-password", Fixtures.account)
        stub("DELETE", "account/passkeys/3", "", status = 204)
        stub("GET", "account/passkeys", """{"data":[]}""")
        compose.onNodeWithTag("confirm.submit").press()
        waitForText("Du hast noch keinen Passkey.")
        waitForText("Der Passkey ist entfernt.")
    }

    @Test
    fun withoutPasskeysOnTheDeviceTheSettingsSaySo() {
        signIn()
        stub("GET", "account", Fixtures.account)
        stub("GET", "account/passkeys", "{}", status = 500)
        stub("GET", "account/phone", PasswordlessFixtures.phone(available = false))
        show {
            CompositionLocalProvider(
                LocalPasskeyAuthenticator provides {
                    FakePasskeys(isAvailable = false)
                }
            ) { AccountScreen(AccountRoute.Settings) }
        }
        waitForText("Auf diesem Gerät gehen Passkeys nicht.", substring = true)
        waitForText("SMS sind gerade nicht verfügbar.", substring = true)
        compose.onNodeWithTag("phone.field").assertDoesNotExist()
        shot("settings-passkeys-unavailable")
    }

    @Test
    fun theMobileNumberIsConfirmedWithTheSmsChangedAndDeleted() {
        settingsWithPasskeys("""{"data":[]}""")
        stub("GET", "account/phone", PasswordlessFixtures.phone())
        stub("POST", "account/phone", PasswordlessFixtures.LOCKED, status = 423)
        showWithDevice { AccountScreen(AccountRoute.Settings) }
        waitForTag("phone.field")
        compose.onNodeWithTag("phone.send").assertIsNotEnabled()
        shot("settings-phone")
        type("Handynummer", "0176 12345678")
        compose.onNodeWithTag("phone.send").tap()
        waitForTag("confirm.password")
        compose.onAllNodesText("Abbrechen").last().press()
        compose.waitUntil(5_000) { compose.onAllNodesText("Kurz bestätigen").isEmpty() }
        compose.onNodeWithTag("phone.send").tap()
        waitForTag("confirm.password")
        stub("POST", "account/confirm-password", Fixtures.account)
        stub("POST", "account/phone", PasswordlessFixtures.phone(pending = "+49 176 ••• 78"), status = 202)
        type("Passwort", "geheim")
        compose.onNodeWithTag("confirm.submit").press()
        waitForTag("phone.code")
        waitForText("Wartet auf Bestätigung")
        compose.waitUntil(5_000) { smsRequests.isNotEmpty() }
        shot("settings-phone-pending")

        stub("POST", "account/phone/confirm", """{"message":"x","errors":{"code":["Der Code stimmt nicht."]}}""", status = 422)
        type("Code aus der SMS an +49 176 ••• 78", "111111")
        compose.onNodeWithContentDescription("Code aus der SMS an +49 176 ••• 78", substring = true).performImeAction()
        waitForText("Der Code stimmt nicht.")
        stub("POST", "account/phone/confirm", PasswordlessFixtures.phone(number = "+49 176 ••• 78"))
        field("Code aus der SMS an +49 176 ••• 78").performTextClearance()
        shareSms("Dein Oasis-Code: 123 456")
        waitForText("123456")
        compose.onNodeWithTag("phone.confirm").tap()
        waitForText("Deine Handynummer ist bestätigt.", substring = true)
        waitForText("Bestätigt")
        shot("settings-phone-confirmed")

        compose.onNodeWithTag("phone.edit").tap()
        waitForText("Neue Handynummer")
        compose.onNodeWithTag("phone.cancel").tap()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("phone.field")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("phone.remove").tap()
        waitForText("Deine Handynummer löschen?", substring = true)
        screenShot("settings-phone-remove")
        compose.onAllNodesText("Abbrechen").last().press()
        stub("DELETE", "account/phone", "", status = 204)
        stub("GET", "account/phone", PasswordlessFixtures.phone())
        compose.onNodeWithTag("phone.remove").tap()
        compose.onAllNodesText("Löschen").last().press()
        waitForText("Deine Handynummer ist gelöscht.")
        waitForTag("phone.field")
    }

    @Test
    fun aWaitingNumberCanBeReplaced() {
        settingsWithPasskeys("""{"data":[]}""")
        stub("GET", "account/phone", PasswordlessFixtures.phone(number = "+49 151 ••• 12", pending = "+49 176 ••• 78"))
        stub("POST", "account/phone", """{"message":"x","errors":{"phone_number":["Das ist keine gültige Handynummer."]}}""", status = 422)
        stub("DELETE", "account/phone", "{}", status = 500)
        showWithDevice { AccountScreen(AccountRoute.Settings) }
        waitForTag("phone.pending")
        compose.onNodeWithTag("phone.edit").tap()
        waitForTag("phone.field")
        compose.onNodeWithTag("phone.pending").assertDoesNotExist()
        type("Neue Handynummer", "12")
        compose.onNodeWithTag("phone.send").tap()
        waitForText("Das ist keine gültige Handynummer.")
        compose.onNodeWithTag("phone.cancel").tap()
        compose.onNodeWithTag("phone.remove").tap()
        compose.onAllNodesText("Löschen").last().press()
        waitForText("Da ist etwas schiefgelaufen.", substring = true)
    }
}
