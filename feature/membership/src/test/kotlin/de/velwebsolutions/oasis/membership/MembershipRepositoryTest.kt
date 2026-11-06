package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseReceipt
import de.velwebsolutions.oasis.membership.data.TermsObjectionRefused
import de.velwebsolutions.oasis.testing.StubTransport.Stub
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MembershipRepositoryTest {
    @Test
    fun `decodes the overview of a member without membership`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.noMembershipOverview))
        val overview = repository.overview()
        assertEquals(MembershipOverview.State.Expired, overview.state)
        assertNull(overview.subscription)
        assertEquals(false, overview.apple?.managedByApple)
        val google = overview.google!!
        assertEquals(true, google.available)
        assertEquals(Fixtures.ACCOUNT_ID, google.accountId)
        assertEquals(Catalogue.ids, google.products.map { it.id })
        assertEquals("oase", google.products[2].tier)
        assertEquals(false, google.managedByGoogle)
        assertEquals("https://play.google.com/store/account/subscriptions", google.manageUrl)
    }

    @Test
    fun `tolerates servers without the apple and google blocks`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.subscriptionOverview))
        assertNull(repository.overview().apple)
        assertNull(repository.overview().google)
    }

    @Test
    fun `a Google Play purchase is posted with its token and product`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "membership/google-purchases", json = Fixtures.confirmed(Fixtures.WELCOME))
        )
        val answer = repository.googlePurchase(PlayPurchaseReceipt("token", Catalogue.OASE_MONTHLY))
        assertEquals(Fixtures.WELCOME, answer.message)
        assertEquals(MembershipOverview.State.Subscription, answer.data.state)
        assertEquals(
            """{"purchase_token":"token","product_id":"${Catalogue.OASE_MONTHLY}"}""",
            transport.lastBody("membership/google-purchases").toString()
        )
    }

    @Test
    fun `decodes a running subscription`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.subscriptionOverview))
        val overview = repository.overview()
        assertEquals(MembershipOverview.State.Subscription, overview.state)
        assertEquals("Oase", overview.subscription?.tierName)
        assertEquals(false, overview.subscription?.onGracePeriod)
        assertTrue(overview.canCancel && overview.canWithdraw && overview.isFoundingMember)
    }

    @Test
    fun `decodes payments and cancellation`() = runTest {
        val (repository, _) = Fixtures.repository(
            Stub("GET", "membership/payments", json = Fixtures.payments),
            Stub("GET", "membership/cancellation", json = Fixtures.cancellation)
        )
        val payment = repository.payments().first()
        assertEquals("OAS-2", payment.invoiceNumber)
        assertEquals("5 €", payment.amountLabel)
        assertEquals("19.10.2026", MembershipFormat.day(payment.paidAt))
        val details = repository.cancellation()
        assertEquals("Mia Mitglied", details.name)
        assertEquals("18.11.2026", MembershipFormat.day(details.effectiveDate))
    }

    @Test
    fun `maps the unavailable cancellation to a conflict`() = runTest {
        val message = "Du hast derzeit kein aktives Abonnement, das gekündigt werden kann."
        val (repository, _) = Fixtures.repository(Stub("GET", "membership/cancellation", status = 409, json = """{"message":"$message"}"""))
        try {
            repository.cancellation()
            fail("expected a conflict")
        } catch (error: ApiError) {
            assertEquals(ApiError.Conflict(message), error)
        }
    }

    @Test
    fun `cancelling reads the confirmation and the new membership`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "membership/cancellation", json = Fixtures.confirmed(Fixtures.CANCELLED))
        )
        val result = repository.cancel()
        assertEquals(Fixtures.CANCELLED, result.message)
        assertEquals(MembershipOverview.State.Subscription, result.data.state)
        assertEquals("POST", transport.requests.last().request.method)
    }

    @Test
    fun `withdrawing reads the withdrawal page and posts the withdrawal`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "membership/withdrawal", json = Fixtures.WITHDRAWAL),
            Stub("POST", "membership/withdrawal", json = Fixtures.confirmed("Widerrufen."))
        )
        assertEquals("Oase", repository.withdrawal().tierName)
        assertEquals("Widerrufen.", repository.withdraw().message)
        assertEquals("membership/withdrawal", transport.requests.last().path)
    }

    @Test
    fun `downloads the invoice and the export as files`() = runTest {
        val html = "<!doctype html><title>Rechnung OAS-2</title>"
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "membership/invoices/2", json = html),
            Stub("GET", "account/export", json = "PK")
        )
        assertEquals(html, repository.invoice(2).decodeToString())
        assertEquals("PK", repository.export().decodeToString())
        assertEquals("/api/v1/account/export", transport.requests.last().request.url.encodedPath)
        assertFalse(transport.requests.any { it.request.header("Authorization") == null })
    }
}

class MembershipFormatTest {
    @Test
    fun `days as the web writes them`() {
        assertEquals("11.11.2026", MembershipFormat.day(Instant.parse("2026-11-11T13:32:15Z")))
        assertEquals("", MembershipFormat.day(null))
        assertEquals("21.12.2026", MembershipFormat.calendarDay(LocalDate.of(2026, 12, 21)))
        assertEquals("", MembershipFormat.calendarDay(null))
    }

    @Test
    fun `moments as the confirmations name them`() {
        assertEquals("06.11.2026 um 14:32 Uhr", MembershipFormat.moment(Instant.parse("2026-11-06T13:32:15Z")))
        assertEquals("summer time", "21.06.2026 um 15:32 Uhr", MembershipFormat.moment(Instant.parse("2026-06-21T13:32:15Z")))
        assertEquals("", MembershipFormat.moment(null))
    }
}

class TermsObjectionRepositoryTest {
    private val alreadyObjected = """{"message":"${Fixtures.ALREADY_OBJECTED}","reason":"already_objected"}"""

    @Test
    fun `decodes an announced change of the AGB and tolerates servers without it`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.termsChangeOverview))
        val change = repository.overview().termsChange!!
        assertEquals(LocalDate.of(2026, 12, 21), change.effectiveOn)
        assertEquals("Wir fassen § 14 neu.", change.summary)
        assertEquals("Das Gesetz verlangt es.", change.reason)
        assertEquals(Instant.parse("2026-11-01T09:00:00Z"), change.announcedAt)
        assertNull(change.objectedAt)
        assertFalse(change.objected)
        assertEquals("http://localhost/agb", change.url)

        val objected = Fixtures.overview(Fixtures.termsObjectedOverview).termsChange!!
        assertTrue(objected.objected)
        assertEquals("06.11.2026 um 14:32 Uhr", MembershipFormat.moment(objected.objectedAt))

        assertNull("servers before announcements", Fixtures.overview(Fixtures.subscriptionOverview).termsChange)
        val bare = Fixtures.subscriptionOverview.replaceFirst("""{"data":{""", """{"data":{"terms_change":{},""")
        assertEquals("an announcement without its fields", MembershipOverview.TermsChange(), Fixtures.overview(bare).termsChange)
    }

    @Test
    fun `objecting posts and taking the objection back deletes, each with the server's word`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "membership/terms-objection", json = Fixtures.confirmed(Fixtures.OBJECTED, Fixtures.termsObjectedOverview)),
            Stub(
                "DELETE",
                "membership/terms-objection",
                json = Fixtures.confirmed(Fixtures.OBJECTION_WITHDRAWN, Fixtures.termsChangeOverview)
            )
        )
        val objected = repository.objectToTerms()
        assertEquals(Fixtures.OBJECTED, objected.message)
        assertTrue(objected.data.termsChange!!.objected)
        assertEquals("POST", transport.requests.last().request.method)
        assertEquals("membership/terms-objection", transport.requests.last().path)

        val withdrawn = repository.withdrawTermsObjection()
        assertEquals(Fixtures.OBJECTION_WITHDRAWN, withdrawn.message)
        assertFalse(withdrawn.data.termsChange!!.objected)
        assertEquals("DELETE", transport.requests.last().request.method)
        assertEquals("membership/terms-objection", transport.requests.last().path)
    }

    @Test
    fun `a refused objection names the server's reason`() = runTest {
        val notObjected = "Du hast dieser Änderung nicht widersprochen, es gibt also nichts zurückzunehmen."
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "membership/terms-objection", status = 422, json = alreadyObjected),
            Stub("DELETE", "membership/terms-objection", status = 422, json = """{"message":"$notObjected","reason":"not_objected"}""")
        )
        val objected = assertRefused { repository.objectToTerms() }
        assertEquals(TermsObjectionRefused.ALREADY_OBJECTED, objected.reason)
        assertEquals(Fixtures.ALREADY_OBJECTED, objected.message)
        val withdrawn = assertRefused { repository.withdrawTermsObjection() }
        assertEquals(TermsObjectionRefused.NOT_OBJECTED, withdrawn.reason)
        assertEquals(notObjected, withdrawn.message)

        val gone = "Derzeit ist keine Änderung unserer AGB angekündigt, der du widersprechen könntest."
        transport.add("POST", "membership/terms-objection", status = 404, json = """{"message":"$gone","reason":"no_announcement"}""")
        val noAnnouncement = assertRefused { repository.objectToTerms() }
        assertEquals(TermsObjectionRefused.NO_ANNOUNCEMENT, noAnnouncement.reason)
        assertEquals(gone, noAnnouncement.message)

        transport.add("POST", "membership/terms-objection", status = 422, json = """{"message":"Ungültig."}""")
        assertNull("a 422 without a code", assertRefused { repository.objectToTerms() }.reason)

        transport.add("POST", "membership/terms-objection", status = 503, json = """{"message":"Später."}""")
        val failure = runCatching { repository.objectToTerms() }.exceptionOrNull()
        assertEquals("other failures stay what they are", ApiError.Server(503, "Später."), failure)
    }

    private suspend fun assertRefused(block: suspend () -> Unit): TermsObjectionRefused {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is TermsObjectionRefused)
        return failure as TermsObjectionRefused
    }
}
