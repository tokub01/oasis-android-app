package de.velwebsolutions.oasis.staff

import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.staff.api.AdminDashboard
import de.velwebsolutions.oasis.staff.api.AdminVote
import de.velwebsolutions.oasis.staff.api.BreachRegister
import de.velwebsolutions.oasis.staff.api.CaseSummary
import de.velwebsolutions.oasis.staff.api.ComplaintDetail
import de.velwebsolutions.oasis.staff.api.DataBreach
import de.velwebsolutions.oasis.staff.api.ModerationCaseDetail
import de.velwebsolutions.oasis.staff.api.ModerationOverview
import de.velwebsolutions.oasis.staff.api.PaymentPage
import de.velwebsolutions.oasis.staff.api.RolePage
import de.velwebsolutions.oasis.staff.api.SuspensionList
import de.velwebsolutions.oasis.staff.api.SystemStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every staff answer decodes as the server sends it. */
class StaffDecodingTest {
    private fun <T> decode(json: String, serializer: KSerializer<T>): T = OasisJson.snake.decodeFromString(serializer, json)

    private fun <T> data(json: String, serializer: KSerializer<T>): T = decode(json, DataEnvelope.serializer(serializer)).data

    @Test
    fun `moderation screens`() {
        val overview = data(Fixtures.overview, ModerationOverview.serializer())
        assertTrue(overview.figures.active >= 0)
        assertTrue(overview.navigation.canAdministrate)
        val page = decode(Fixtures.cases, Page.serializer(CaseSummary.serializer()))
        assertTrue(page.data.isNotEmpty())
        assertEquals(25, page.meta?.perPage)

        val plain = decode(Fixtures.caseDetail, Confirmed.serializer(ModerationCaseDetail.serializer())).data
        assertEquals("content_report", plain.report.kind)
        assertNull(plain.escalation)
        assertEquals(5, plain.outcomes.size)

        val escalated = data(Fixtures.caseEscalated, ModerationCaseDetail.serializer())
        assertEquals("child_abuse", escalated.escalation?.type?.value)
        assertEquals(true, escalated.escalation?.quarantined)
        assertEquals(2, escalated.escalation?.evidence?.size)
        assertEquals(5, escalated.escalation?.checklist?.size)

        val check = data(Fixtures.caseCheck, ModerationCaseDetail.serializer())
        assertEquals("content_check", check.report.kind)
        assertEquals(true, check.report.check?.scores?.first { it.key == "insult" }?.flagged)
        assertNotNull(check.report.check?.heldAt)

        val complaint = data(Fixtures.complaint, ComplaintDetail.serializer())
        assertTrue(complaint.canReview)
        assertNull(complaint.result)

        val roles = decode(Fixtures.roles, RolePage.serializer())
        assertEquals(listOf("member", "moderator", "admin"), roles.roles.map { it.value })
        assertNull(roles.page.nextCursor)

        val locks = data(Fixtures.suspensions, SuspensionList.serializer())
        assertEquals("Es tut mir leid.", locks.active.first().statement)
        assertEquals("ok", locks.active.first().deadlineState)
    }

    @Test
    fun `admin screens`() {
        val dashboard = data(Fixtures.dashboard, AdminDashboard.serializer())
        assertEquals(listOf(7, 30, 90, 365), dashboard.periods)
        assertEquals(12, dashboard.network.weeks.size)
        assertNull(dashboard.revenue.nextMonth)
        assertTrue(data(Fixtures.votes, ListSerializer(AdminVote.serializer())).isNotEmpty())
        val payments = decode(Fixtures.payments, PaymentPage.serializer())
        assertEquals(7, payments.filters.statuses.size)
        assertFalse(payments.data.isEmpty())
        val breaches = decode(Fixtures.breaches, BreachRegister.serializer())
        assertEquals(3, breaches.risks.size)
        assertEquals(true, breaches.data.first().canRecordNotified)
        val stored = decode(Fixtures.breachStore, Confirmed.serializer(DataBreach.serializer()))
        assertTrue(stored.message.orEmpty().startsWith("Erfasst."))
        assertEquals("database", data(Fixtures.system, SystemStatus.serializer()).checks.first().key)
    }
}
