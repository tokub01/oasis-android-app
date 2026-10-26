package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.billing.BillingPeriod
import de.velwebsolutions.oasis.membership.billing.PlayDelivery
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.billing.PlayPurchase
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseReceipt
import de.velwebsolutions.oasis.membership.ui.storeManagement
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
    fun `the receipt carries the purchase's first product, a purchase without one makes none`() {
        val ours = PlayPurchase("token", listOf(Catalogue.OASE_YEARLY), isPurchased = true, isAcknowledged = false)
        assertEquals(PlayPurchaseReceipt("token", Catalogue.OASE_YEARLY), ours.receipt())
        val empty = PlayPurchase("token", emptyList(), isPurchased = true, isAcknowledged = false)
        assertNull(empty.productId)
        assertNull(empty.receipt())
    }

    @Test
    fun `delivery sends paid purchases with a product and skips the rest`() = runTest {
        val repository = FakeMembershipRepository()
        val delivery = PlayDelivery(repository)
        val empty = PlayPurchase("token", emptyList(), isPurchased = true, isAcknowledged = false)
        assertEquals(PlayDelivery.Result.Skipped, delivery.deliver(empty))
        assertEquals(PlayDelivery.Result.Skipped, delivery.deliver(Fixtures.purchase(purchased = false)))
        assertTrue(delivery.deliver(Fixtures.purchase()) is PlayDelivery.Result.Unlocked)
        assertEquals(listOf(PlayPurchaseReceipt("token-${Catalogue.OASE_MONTHLY}", Catalogue.OASE_MONTHLY)), repository.receipts)
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

    @Test
    fun `a store subscription is managed in its store, everything else here`() {
        val none = de.velwebsolutions.oasis.membership.api.MembershipOverview()
        assertNull(none.storeManagement())
        val apple = de.velwebsolutions.oasis.membership.api.MembershipOverview.Apple(managedByApple = true, manageUrl = "a")
        val notApple = de.velwebsolutions.oasis.membership.api.MembershipOverview.Apple()
        val google = de.velwebsolutions.oasis.membership.api.MembershipOverview.Google(managedByGoogle = true, manageUrl = "g")
        val notGoogle = de.velwebsolutions.oasis.membership.api.MembershipOverview.Google(
            products = listOf(
                de.velwebsolutions.oasis.membership.api.MembershipOverview.Google.Product(Catalogue.OASE_MONTHLY, "oase", "monat")
            )
        )
        assertEquals("membership.manageApple", none.copy(apple = apple, google = google).storeManagement()?.tag)
        assertEquals("g", none.copy(apple = notApple, google = google).storeManagement()?.manageUrl)
        assertNull(none.copy(apple = notApple, google = notGoogle).storeManagement())
        assertNull(none.copy(apple = notApple).storeManagement())
        assertNull(none.copy(google = notGoogle).storeManagement())
        assertEquals("oase", notGoogle.products.single().tier)
    }
}
