package de.velwebsolutions.oasis.staff

import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.staff.Fixtures.lastObject
import de.velwebsolutions.oasis.staff.Fixtures.lastQuery
import de.velwebsolutions.oasis.staff.api.ComplaintReview
import de.velwebsolutions.oasis.staff.api.NewBreach
import de.velwebsolutions.oasis.staff.api.NewVote
import de.velwebsolutions.oasis.staff.api.QueueFilter
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.testing.StubTransport.Stub
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every staff call goes to its route with the body and query the Laravel API expects. */
class StaffRepositoryCallsTest {
    /** `{"message": …, "data": <first element of the fixture's data list>}`. */
    private fun confirmedFirst(fixture: String, message: String = "OK."): String {
        val first = OasisJson.verbatim.parseToJsonElement(fixture).jsonObject.getValue("data").jsonArray.first()
        return """{"message":"$message","data":$first}"""
    }

    private val mollie = """{"data":{"mollie":{"kind":"payment","payment":{"id":"tr_1","status":"paid"}},"error":null}}"""
    private val statement = StatementOfReasons("content_removed", "community_rules", "Regel 2", "Tatsachen", "Begründung")

    private fun field(body: JsonObject?, name: String) = (body?.get(name) as? JsonPrimitive)?.content

    @Test
    fun `case actions post their bodies`() = runTest {
        val confirmed = Fixtures.confirmed(Fixtures.caseDetail, "Gespeichert.")
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "staff/moderation/cases", json = Fixtures.cases),
            Stub("POST", "staff/moderation/cases/2/assign", json = confirmed),
            Stub("POST", "staff/moderation/cases/2/decision", json = confirmed),
            Stub("POST", "staff/moderation/cases/2/escalate", json = confirmed),
            Stub("POST", "staff/moderation/cases/2/authority-report", json = confirmed),
            Stub("POST", "staff/moderation/cases/2/evidence/extend", json = confirmed),
            Stub("DELETE", "staff/moderation/cases/2/evidence", json = confirmed),
            Stub("DELETE", "staff/moderation/cases/2/quarantine", json = confirmed)
        )
        repository.cases(QueueFilter(status = "open", search = "  "))
        assertEquals(mapOf("status" to "open", "prioritaet" to "all", "page" to "1"), transport.lastQuery())
        repository.cases(QueueFilter(mine = true, search = "drop"), page = 2)
        assertEquals("1", transport.lastQuery()["meine"])
        assertEquals("drop", transport.lastQuery()["suche"])

        assertEquals("Gespeichert.", repository.assign(2, 7).message)
        assertEquals("7", field(transport.lastObject("staff/moderation/cases/2/assign"), "assigned_to"))
        repository.decide(2, statement)
        assertEquals("Regel 2", field(transport.lastObject("staff/moderation/cases/2/decision"), "rule_reference"))
        repository.escalate(2, "terror")
        assertEquals("terror", field(transport.lastObject("staff/moderation/cases/2/escalate"), "type"))
        repository.recordAuthorityReport(2, "LKA 1/26")
        assertEquals("LKA 1/26", field(transport.lastObject("staff/moderation/cases/2/authority-report"), "reference"))
        repository.extendEvidence(2)
        repository.deleteEvidence(2)
        repository.liftQuarantine(2)
        assertEquals(listOf("POST", "DELETE", "DELETE"), transport.requests.takeLast(3).map { it.request.method })
    }

    @Test
    fun `complaints, roles and locks`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "staff/moderation/complaints/1/review", json = Fixtures.confirmed(Fixtures.complaint, "Geprüft.")),
            Stub("PUT", "staff/moderation/roles/3", json = confirmedFirst(Fixtures.roles, "Rolle geändert.")),
            Stub("POST", "staff/moderation/suspensions/1/release", json = """{"message":"Freigegeben."}"""),
            Stub("POST", "staff/moderation/roles/3/email-confirmation", json = confirmedFirst(Fixtures.roles, "Bestätigt.")),
            Stub("POST", "staff/moderation/roles/3/password-link", json = confirmedFirst(Fixtures.roles, "Link gesendet.")),
            Stub("PUT", "staff/moderation/roles/3/password", json = confirmedFirst(Fixtures.roles, "Gesetzt."))
        )
        assertEquals("Geprüft.", repository.review(1, "upheld", "Bleibt.", statement).message)
        assertEquals("Bleibt.", field(transport.lastObject("staff/moderation/complaints/1/review"), "explanation"))
        assertEquals("Rolle geändert.", repository.changeRole(3, "moderator").message)
        assertEquals("moderator", field(transport.lastObject("staff/moderation/roles/3"), "role"))
        assertEquals("Freigegeben.", repository.release(1))
        assertEquals("Bestätigt.", repository.confirmEmail(3).message)
        assertEquals(true, repository.confirmEmail(3).data.emailVerified)
        assertEquals("Link gesendet.", repository.sendPasswordLink(3).message)
        assertEquals("Gesetzt.", repository.setPassword(3, "geheim-123").message)
        assertEquals("geheim-123", field(transport.lastObject("staff/moderation/roles/3/password"), "password"))
    }

    @Test
    fun `a complaint review sends only the chosen branch`() {
        assertEquals(ComplaintReview("upheld", explanation = "Bleibt."), ComplaintReview.of("upheld", "Bleibt.", statement))
        val overturned = ComplaintReview.of("overturned", "ignored", statement)
        assertEquals("Begründung", overturned.explanation)
        assertEquals("content_removed", overturned.outcome)
        assertEquals("Tatsachen", overturned.facts)
        assertEquals(ComplaintReview(""), ComplaintReview.of("", "ignored", statement))
    }

    @Test
    fun `votes, payments and Mollie`() = runTest {
        val payment = confirmedFirst(Fixtures.payments, "Abgeglichen.")
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "staff/admin/votes", json = Fixtures.votes),
            Stub("POST", "staff/admin/votes", json = confirmedFirst(Fixtures.votes, "Angelegt.")),
            Stub("GET", "staff/admin/payments", json = Fixtures.payments),
            Stub("POST", "staff/admin/payments/2/check-payment", json = mollie),
            Stub("POST", "staff/admin/payments/2/check-customer", json = mollie),
            Stub("POST", "staff/admin/payments/mollie-search", json = mollie),
            Stub("POST", "staff/admin/payments/2/sync", json = payment),
            Stub("POST", "staff/admin/payments/2/refund", json = payment)
        )
        repository.votes()
        assertEquals(
            "Angelegt.",
            repository.createVote(NewVote("Frage?", "", "2026-11-01T10:00", "2026-11-08T10:00", listOf("Ja", "Nein"))).message
        )
        repository.payments("", "", 1)
        assertNull(transport.lastQuery()["suche"])
        repository.payments("#12", "paid", 2)
        assertEquals(mapOf("suche" to "#12", "status" to "paid", "page" to "2"), transport.lastQuery())
        assertEquals("tr_1", repository.checkPayment(2).mollie?.payment?.id)
        assertEquals("paid", repository.checkCustomer(2).mollie?.payment?.status)
        repository.searchMollie("tr_1")
        assertEquals("tr_1", field(transport.lastObject("staff/admin/payments/mollie-search"), "q"))
        assertEquals("Abgeglichen.", repository.syncPayment(2).message)
        repository.refund(2, "5,00", "Doppelt bezahlt")
        assertEquals("5,00", field(transport.lastObject("staff/admin/payments/2/refund"), "amount"))
        assertEquals("Doppelt bezahlt", field(transport.lastObject("staff/admin/payments/2/refund"), "reason"))
    }

    @Test
    fun `breach steps and the system status`() = runTest {
        val stored = Fixtures.breachStore
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "staff/admin/breaches", json = stored),
            Stub("POST", "staff/admin/breaches/1/authority-report", json = stored),
            Stub("POST", "staff/admin/breaches/1/people-notified", json = stored),
            Stub("POST", "staff/admin/breaches/1/notes", json = stored),
            Stub("POST", "staff/admin/breaches/1/close", json = stored),
            Stub("GET", "staff/admin/system", json = Fixtures.system),
            Stub("GET", "staff/admin/dashboard", json = Fixtures.dashboard)
        )
        repository.recordBreach(NewBreach(title = "Panne"))
        repository.recordBreachReport(1, "BfDI 1/26")
        assertEquals("BfDI 1/26", field(transport.lastObject("staff/admin/breaches/1/authority-report"), "reference"))
        repository.recordPeopleNotified(1)
        repository.addBreachNote(1, "Nachtrag")
        assertEquals("Nachtrag", field(transport.lastObject("staff/admin/breaches/1/notes"), "note"))
        repository.closeBreach(1)
        assertEquals("database", repository.system().checks.first().key)
        repository.dashboard(90, "2026-09")
        assertEquals(mapOf("tage" to "90", "monat" to "2026-09"), transport.lastQuery())
    }
}
