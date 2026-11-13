package de.velwebsolutions.oasis.account.hilt

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.WebRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Lock screen, complaint and glossary as the app shows them. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemberScreensHiltTest : HiltScreenTest() {
    /** The lock as `GET suspension` answers it: a lock with a duration ([endsAt] set) or a ban (null). */
    private fun suspension(
        type: String,
        statement: String = "null",
        statementAt: String = "null",
        excerpt: String = "\"Du bist doch …\"",
        endsAt: String? = "2026-10-29T10:00:00.000000Z",
        complaint: String? = null
    ) = """{"data":{"type":"$type","label":"Sperre durch das Moderationsteam","started_at":"2026-10-22T10:00:00.000000Z",""" +
        """"ends_at":${endsAt?.let { "\"$it\"" }},"ends_at_label":${endsAt?.let { "\"29.10.2026, 11:00 Uhr\"" }},""" +
        """"accusation":"Beleidigung in einer Antwort","excerpt":$excerpt,"statement":$statement,"statement_at":$statementAt,""" +
        """"strikes":1,"strike_limit":3,"strike_months":12,"statement_max":3000${complaint?.let { ",\"complaint\":$it" }.orEmpty()}}}"""

    @Test
    fun theLockWithADurationTakesAStatementAndOffersTheWayOut() {
        signIn(MIRA.copy(isSuspended = true))
        stub("GET", "suspension", suspension("suspension"))
        stub(
            "PUT",
            "suspension/statement",
            """{"message":"Deine Stellungnahme ist angekommen.","statement_at":"2026-10-22T11:00:00.000000Z"}"""
        )
        stub("GET", "account/export", "PK")
        stub("GET", "auth/me", """{"data":${Fixtures.ME}}""")
        stub("POST", "auth/logout", "{}")
        show { AccountScreen(AccountRoute.Suspended) }
        waitForText("Dein Konto ist bis zum 29.10.2026, 11:00 Uhr gesperrt")
        compose.onNodeWithText("Die Sperre endet am 29.10.2026, 11:00 Uhr von selbst.", substring = true).assertExists()
        compose.onNodeWithText("Entschieden hat das ein Mensch aus dem Moderationsteam", substring = true).assertExists()
        compose.onNodeWithText("Bestätigte Verstöße in den letzten 12 Monaten: ", substring = true).assertExists()
        compose.onNodeWithText("innerhalb von 12 Monaten sperren wir ein Konto dauerhaft.", substring = true).assertExists()
        compose.onNodeWithText("vorläufig", substring = true).assertDoesNotExist()
        shot("suspended-duration")
        // An older server without `complaint`: no case here, so the complaint goes by e-mail to the DSA address.
        compose.onNodeWithText("Eine Stellungnahme ist keine Beschwerde gegen die Entscheidung", substring = true).assertExists()
        compose.onNodeWithTag("suspended.complaintClosed").assertExists()
        compose.onNodeWithText(
            "Hier geht das gerade nicht, weil zu dieser Sperre kein Fall hinterlegt ist. Schreib deine Beschwerde per E-Mail an " +
                "info@velwebsolutions.de, oder nutze den Link in der E-Mail zur Entscheidung."
        ).assertExists()
        type("Stellungnahme", "Es tut mir leid.")
        compose.onNodeWithTag("suspended.send").tap()
        waitForText("Zuletzt gesendet am", substring = true)
        compose.onNodeWithText("Community-Regeln").tap()
        assertTrue(web.single().endsWith("/community-regeln"))
        compose.onNodeWithText("Mitgliedschaft ansehen oder kündigen").tap()
        compose.onNodeWithText("Einstellungen und Konto löschen").tap()
        assertEquals(listOf(MembershipRoute.Overview, AccountRoute.Settings), navigator.stack(navigator.selectedTab).toList())
        compose.onNodeWithTag("settings.export").tap()
        waitForRequest("GET", "account/export")
        compose.onNodeWithTag("suspended.signout").tap()
        waitForRequest("POST", "auth/logout")
    }

    @Test
    fun theBanAndTheTemporaryLockAndALiftedLock() {
        signIn()
        stub(
            "GET",
            "suspension",
            suspension("ban", statement = "\"Falsch.\"", statementAt = "\"2026-10-22T11:00:00.000000Z\"", excerpt = "null", endsAt = null)
        )
        stub("PUT", "suspension/statement", """{"message":"x","errors":{"statement":["Höchstens 3000 Zeichen."]}}""", status = 422)
        show { AccountScreen(AccountRoute.Suspended) }
        waitForText("Dein Konto ist dauerhaft gesperrt")
        compose.onNodeWithText("Du kannst Mildcrowd nicht mehr nutzen.", substring = true).assertExists()
        compose.onNodeWithText("bis zum", substring = true).assertDoesNotExist()
        shot("suspended-ban")
        compose.onNodeWithTag("suspended.send").tap()
        waitForText("Höchstens 3000 Zeichen.")
    }

    /** An older answer without `ends_at` (or a lock staff released early) still decodes and reads as a plain lock. */
    @Test
    fun aLockWithoutAPlannedEndAndALiftedOne() {
        signIn()
        stub("GET", "suspension", suspension("suspension", endsAt = null).replace(""""strike_months":12,""", """"review_hours":24,"""))
        stub("PUT", "suspension/statement", """{"message":"Server Error"}""", status = 500)
        show { AccountScreen(AccountRoute.Suspended) }
        waitForText("Dein Konto ist gesperrt")
        compose.onNodeWithText("Das Moderationsteam hebt die Sperre auf", substring = true).assertExists()
        compose.onNodeWithTag("suspended.send").tap()
        waitForRequest("PUT", "suspension/statement")
        stub("GET", "suspension", """{"message":"Not Found"}""", status = 404)
        stub("GET", "auth/me", """{"data":${Fixtures.ME}}""")
        compose.onNodeWithTag("suspended.title").assertExists()
    }

    @Test
    fun aLiftedLockLeadsBackIntoOasis() {
        signIn(MIRA.copy(isSuspended = true))
        stub("GET", "suspension", """{"message":"Not Found"}""", status = 404)
        stub("GET", "auth/me", """{"data":${Fixtures.ME}}""")
        show { AccountScreen(AccountRoute.Suspended) }
        waitForText("Dein Konto ist nicht gesperrt.")
        shot("suspended-lifted")
        compose.onNodeWithText("Weiter zu Mildcrowd").tap()
        compose.waitUntil(5_000) { session.me?.isSuspended == false }
    }

    @Test
    fun theComplaintIsFiledFromTheLockScreen() {
        signIn(MIRA.copy(isSuspended = true))
        stub(
            "GET",
            "suspension",
            suspension(
                "suspension",
                complaint = """{"decision_id":7,"deadline":"2026-11-22T10:00:00.000000Z","allowed":true,"submitted_at":null,""" +
                    """"email":"dsa@mildcrowd.com"}"""
            )
        )
        stub("POST", "complaints/7", """{"message":"x","errors":{"reason":["Bitte begründe die Beschwerde."]}}""", status = 422)
        show { AccountScreen(AccountRoute.Suspended) }
        waitForTag("suspended.complaint")
        compose.onNodeWithText("Beschwerde gegen die Entscheidung").assertExists()
        compose.onNodeWithText("Eine andere Person aus dem Moderationsteam als die, die entschieden hat", substring = true).assertExists()
        compose.onNodeWithText("Möglich bis zum 22.11.2026.").assertExists()
        compose.onNodeWithTag("suspended.complaintClosed").assertDoesNotExist()
        shot("suspended-complaint")
        compose.onNodeWithText("AGB § 15").tap()
        compose.onNodeWithTag("suspended.complaintMail").tap()
        assertTrue(web.first().endsWith("/agb#beschwerde"))
        assertEquals("mailto:dsa@mildcrowd.com", web.last())
        type("Warum hältst du die Entscheidung für falsch?", "Das war Ironie.")
        compose.onNodeWithTag("suspended.complain").tap()
        waitForText("Bitte begründe die Beschwerde.")
        stub("POST", "complaints/7", "{}")
        compose.onNodeWithTag("suspended.complain").tap()
        waitForTag("suspended.complaintThanks")
        compose.onNodeWithText(
            "Danke, deine Beschwerde ist da. Die Eingangsbestätigung haben wir dir per E-Mail geschickt; das Ergebnis und die " +
                "Begründung folgen per E-Mail."
        ).assertExists()
        compose.onNodeWithTag("suspended.complaint").assertDoesNotExist()
    }

    @Test
    fun aComplaintAlreadyInOrNoLongerPossibleOnTheLockScreen() {
        signIn(MIRA.copy(isSuspended = true))
        val received = """{"decision_id":7,"deadline":"2026-11-22T10:00:00.000000Z","allowed":false,""" +
            """"submitted_at":"2026-10-23T09:00:00.000000Z","email":"dsa@mildcrowd.com"}"""
        stub("GET", "suspension", suspension("suspension", complaint = received))
        show { AccountScreen(AccountRoute.Suspended) }
        waitForTag("suspended.complaintThanks")
        compose.onNodeWithTag("suspended.complaint").assertDoesNotExist()
    }

    @Test
    fun aComplaintPastItsDeadlineGoesByEmail() {
        signIn(MIRA.copy(isSuspended = true))
        val closed = """{"decision_id":7,"deadline":"2026-10-01T10:00:00.000000Z","allowed":false,"submitted_at":null,""" +
            """"email":"dsa@mildcrowd.com"}"""
        stub("GET", "suspension", suspension("ban", endsAt = null, complaint = closed))
        show { AccountScreen(AccountRoute.Suspended) }
        waitForTag("suspended.complaintClosed")
        compose.onNodeWithText(
            "Hier geht das gerade nicht. Schreib deine Beschwerde per E-Mail an dsa@mildcrowd.com, oder nutze den Link in der E-Mail " +
                "zur Entscheidung."
        ).assertExists()
        compose.onNodeWithText("E-Mail an dsa@mildcrowd.com").assertExists()
    }

    private val decision =
        """{"data":{"id":7,"outcome":"Tropfen entfernt","ground":"Beleidigung","rule_reference":"§ 3 Umgangston",""" +
            """"explanation":"Der Tropfen beleidigt eine Person.","deadline":"2026-11-22T10:00:00.000000Z"}}"""

    @Test
    fun aComplaintIsSentAndThanked() {
        signIn()
        stub("GET", "complaints/7", decision)
        stub("POST", "complaints/7", "{}")
        navigator.stack(navigator.selectedTab).add(AccountRoute.Complaint(7))
        show { AccountScreen(AccountRoute.Complaint(7)) }
        waitForText("Tropfen entfernt · Beleidigung")
        // AGB § 15 and § 20: the way to court, and no out-of-court dispute body for Oasis (Art. 21 DSA does not apply).
        waitForText(
            "Du kannst bis zum 22.11.2026 Beschwerde einlegen. Die Beschwerde ist kostenlos. Unabhängig davon steht dir der " +
                "Rechtsweg offen. Eine außergerichtliche Streitbeilegungsstelle gibt es für Mildcrowd nicht (AGB § 15 und § 20)."
        )
        shot("complaint")
        type("Warum hältst du die Entscheidung für falsch?", "Das war Ironie.")
        compose.onNodeWithTag("complaint.submit").tap()
        waitForTag("complaint.thanks")
        shot("complaint-thanks")
        compose.onNodeWithText("Fertig").tap()
        assertTrue(navigator.stack(navigator.selectedTab).isEmpty())
    }

    @Test
    fun aComplaintIsRefusedOrThrottled() {
        signIn()
        stub("GET", "complaints/7", decision.replace("§ 3 Umgangston", ""))
        stub("POST", "complaints/7", """{"message":"x","errors":{"reason":["Bitte begründe die Beschwerde."]}}""", status = 422)
        show { AccountScreen(AccountRoute.Complaint(7)) }
        waitForTag("complaint.reason")
        type("Warum hältst du die Entscheidung für falsch?", "Darum.")
        compose.onNodeWithTag("complaint.submit").tap()
        waitForText("Bitte begründe die Beschwerde.")
        stub("POST", "complaints/7", """{"message":"Too Many Attempts."}""", status = 429)
        compose.onNodeWithTag("complaint.submit").tap()
        waitForText("Zu viele Beschwerden. Bitte versuche es in einer Minute erneut.")
        stub("POST", "complaints/7", """{"message":"Server Error"}""", status = 500)
        compose.onNodeWithTag("complaint.submit").tap()
        waitForRequest("POST", "complaints/7")
    }

    @Test
    fun aComplaintOpenedAsSheetClosesTheSheet() {
        signIn()
        stub("GET", "complaints/7", decision)
        stub("POST", "complaints/7", "{}")
        navigator.present(AccountRoute.Complaint(7))
        show { AccountScreen(AccountRoute.Complaint(7)) }
        waitForTag("complaint.reason")
        type("Warum hältst du die Entscheidung für falsch?", "Bitte prüfen.")
        compose.onNodeWithTag("complaint.submit").tap()
        waitForTag("complaint.thanks")
        compose.onNodeWithText("Fertig").tap()
        assertEquals(null, navigator.sheet)
    }

    @Test
    fun theGlossaryExplainsTheWords() {
        signIn()
        stub("GET", "glossary", Fixtures.glossary)
        show { AccountScreen(AccountRoute.Glossary) }
        waitForText("Tropfen")
        shot("glossary")
    }

    @Test
    fun webPagesOpenOutsideTheApp() {
        navigator.open(WebRoute("https://mildcrowd.com/agb"))
        assertEquals(listOf("https://mildcrowd.com/agb"), web)
    }
}
