package de.velwebsolutions.oasis.account.hilt

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Geräte, Blockiert & stumm and Melden as the app shows them. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SafetyScreensHiltTest : HiltScreenTest() {
    private val chatDevices = """"chat_devices": [{"id":3,"label":"Pixel 9","created_at":"2026-10-01T10:00:00.000000Z"}]"""

    private fun stack() = navigator.stack(navigator.selectedTab).toList()

    @Test
    fun devicesAreSignedOutForgottenAndTheLastChatDeviceAsksFirst() {
        signIn()
        stub("GET", "privacy/devices", Fixtures.devices.replace("\"chat_devices\": []", chatDevices))
        listOf(
            "DELETE" to "privacy/devices/apps/12",
            "DELETE" to "privacy/devices/sessions/790861722d6cff63bc8507158dc695a7895a7830cde03ff7e45b43fa37c22fc0",
            "POST" to "privacy/devices/sign-out-others",
            "DELETE" to "privacy/devices/known/1",
            "DELETE" to "privacy/devices/chat/3"
        ).forEach { (method, path) -> stub(method, path, """{"message":"Erledigt."}""") }
        show { AccountScreen(AccountRoute.Devices) }
        waitForTag("devices.app.12")
        shot("devices")
        compose.onNodeWithTag("devices.app.12").tap()
        waitForRequest("DELETE", "privacy/devices/apps/12")
        compose.onAllNodesText("Abmelden").last().tap()
        compose.waitUntil(5_000) { transport.requests.any { it.path.startsWith("privacy/devices/sessions/") } }
        compose.onNodeWithText("Vergessen").tap()
        waitForRequest("DELETE", "privacy/devices/known/1")

        compose.onNodeWithTag("devices.signOutOthers").tap()
        screenShot("devices-confirm")
        compose.onNodeWithText("Abbrechen").press()
        compose.onNodeWithTag("devices.signOutOthers").tap()
        compose.onAllNodesText("Überall sonst abmelden").last().press()
        waitForRequest("POST", "privacy/devices/sign-out-others")

        compose.onNodeWithText("Entfernen").tap()
        waitForText("Das ist dein einziger Zugang zum Chat", substring = true)
        compose.onAllNodesText("Entfernen").last().press()
        waitForRequest("DELETE", "privacy/devices/chat/3")

        compose.onNodeWithText("Im Chat ein Gerät einrichten").tap()
        compose.onNodeWithText("Zwei-Faktor-Anmeldung").tap()
        compose.onNodeWithText("Anmelde-Warnungen einstellen").tap()
        assertEquals(listOf(ChatRoute.Conversations, AccountRoute.Settings, AccountRoute.Privacy), stack())
    }

    @Test
    fun devicesWithoutOthersAndAChatPassphrase() {
        signIn()
        stub(
            "GET",
            "privacy/devices",
            """{"data":{"current":{"device":"Oasis-App auf Android","ip":"10.0.0.…"},"apps":[{"id":2,"name":"Tablet"}],""" +
                """"sessions_listable":false,"known":[],"login_alerts":false,""" +
                """"chat_devices":[{"id":3,"label":"Pixel 9","created_at":"2026-10-01T10:00:00.000000Z"},""" +
                """{"id":4,"label":"Tablet","created_at":"2026-10-02T10:00:00.000000Z"}],"chat_passphrase":true}}"""
        )
        stub("DELETE", "privacy/devices/chat/3", """{"message":"Server Error"}""", status = 500)
        show { AccountScreen(AccountRoute.Devices) }
        waitForText("Andere Anmeldungen im Browser können auf diesem Server nicht angezeigt werden.")
        compose.onNodeWithText("Anmelde-Warnungen per E-Mail sind ausgeschaltet.", substring = true).assertExists()
        compose.onNodeWithText("Noch keine Geräte gespeichert.").assertExists()
        shot("devices-passphrase")
        compose.onAllNodesText("Entfernen").first().tap()
        waitForRequest("DELETE", "privacy/devices/chat/3")
    }

    @Test
    fun devicesOfAMemberWhoIsNowhereElse() {
        signIn()
        stub(
            "GET",
            "privacy/devices",
            """{"data":{"current":{"device":"Oasis-App auf Android","ip":"10.0.0.…"},"sessions_listable":true,"login_alerts":true}}"""
        )
        show { AccountScreen(AccountRoute.Devices) }
        waitForText("Du bist auf keinem anderen Gerät angemeldet.")
        compose.onNodeWithText("Noch kein Gerät eingerichtet.").assertExists()
    }

    private val person = """{"id":9,"username":"jonas","display_name":"Jonas Weber","initials":"JW","avatar_color":"spring"}"""

    @Test
    fun blockedMutedAndRestrictedPeopleAreLifted() {
        signIn()
        stub("GET", "safety", """{"data":{"blocked":[$person],"muted":[$person],"restricted":[$person]}}""")
        listOf("blocks", "mutes", "restrictions").forEach { stub("DELETE", "safety/$it/9", """{"message":"Aufgehoben."}""") }
        show { AccountScreen(AccountRoute.Safety) }
        waitForTag("safety.blocks.jonas")
        shot("safety")
        compose.onNodeWithTag("safety.blocks.jonas").tap()
        waitForRequest("DELETE", "safety/blocks/9")
        compose.onNodeWithTag("safety.mutes.jonas").tap()
        compose.onNodeWithTag("safety.restrictions.jonas").tap()
        waitForRequest("DELETE", "safety/restrictions/9")
        compose.onAllNodesText("Jonas Weber")[1].tap()
        compose.onNodeWithText("Zur Merkliste").tap()
        assertEquals(listOf(NetworkRoute.Profile("jonas"), NetworkRoute.Bookmarks), stack())
    }

    @Test
    fun emptySafetyListsAndAFailedLift() {
        signIn()
        stub("GET", "safety", Fixtures.safety)
        show { AccountScreen(AccountRoute.Safety) }
        waitForText("Du hast aktuell niemanden blockiert.")
        compose.onNodeWithText("Du hast aktuell niemanden stummgeschaltet.").assertExists()
        shot("safety-empty")
    }

    @Test
    fun aReportIsSentWithItsCategoryAndThanked() {
        signIn()
        stub("GET", "report", Fixtures.report)
        stub("POST", "report", "{}")
        navigator.present(AccountRoute.Report("http://localhost/profil/jonas"))
        show { AccountScreen(AccountRoute.Report("http://localhost/profil/jonas")) }
        waitForTag("report.send")
        shot("report")
        stub("POST", "report", """{"message":"x","errors":{"category":["Bitte wähle aus, was nicht stimmt."]}}""", status = 422)
        compose.onNodeWithTag("report.send").tap()
        waitForText("Bitte wähle aus, was nicht stimmt.")
        stub("POST", "report", """{"message":"Server Error"}""", status = 500)
        compose.onNodeWithTag("report.send").tap()
        compose.waitUntil(5_000) { transport.requests.count { it.path == "report" && it.request.method == "POST" } == 2 }
        stub("POST", "report", "{}")
        compose.onNodeWithTag("report.category.spam").tap()
        type("Möchtest du noch etwas dazu sagen? (freiwillig)", "Seit Wochen.")
        compose.onNodeWithTag("report.send").tap()
        waitForTag("report.thanks")
        shot("report-thanks")
        compose.onNodeWithText("Zurück zur Quelle").tap()
        assertEquals(null, navigator.sheet)
    }

    @Test
    fun aReportOutsideASheetGoesBackToTheSourceAndOneWithoutLinkIsNotFound() {
        signIn()
        stub("GET", "report", Fixtures.report)
        stub("POST", "report", "{}")
        show { AccountScreen(AccountRoute.Report("http://localhost/profil/jonas")) }
        waitForTag("report.category.spam")
        compose.onNodeWithTag("report.category.spam").tap()
        compose.onNodeWithTag("report.send").tap()
        waitForTag("report.thanks")
        compose.onNodeWithText("Zurück zur Quelle").tap()
        assertTrue(stack().isEmpty())
    }

    @Test
    fun aReportWithoutLink() {
        signIn()
        show { AccountScreen(AccountRoute.Report(null)) }
        compose.waitForIdle()
        assertTrue(transport.requests.none { it.path == "report" })
        shot("report-missing")
    }
}
