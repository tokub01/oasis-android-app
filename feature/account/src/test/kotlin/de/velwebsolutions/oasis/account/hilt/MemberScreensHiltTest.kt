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

/** Lock screen, complaint, votes and glossary as the app shows them. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemberScreensHiltTest : HiltScreenTest() {
    private fun suspension(type: String, statement: String = "null", statementAt: String = "null", excerpt: String = "\"Du bist doch …\"") =
        """{"data":{"type":"$type","label":"Vorläufig gesperrt","started_at":"2026-10-22T10:00:00.000000Z",""" +
            """"accusation":"Beleidigung in einer Antwort","excerpt":$excerpt,"statement":$statement,"statement_at":$statementAt,""" +
            """"strikes":1,"strike_limit":3,"review_hours":24,"statement_max":3000}}"""

    @Test
    fun theProvisionalLockTakesAStatementAndOffersTheWayOut() {
        signIn(MIRA.copy(isSuspended = true))
        stub("GET", "suspension", suspension("provisional"))
        stub(
            "PUT",
            "suspension/statement",
            """{"message":"Deine Stellungnahme ist angekommen.","statement_at":"2026-10-22T11:00:00.000000Z"}"""
        )
        stub("GET", "account/export", "PK")
        stub("GET", "auth/me", """{"data":${Fixtures.ME}}""")
        stub("POST", "auth/logout", "{}")
        show { AccountScreen(AccountRoute.Suspended) }
        waitForTag("suspended.title")
        compose.onNodeWithText("Erkannt hat das ein automatisiertes Verfahren", substring = true).assertExists()
        shot("suspended-provisional")
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
            suspension("ban", statement = "\"Falsch.\"", statementAt = "\"2026-10-22T11:00:00.000000Z\"", excerpt = "null")
        )
        stub("PUT", "suspension/statement", """{"message":"x","errors":{"statement":["Höchstens 3000 Zeichen."]}}""", status = 422)
        show { AccountScreen(AccountRoute.Suspended) }
        waitForText("Dein Konto ist dauerhaft gesperrt")
        shot("suspended-ban")
        compose.onNodeWithTag("suspended.send").tap()
        waitForText("Höchstens 3000 Zeichen.")
    }

    @Test
    fun aTemporaryLockAndALiftedOne() {
        signIn()
        stub("GET", "suspension", suspension("temporary"))
        stub("PUT", "suspension/statement", """{"message":"Server Error"}""", status = 500)
        show { AccountScreen(AccountRoute.Suspended) }
        waitForText("Dein Konto ist vorübergehend gesperrt")
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
        compose.onNodeWithText("Weiter zu Oasis").tap()
        compose.waitUntil(5_000) { session.me?.isSuspended == false }
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
        stub("POST", "complaints/7", """{"message":"x","errors":{"reason":["Bitte begründe den Widerspruch."]}}""", status = 422)
        show { AccountScreen(AccountRoute.Complaint(7)) }
        waitForTag("complaint.reason")
        type("Warum hältst du die Entscheidung für falsch?", "Darum.")
        compose.onNodeWithTag("complaint.submit").tap()
        waitForText("Bitte begründe den Widerspruch.")
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
    fun votesAreCastAndEndedOnesShowTheirResult() {
        signIn()
        stub("GET", "votes", Fixtures.votes.replace("\"can_vote\": false", "\"can_vote\": true"))
        stub("POST", "votes/1", """{"message":"Danke für deine Stimme."}""")
        show { AccountScreen(AccountRoute.Votes) }
        waitForText("Wohin mit einem Überschuss?")
        shot("votes")
        compose.onNodeWithTag("vote.1.2").tap()
        compose.onNodeWithTag("vote.cast.1").tap()
        waitForRequest("POST", "votes/1")
        waitForText("Danke für deine Stimme.")
    }

    @Test
    fun votesForTrialMembersAndWithoutAnyVote() {
        signIn()
        stub("GET", "votes", Fixtures.votes.replace("\"voted\": false", "\"voted\": true"))
        show { AccountScreen(AccountRoute.Votes) }
        waitForText("Abstimmen können Mitglieder mit aktiver Mitgliedschaft", substring = true)
        waitForText("Du hast abgestimmt. Danke!")
    }

    @Test
    fun noVotesAtAll() {
        signIn()
        stub("GET", "votes", """{"data":{"can_vote":true,"open":[],"ended":[]}}""")
        show { AccountScreen(AccountRoute.Votes) }
        waitForText("Gerade läuft keine Abstimmung.")
        compose.onNodeWithText("Noch keine abgeschlossenen Abstimmungen.").assertExists()
        shot("votes-empty")
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
        navigator.open(WebRoute("https://get-oasis.com/agb"))
        assertEquals(listOf("https://get-oasis.com/agb"), web)
    }
}
