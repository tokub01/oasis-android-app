package de.velwebsolutions.oasis.staff

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.api.durationLabel
import de.velwebsolutions.oasis.staff.ui.components.DeadlineLook
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.moderation.ComplaintReviewUiState
import de.velwebsolutions.oasis.staff.ui.moderation.reviewSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The staff pages' dates, deadline colours and the review summary. */
class StaffFormatsTest {
    @Test
    fun `missing dates show a dash and stored values without a date stay as they are`() {
        assertEquals("–", StaffDate.short(null))
        assertEquals("–", StaffDate.withUhr(null))
        assertEquals("–", StaffDate.medium(null))
        assertEquals("22.10.2026 15:49", StaffDate.short(Instant.parse("2026-10-22T13:49:00Z")))
        assertEquals("2026-10-22T15:49", StaffDate.localInput(Instant.parse("2026-10-22T13:49:00Z")))
    }

    /** No lock waits for a decision since 6 November 2026; the colours stay for a waiting-time state. */
    @Test
    fun `waiting-time states are coloured by their deadline`() {
        assertEquals(OasisColors.Sun.copy(alpha = 0.35f), DeadlineLook.background("overdue"))
        assertEquals(OasisColors.Sun.copy(alpha = 0.2f), DeadlineLook.background("soon"))
        assertEquals(OasisColors.Dune, DeadlineLook.background(null))
        assertEquals(OasisColors.Danger, DeadlineLook.foreground("overdue"))
        assertEquals(OasisColors.Night, DeadlineLook.foreground("ok"))
    }

    @Test
    fun `the review summary names the new measure, or nothing for unknown values`() {
        val complaint = Decoded.complaint
        val upheld = ComplaintReviewUiState(Loadable.Loaded(complaint), result = "upheld")
        assertEquals(
            "Die Entscheidung bleibt bestehen. Die Person, die widersprochen hat, bekommt die Begründung per E-Mail.",
            reviewSummary(upheld, complaint)
        )
        val overturned = upheld.copy(result = "overturned", statement = StatementOfReasons("no_action", "community_rules", "", "", ""))
        assertEquals(
            "Die Entscheidung wird geändert: Keine Maßnahme · Community-Regeln. Die Betroffenen bekommen die Begründung per E-Mail.",
            reviewSummary(overturned, complaint)
        )
        val unknown = overturned.copy(statement = StatementOfReasons("x", "y", "", "", ""))
        assertEquals(
            "Die Entscheidung wird geändert:  · . Die Betroffenen bekommen die Begründung per E-Mail.",
            reviewSummary(unknown, complaint)
        )
        val lock = overturned.copy(statement = StatementOfReasons("account_suspended", "community_rules", suspensionDays = 3))
        assertEquals(
            "Die Entscheidung wird geändert: Konto gesperrt (3 Tage) · Community-Regeln. Die Betroffenen bekommen die Begründung per E-Mail.",
            reviewSummary(lock, complaint)
        )
    }

    @Test
    fun `the duration of a lock belongs to "Konto gesperrt" only`() {
        assertEquals("1 Tag", durationLabel(1))
        assertEquals("7 Tage", durationLabel(7))
        val outcomes = Decoded.complaint.outcomes
        val lock = StatementOfReasons("account_suspended", "community_rules")
        assertTrue(lock.isSuspension)
        assertTrue("the server refuses the lock without its duration", lock.lacksDuration)
        assertEquals("Konto gesperrt", lock.outcomeLabel(outcomes))
        assertFalse(lock.copy(suspensionDays = 30).lacksDuration)
        assertEquals("Konto gesperrt (30 Tage)", lock.copy(suspensionDays = 30).outcomeLabel(outcomes))
        val warning = StatementOfReasons("warning", "community_rules", suspensionDays = 30)
        assertFalse(warning.isSuspension)
        assertFalse(warning.lacksDuration)
        assertEquals("a stray duration is not shown for another measure", "Warnung", warning.outcomeLabel(outcomes))
    }

    @Test
    fun `staff links open Mildcrowd pages in the app and others in the browser`() {
        assertEquals(
            de.velwebsolutions.oasis.design.navigation.MembershipRoute.Overview,
            de.velwebsolutions.oasis.staff.ui.components.linkRoute("http://localhost/app/mitgliedschaft")
        )
        assertEquals(
            de.velwebsolutions.oasis.design.navigation.WebRoute("https://example.org/"),
            de.velwebsolutions.oasis.staff.ui.components.linkRoute("https://example.org/")
        )
    }
}
