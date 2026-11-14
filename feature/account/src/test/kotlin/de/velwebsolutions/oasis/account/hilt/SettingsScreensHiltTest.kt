package de.velwebsolutions.oasis.account.hilt

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.WindowManager
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.account.ui.settings.KEY_COPIED
import de.velwebsolutions.oasis.account.ui.settings.SECRET_CLIP_MILLIS
import de.velwebsolutions.oasis.account.ui.settings.SENSITIVE_CLIP
import de.velwebsolutions.oasis.account.ui.settings.copySecret
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.theme.Appearance
import de.velwebsolutions.oasis.design.theme.AppearanceChoice
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Whether screenshots, recordings and the recent apps see the window. */
    private fun secure() = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    private fun clip() = activity.getSystemService(ClipboardManager::class.java).primaryClip!!

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun theSettingsInDarkMode() {
        signIn()
        stub("GET", "account", """{"data":${settings("off")}}""")
        Appearance.show(AppearanceChoice.Dark)
        try {
            show { AccountScreen(AccountRoute.Settings) }
            waitForTag("settings.twoFactorEnable")
            shot("settings-dark")
        } finally {
            Appearance.show(AppearanceChoice.Automatic)
        }
    }

    @Test
    fun theSecretAndTheCodesStayOutOfScreenshotsAndAreCopiedAsSensitive() {
        signIn()
        stub("GET", "account", """{"data":${settings("pending")}}""")
        val active = change("Die Zwei-Faktor-Anmeldung ist aktiv.", "active", """["aaaa-bbbb","cccc-dddd"]""")
        stub("POST", "account/two-factor/confirm", active)
        stub("DELETE", "account/two-factor", change("Ausgeschaltet.", "off"))
        show { AccountScreen(AccountRoute.Settings) }
        waitForTag("settings.setupKey")
        assertTrue("the shared secret keeps the window secure", secure())
        compose.onNodeWithTag("settings.copySetupKey").tap()
        assertEquals("ABCDEFGHIJKLMNOP", clip().getItemAt(0).text)
        assertTrue("copied as sensitive", clip().description.extras?.getBoolean(SENSITIVE_CLIP) == true)
        waitForText(KEY_COPIED)
        type("Sechsstelliger Code", "123456")
        compose.onNodeWithTag("settings.twoFactorConfirm").tap()
        waitForTag("settings.recoveryCodes")
        assertTrue("the recovery codes keep it secure", secure())
        compose.onNodeWithTag("settings.copyCodes").tap()
        assertEquals("aaaa-bbbb\ncccc-dddd", clip().getItemAt(0).text)
        assertTrue(clip().description.extras?.getBoolean(SENSITIVE_CLIP) == true)
        compose.onNodeWithTag("settings.twoFactorDisable").tap()
        waitForTag("settings.twoFactorEnable")
        assertFalse("without a secret on screen the window is as before", secure())
    }

    @Test
    fun aCopiedSecretLeavesTheClipboardAfterAMinuteUnlessSomethingElseWasCopiedSince() {
        val app = ApplicationProvider.getApplicationContext<HiltTestApplication>()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        copySecret(app, "ABCDEFGHIJKLMNOP")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SECRET_CLIP_MILLIS - 1))
        assertEquals("time to paste it", "ABCDEFGHIJKLMNOP", clipboard.primaryClip?.getItemAt(0)?.text)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse("a minute later the secret is off the clipboard", clipboard.hasPrimaryClip())
        copySecret(app, "aaaa-bbbb")
        clipboard.setPrimaryClip(ClipData.newPlainText("Notiz", "Einkaufsliste"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SECRET_CLIP_MILLIS))
        assertEquals("what was copied since stays", "Einkaufsliste", clipboard.primaryClip?.getItemAt(0)?.text)
        // Each copy is told apart by its mark, not by its text: the same key copied again gets its own minute, and the
        // same text copied in another app (a password manager) is not the app's to remove.
        copySecret(app, "ABCDEFGHIJKLMNOP")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SECRET_CLIP_MILLIS / 2))
        copySecret(app, "ABCDEFGHIJKLMNOP")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SECRET_CLIP_MILLIS / 2))
        assertEquals("copied again, the key stays a minute again", "ABCDEFGHIJKLMNOP", clipboard.primaryClip?.getItemAt(0)?.text)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SECRET_CLIP_MILLIS / 2))
        assertFalse(clipboard.hasPrimaryClip())
        copySecret(app, "ABCDEFGHIJKLMNOP")
        clipboard.setPrimaryClip(ClipData.newPlainText("Passwörter", "ABCDEFGHIJKLMNOP"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SECRET_CLIP_MILLIS))
        assertTrue("the same text copied elsewhere stays", clipboard.hasPrimaryClip())
        // Nothing to be seen on the clipboard after the minute (emptied meanwhile, or Mildcrowd in the background from
        // Android 10 on): it is emptied all the same.
        copySecret(app, "ABCDEFGHIJKLMNOP")
        clipboard.clearPrimaryClip()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SECRET_CLIP_MILLIS))
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun aWindowThatWasSecureAlreadyStaysSecure() {
        signIn()
        stub("GET", "account", """{"data":${settings("off")}}""")
        stub("POST", "account/two-factor", change("Scanne jetzt den QR-Code.", "pending"))
        stub("POST", "account/two-factor/confirm", change("Die Zwei-Faktor-Anmeldung ist aktiv.", "active", """["aaaa-bbbb"]"""))
        stub("DELETE", "account/two-factor", change("Ausgeschaltet.", "off"))
        show { AccountScreen(AccountRoute.Settings) }
        waitForTag("settings.twoFactorEnable")
        assertFalse(secure())
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        compose.onNodeWithTag("settings.twoFactorEnable").tap()
        waitForTag("settings.setupKey")
        type("Sechsstelliger Code", "123456")
        compose.onNodeWithTag("settings.twoFactorConfirm").tap()
        waitForTag("settings.recoveryCodes")
        compose.onNodeWithTag("settings.twoFactorDisable").tap()
        waitForTag("settings.twoFactorEnable")
        assertTrue("another reason to keep the window secure is not undone", secure())
    }

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
        compose.onNodeWithTag("settings.deletePrivacy").tap()
        assertTrue(web.last().endsWith("/datenschutz"))
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
        compose.onNodeWithTag("help.neuigkeiten").tap()
        listOf(
            "hilfe", "community-regeln", "sicherheit", "datenschutz", "agb", "widerrufsbelehrung", "barrierefreiheit", "impressum",
            "inhalte-melden", "vertraege-kuendigen", "vertrag-widerrufen"
        ).forEach { compose.onNodeWithTag("help.$it").tap() }
        assertEquals(11, web.size)
        assertEquals(
            listOf(AccountRoute.Welcome, AccountRoute.Glossary, AccountRoute.Releases),
            navigator.stack(MainTab.Profile).toList()
        )
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

    @Test
    fun theNameCheckRefusesTheDisplayNameAtItsField() {
        signIn()
        stub("GET", "account", Fixtures.account)
        stub("PUT", "account/profile", """{"message":"x","errors":{"display_name":["Dieser Name ist nicht erlaubt."]}}""", status = 422)
        show { AccountScreen(AccountRoute.Settings) }
        waitForTag("settings.saveProfile")
        type("Anzeigename", "!")
        compose.onNodeWithTag("settings.saveProfile").tap()
        waitForText("Dieser Name ist nicht erlaubt.")
    }
}
