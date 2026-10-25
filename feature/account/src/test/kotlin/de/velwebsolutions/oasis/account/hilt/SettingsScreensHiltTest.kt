package de.velwebsolutions.oasis.account.hilt

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.MainTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Einstellungen: profile, password, two-factor with the password confirmation, data export, deletion, help. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreensHiltTest : HiltScreenTest() {
    private fun settings(state: String, verified: Boolean = true) =
        """{"display_name":"Mira Sol","username":"mira","email":"mira@demo.oasis.tld","email_verified":$verified,""" +
            """"two_factor":{"state":"$state","setup_key":${if (state == "pending") "\"ABCDEFGHIJKLMNOP\"" else "null"},""" +
            """"otpauth_url":${if (state == "pending") "\"$OTPAUTH\"" else "null"}},""" +
            """"password_confirmed":true}"""

    private companion object {
        const val OTPAUTH = "otpauth://totp/Oasis:mira?secret=ABCDEFGHIJKLMNOP&issuer=Oasis"
    }

    private fun change(message: String, state: String, codes: String = "null") =
        """{"message":"$message","data":${settings(state)},"recovery_codes":$codes}"""

    @Test
    fun twoFactorIsSwitchedOnAfterThePasswordAndConfirmedWithACode() {
        signIn()
        stub("GET", "account", Fixtures.account)
        stub("POST", "account/two-factor", """{"message":"Bitte bestätige zuerst dein Passwort."}""", status = 423)
        stub("POST", "account/confirm-password", """{"message":"x","errors":{"password":["Das Passwort stimmt nicht."]}}""", status = 422)
        show { AccountScreen(AccountRoute.Settings) }
        waitForTag("settings.twoFactorEnable")
        shot("settings")
        compose.onNodeWithTag("settings.twoFactorEnable").tap()
        waitForTag("confirm.password")
        screenShot("settings-confirm")
        type("Passwort", "falsch")
        compose.onNodeWithTag("confirm.submit").tap()
        waitForText("Das Passwort stimmt nicht.")
        stub("POST", "account/confirm-password", """{"data":${settings("off")}}""")
        stub("POST", "account/two-factor", change("Scanne jetzt den QR-Code.", "pending"))
        compose.onNodeWithTag("confirm.submit").tap()
        waitForTag("settings.qrCode")
        shot("settings-pending")
        compose.onNodeWithText("In der Authenticator-App öffnen").tap()
        assertEquals(Intent.ACTION_VIEW, shadowOf(activity.application).nextStartedActivity.action)
        stub(
            "POST",
            "account/two-factor/confirm",
            change("Die Zwei-Faktor-Anmeldung ist aktiv.", "active", """["aaaa-bbbb","cccc-dddd","eeee-ffff"]""")
        )
        type("Sechsstelliger Code", "123456")
        compose.onNodeWithTag("settings.twoFactorConfirm").tap()
        waitForTag("settings.recoveryCodes")
        shot("settings-codes")
        compose.onNodeWithText("Codes sichern").tap()
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity.application).nextStartedActivity.action)
        stub("POST", "account/two-factor/recovery-codes", change("Neue Codes.", "active", """["1111-2222"]"""))
        compose.onNodeWithTag("settings.regenerateCodes").tap()
        waitForText("1111-2222")
        stub("DELETE", "account/two-factor", change("Ausgeschaltet.", "off"))
        compose.onNodeWithTag("settings.twoFactorDisable").tap()
        waitForTag("settings.twoFactorEnable")
    }

    @Test
    fun withoutAnAuthenticatorAppTheMemberHearsSo() {
        signIn()
        stub("GET", "account", """{"data":${settings("pending")}}""")
        show { AccountScreen(AccountRoute.Settings) }
        waitForTag("settings.qrCode")
        shadowOf(activity.application).checkActivities(true)
        compose.onNodeWithText("In der Authenticator-App öffnen").tap()
        waitForText("Auf diesem Gerät ist keine Authenticator-App installiert", substring = true)
    }

    @Test
    fun theProfileThePasswordAndTheConfirmationSheetCanBeCancelled() {
        signIn()
        stub("GET", "account", """{"data":${settings("off", verified = false)}}""")
        stub("PUT", "account/profile", """{"message":"Gespeichert.","data":${Fixtures.ME}}""")
        stub(
            "PUT",
            "account/password",
            """{"message":"x","errors":{"current_password":["Das aktuelle Passwort stimmt nicht."]}}""",
            status = 422
        )
        stub("POST", "account/two-factor", """{"message":"Bitte bestätige zuerst dein Passwort."}""", status = 423)
        show { AccountScreen(AccountRoute.Settings) }
        waitForText("Deine E-Mail-Adresse ist noch nicht bestätigt.", substring = true)
        type("Anzeigename", " M.")
        type("E-Mail-Adresse", "x")
        compose.onNodeWithTag("settings.saveProfile").tap()
        waitForRequest("PUT", "account/profile")
        type("Aktuelles Passwort", "alt")
        type("Neues Passwort", "neu-neu-neu")
        type("Neues Passwort wiederholen", "neu-neu-neu")
        compose.onNodeWithText("Passwort ändern").tap()
        waitForText("Das aktuelle Passwort stimmt nicht.")
        compose.onNodeWithTag("settings.twoFactorEnable").tap()
        waitForTag("confirm.password")
        compose.onAllNodesText("Abbrechen").last().press()
        compose.waitUntil(5_000) { compose.onAllNodesText("Kurz bestätigen").isEmpty() }
    }

    @Test
    fun theAccountIsDeletedOnlyWithTheConsentAndSignsOut() {
        signIn()
        stub("GET", "account", Fixtures.account)
        stub("DELETE", "account", "{}")
        stub("POST", "auth/logout", "{}")
        stub("GET", "account/export", "PK")
        show { AccountScreen(AccountRoute.Settings) }
        waitForTag("settings.delete")
        compose.onNodeWithTag("settings.export").tap()
        waitForTag("settings.exportShare")
        compose.onNodeWithTag("settings.exportShare").tap()
        val picker = shadowOf(activity).nextStartedActivityForResult
        val target = Uri.fromFile(java.io.File(activity.cacheDir, "oasis-daten.zip"))
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(target))
        compose.waitUntil(5_000) { java.io.File(activity.cacheDir, "oasis-daten.zip").exists() }
        compose.onAllNodesText("Verträge hier kündigen").first().tap()
        assertTrue(web.last().endsWith("/vertraege-kuendigen"))
        compose.onNodeWithTag("settings.deleteConfirm").tap()
        compose.onNodeWithTag("settings.delete").tap()
        waitForRequest("POST", "auth/logout")
    }

    @Test
    fun helpPagesTheGlossaryTheWelcomeTourAndSigningOut() {
        signIn()
        stub("GET", "account", Fixtures.account)
        stub("POST", "auth/logout", "{}")
        navigator.selectedTab = MainTab.Profile
        show { AccountScreen(AccountRoute.Settings) }
        waitForTag("help.hilfe")
        compose.onNodeWithText("Neu hier? Einstieg noch einmal ansehen").tap()
        compose.onNodeWithTag("help.begriffe").tap()
        listOf(
            "hilfe", "community-regeln", "sicherheit", "datenschutz", "agb", "widerrufsbelehrung", "barrierefreiheit", "impressum",
            "inhalte-melden", "vertraege-kuendigen", "vertrag-widerrufen"
        ).forEach { compose.onNodeWithTag("help.$it").tap() }
        assertEquals(11, web.size)
        assertEquals(listOf(AccountRoute.Welcome, AccountRoute.Glossary), navigator.stack(MainTab.Profile).toList())
        compose.onNodeWithTag("settings.signout").tap()
        waitForRequest("POST", "auth/logout")
    }

    @Test
    fun aFailedExportOrReloadIsReported() {
        signIn(MIRA.copy(isSuspended = true))
        stub("GET", "account", Fixtures.account)
        stub("GET", "account/export", """{"message":"Server Error"}""", status = 500)
        show { AccountScreen(AccountRoute.Settings) }
        waitForTag("settings.export")
        compose.onNodeWithText("Neu hier? Einstieg noch einmal ansehen").assertDoesNotExist()
        compose.onNodeWithTag("settings.export").tap()
        waitForRequest("GET", "account/export")
    }
}
