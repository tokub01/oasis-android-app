package de.velwebsolutions.oasis.account.hilt

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.account.SignedOutScreen
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pull to refresh, closing from the root, rotating the device and the rarer states of the account screens. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountStatesHiltTest : HiltScreenTest() {
    private val serverError = """{"message":"Server Error"}"""

    private fun pull() = compose.onRoot().performTouchInput { swipeDown(startY = centerY / 2, endY = bottom, durationMillis = 400) }

    private fun refreshes(route: AccountRoute, path: String, json: String, readyTag: String) {
        stub("GET", path, json)
        show { AccountScreen(route) }
        waitForTag(readyTag)
        stub("GET", path, serverError, status = 500)
        pull()
        compose.waitUntil(5_000) { transport.requests.count { it.path == path } >= 2 }
    }

    @Test
    fun theLockScreenRefreshes() {
        signIn(MIRA.copy(isSuspended = true))
        stub("GET", "auth/me", """{"data":${Fixtures.ME}}""")
        refreshes(AccountRoute.Suspended, "suspension", de.velwebsolutions.oasis.account.AccountEdgeCasesTest.SUSPENSION, "suspended.title")
    }

    @Test
    fun votesRefresh() {
        signIn()
        refreshes(AccountRoute.Votes, "votes", Fixtures.votes.replace("\"can_vote\": false", "\"can_vote\": true"), "vote.cast.1")
    }

    @Test
    fun thePrivacyCentreRefreshes() {
        signIn()
        refreshes(AccountRoute.Privacy, "privacy", Fixtures.privacy, "privacy.checkup")
    }

    @Test
    fun devicesRefresh() {
        signIn()
        refreshes(AccountRoute.Devices, "privacy/devices", Fixtures.devices, "devices.signOutOthers")
    }

    @Test
    fun theSafetyListsRefresh() {
        signIn()
        stub("GET", "safety", Fixtures.safety)
        show { AccountScreen(AccountRoute.Safety) }
        waitForText("Du hast aktuell niemanden blockiert.")
        stub("GET", "safety", serverError, status = 500)
        pull()
        compose.waitUntil(5_000) { transport.requests.count { it.path == "safety" } >= 2 }
    }

    @Test
    fun aComplaintAndTheCheckupOpenedAtTheRootCloseTheSheet() {
        signIn()
        stub("GET", "complaints/7", """{"data":{"id":7,"outcome":"Entfernt","ground":"Spam","deadline":"2026-11-22T10:00:00.000000Z"}}""")
        stub("POST", "complaints/7", "{}")
        show { AccountScreen(AccountRoute.Complaint(7)) }
        waitForTag("complaint.submit")
        type("Warum hältst du die Entscheidung für falsch?", "Kein Spam.")
        compose.onNodeWithTag("complaint.submit").tap()
        waitForTag("complaint.thanks")
        compose.onNodeWithText("Fertig").tap()
        assertEquals(null, navigator.sheet)
    }

    @Test
    fun theCheckupAtTheRoot() {
        signIn()
        stub("GET", "privacy", """{"data":{"sections":[],"counts":{}}}""")
        show { AccountScreen(AccountRoute.PrivacyCheckup) }
        waitForTag("checkup.done")
        compose.onNodeWithTag("checkup.done").tap()
        assertEquals(null, navigator.sheet)
    }

    @Test
    fun anOasisWithoutBannerAndASearchWithoutText() {
        signIn()
        stub(
            "GET",
            "onboarding",
            """{"data":{"steps":[{"key":"oasen","title":"Oasen"},{"key":"menschen","title":"Menschen"}],"onboarded":true,""" +
                """"oases":[{"id":1,"slug":"garten","name":"Garten","members_count":1,"joined":true}]}}"""
        )
        stub("POST", "onboarding/oases/1", """{"joined":false}""")
        show { AccountScreen(AccountRoute.Welcome) }
        waitForText("1 Mitglied")
        compose.onNodeWithTag("welcome.oasis.garten").tap()
        waitForRequest("POST", "onboarding/oases/1")
        compose.onNodeWithTag("welcome.next").tap()
        compose.onNodeWithText("Suchen").tap()
        assertEquals(NetworkRoute.Search(null), navigator.stack(navigator.selectedTab).last())
    }

    @Test
    fun aPendingSetupWithoutKeyAndAChatDeviceAmongSeveral() {
        signIn()
        stub(
            "GET",
            "account",
            """{"data":{"display_name":"Mira","username":"mira","email":"m@x.tld","two_factor":{"state":"pending","otpauth_url":"otpauth://totp/x"}}}"""
        )
        show { AccountScreen(AccountRoute.Settings) }
        waitForTag("settings.qrCode")
        compose.onNodeWithTag("settings.setupKey").assertDoesNotExist()
    }

    @Test
    fun withoutPassphraseTheSecondChatDeviceGoesAtOnce() {
        signIn()
        stub(
            "GET",
            "privacy/devices",
            """{"data":{"current":{"device":"App","ip":"1"},""" +
                """"chat_devices":[{"id":3,"label":"A","created_at":"2026-10-01T10:00:00.000000Z"},""" +
                """{"id":4,"label":"B","created_at":"2026-10-02T10:00:00.000000Z"}],"chat_passphrase":false}}"""
        )
        stub("DELETE", "privacy/devices/chat/3", """{"message":"Entfernt."}""")
        show { AccountScreen(AccountRoute.Devices) }
        waitForText("Entfernen")
        compose.onAllNodesText("Entfernen").first().tap()
        waitForRequest("DELETE", "privacy/devices/chat/3")
    }

    @Test
    fun theExportPickerCanBeCancelledFailOrComeBackAfterARotation() {
        signIn()
        stub("GET", "account", Fixtures.account)
        stub("GET", "account/export", "PK")
        show { AccountScreen(AccountRoute.Settings) }
        waitForTag("settings.export")
        compose.onNodeWithTag("settings.export").tap()
        waitForTag("settings.exportShare")
        compose.onNodeWithTag("settings.exportShare").tap()
        var picker = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_CANCELED, null)
        compose.onNodeWithTag("settings.exportShare").tap()
        picker = shadowOf(activity).nextStartedActivityForResult
        shadowOf(
            activity
        ).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(Uri.parse("content://nowhere/oasis-daten.zip")))
        waitForText("Die Datei konnte nicht gespeichert werden.")
        compose.onNodeWithTag("settings.exportShare").tap()
        picker = shadowOf(activity).nextStartedActivityForResult
        recreate()
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(Uri.parse("content://nowhere/x.zip")))
        waitForTag("settings.export")
    }

    @Test
    fun theSignedOutStepsSurviveARotation() {
        stub("GET", "auth/register", """{"data":{"honeypot_field":"w","token_field":"t","form_token":"a","trial_days":30}}""")
        stub("POST", "auth/login", """{"two_factor":true,"challenge":"chal-1"}""")
        show { SignedOutScreen() }
        waitForTag("forgot.button")
        type("E-Mail oder Benutzername", "mira")
        type("Passwort", "geheim")
        compose.onNodeWithTag("signin.button").tap()
        waitForTag("twofactor.lost")
        compose.onNodeWithTag("twofactor.lost").tap()
        waitForTag("recovery.submit")
        recreate()
        waitForTag("recovery.submit")
        activity.onBackPressedDispatcher.onBackPressed()
        waitForTag("twofactor.submit")
        activity.onBackPressedDispatcher.onBackPressed()
        compose.onNodeWithTag("register.button").tap()
        waitForTag("register.submit")
        compose.onNodeWithTag("register.terms").tap()
        compose.onNodeWithTag("register.terms").tap()
        recreate()
        waitForTag("register.submit")
        assertTrue(transport.requests.isNotEmpty())
    }
}
