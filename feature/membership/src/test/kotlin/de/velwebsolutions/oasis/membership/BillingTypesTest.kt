package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.billing.BillingPeriod
import de.velwebsolutions.oasis.membership.billing.PlayDelivery
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.billing.PlayPurchase
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseReceipt
import de.velwebsolutions.oasis.membership.billing.PlayVerificationUnavailable
import de.velwebsolutions.oasis.membership.billing.ServerPendingPlayPurchaseVerifier
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small pieces of the Play checkout and the membership's formatting. */
class BillingTypesTest {
    @Test
    fun `renewal periods are read from Play's ISO 8601 durations`() {
        assertEquals(BillingPeriod.Year, BillingPeriod.parse("P1Y"))
        assertEquals(BillingPeriod.Month, BillingPeriod.parse("P1M"))
        assertEquals(BillingPeriod.Week, BillingPeriod.parse("P1W"))
        assertEquals(BillingPeriod.Unknown, BillingPeriod.parse("P3D"))
        assertEquals(BillingPeriod.Unknown, BillingPeriod.parse(null))
        assertEquals("/ Monat", BillingPeriod.Month.per)
    }

    @Test
    fun `only our products make a receipt for the server`() {
        val ours = PlayPurchase("token", listOf("other.app.coins", PlayProducts.OASE_YEARLY), isPurchased = true, isAcknowledged = false)
        assertEquals(PlayPurchaseReceipt("token", PlayProducts.OASE_YEARLY), ours.receipt())
        val foreign = PlayPurchase("token", listOf("other.app.coins"), isPurchased = true, isAcknowledged = false)
        assertNull(foreign.productId)
        assertNull(foreign.receipt())
        assertNull(PlayProducts.entry("other.app.coins"))
    }

    @Test
    fun `delivery skips foreign purchases and acknowledges only once`() = runTest {
        val gateway = FakeBillingGateway()
        val delivery = PlayDelivery(gateway, FakeVerifier())
        val foreign = PlayPurchase("token", listOf("other.app.coins"), isPurchased = true, isAcknowledged = false)
        assertEquals(PlayDelivery.Result.Skipped, delivery.deliver(foreign))
        assertTrue(delivery.deliver(Fixtures.purchase(acknowledged = true)) is PlayDelivery.Result.Unlocked)
        assertTrue("an acknowledged purchase is not acknowledged again", gateway.acknowledged.isEmpty())
    }

    @Test
    fun `the pending verifier refuses every purchase`() = runTest {
        val verifier = ServerPendingPlayPurchaseVerifier()
        assertFalse(verifier.isAvailable)
        val refusal = runCatching { verifier.verify(PlayPurchaseReceipt("token", PlayProducts.OASE_MONTHLY)) }.exceptionOrNull()
        assertTrue(refusal is PlayVerificationUnavailable)
    }

    @Test
    fun `dates may be missing`() {
        assertEquals("", MembershipFormat.day(null))
    }

    @Test
    fun `an overview without data is read-only mode, a subscription without dates runs`() {
        val empty = de.velwebsolutions.oasis.membership.api.MembershipOverview()
        assertEquals(de.velwebsolutions.oasis.membership.api.MembershipOverview.State.Expired, empty.state)
        assertNull(empty.apple)
        val running = de.velwebsolutions.oasis.membership.api.MembershipOverview.Subscription("Oase")
        assertFalse(running.onGracePeriod)
        assertNull(running.endsAt)
    }
}
