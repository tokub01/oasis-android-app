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
    fun `decodes the trial overview`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.trialOverview))
        val overview = repository.overview()
        assertEquals(MembershipOverview.State.Trial, overview.state)
        assertEquals(20, overview.trialDaysLeft)
        assertNull(overview.subscription)
        assertEquals("11.11.2026", MembershipFormat.day(overview.trialEndsAt))
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
    fun `trial banner counts days up and hides for members`() {
        val now = java.time.Instant.ofEpochSecond(1_800_000_000)
        assertEquals(20, MembershipFormat.trialBannerDaysLeft(now.plusSeconds((86_400 * 19.2).toLong()), null, now))
        assertEquals(1, MembershipFormat.trialBannerDaysLeft(now.plusSeconds(3600), null, now))
        assertNull(MembershipFormat.trialBannerDaysLeft(now.plusSeconds(86_400), "oase", now))
        assertNull(MembershipFormat.trialBannerDaysLeft(now.minusSeconds(1), null, now))
        assertEquals("1 Tag", MembershipFormat.days(1))
        assertEquals("2 Tage", MembershipFormat.days(2))
        assertEquals("1.000", MembershipFormat.count(1000))
    }
}
