package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.testing.StubTransport.Stub
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
        assertEquals(true, overview.apple?.available)
        assertEquals("3f2b7c1e-8a4d-5b6e-9c0f-1a2b3c4d5e6f", overview.apple?.accountToken)
        assertEquals(false, overview.apple?.managedByApple)
    }

    @Test
    fun `tolerates servers without the apple block`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.subscriptionOverview))
        assertNull(repository.overview().apple)
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
        assertEquals("11.11.2026", MembershipFormat.day(java.time.Instant.parse("2026-11-11T13:32:15Z")))
    }
}
