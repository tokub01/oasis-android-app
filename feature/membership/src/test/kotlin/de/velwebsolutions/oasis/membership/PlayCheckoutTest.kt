package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.membership.billing.BillingPeriod
import de.velwebsolutions.oasis.membership.billing.PlayDelivery
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseVerifier
import de.velwebsolutions.oasis.membership.billing.PurchaseOutcome
import de.velwebsolutions.oasis.membership.billing.ServerPendingPlayPurchaseVerifier
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.ui.AlreadyMember
import de.velwebsolutions.oasis.membership.ui.PlayCheckoutViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport.Stub
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayCheckoutTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val events = MembershipEvents()

    private suspend fun checkout(
        gateway: FakeBillingGateway = FakeBillingGateway(),
        verifier: PlayPurchaseVerifier = FakeVerifier(),
        overview: String = Fixtures.trialOverview
    ): PlayCheckoutViewModel {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = overview))
        return PlayCheckoutViewModel(repository, gateway, PlayDelivery(gateway, verifier), verifier, events, Fixtures.session()).also {
            it.load()
        }
    }

    @Test
    fun `offers the three tiers in both rhythms with Play's prices`() = runTest {
        val viewModel = checkout()
        val offers = viewModel.state.value.offers.valueOrNull!!
        assertEquals(listOf("solidar", "oase", "maezen"), offers.map { it.tier })
        assertEquals(listOf("3,00 €", "5,00 €", "12,00 €"), offers.map { it.monthly?.price })
        assertEquals(listOf("30,00 €", "50,00 €", "120,00 €"), offers.map { it.yearly?.price })
        viewModel.selectRhythm(PlayProducts.YEARLY)
        assertEquals(BillingPeriod.Year, offers[1].product(viewModel.state.value.rhythm)?.period)
        assertTrue(viewModel.state.value.canPurchase)
    }

    @Test
    fun `a purchase is unlocked by the server and only then acknowledged`() = runTest {
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Purchased(listOf(Fixtures.purchase())))
        val verifier = FakeVerifier()
        val viewModel = checkout(gateway, verifier)
        val oase = viewModel.state.value.offers.valueOrNull!![1].monthly!!

        assertTrue(viewModel.purchase(null, oase))

        assertEquals(listOf(PlayProducts.OASE_MONTHLY to "3f2b7c1e-8a4d-5b6e-9c0f-1a2b3c4d5e6f"), gateway.purchased)
        assertEquals(PlayProducts.OASE_MONTHLY, verifier.receipts.single().productId)
        assertEquals("token-${PlayProducts.OASE_MONTHLY}", verifier.receipts.single().purchaseToken)
        assertEquals(listOf("token-${PlayProducts.OASE_MONTHLY}"), gateway.acknowledged)
        assertEquals("Willkommen als Mitglied!", viewModel.state.value.message)
        assertEquals("Willkommen als Mitglied!", events.take().message)
        assertNull(viewModel.state.value.purchasing)
    }

    @Test
    fun `a refused purchase is an error and not acknowledged`() = runTest {
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Purchased(listOf(Fixtures.purchase())))
        val viewModel = checkout(gateway, FakeVerifier.refusing("Dieser Kauf gehört zu einem anderen Konto."))
        assertFalse(viewModel.purchase(null, Fixtures.product(PlayProducts.OASE_MONTHLY, "5,00 €")))
        assertEquals("Dieser Kauf gehört zu einem anderen Konto.", viewModel.state.value.error)
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun `pending, cancelled and failed purchases unlock nothing`() = runTest {
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Pending)
        val viewModel = checkout(gateway)
        val product = Fixtures.product(PlayProducts.SOLIDAR_MONTHLY, "3,00 €")
        assertFalse(viewModel.purchase(null, product))
        assertEquals(PlayCheckoutViewModel.PENDING, viewModel.state.value.message)

        viewModel.dismissMessage()
        gateway.outcome = PurchaseOutcome.Cancelled
        assertFalse(viewModel.purchase(null, product))
        assertNull(viewModel.state.value.message)
        assertNull(viewModel.state.value.error)

        gateway.outcome = PurchaseOutcome.Failed
        assertFalse(viewModel.purchase(null, product))
        assertEquals(PlayCheckoutViewModel.FAILED, viewModel.state.value.error)
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun `without the server check the prices show but nothing can be bought`() = runTest {
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Purchased(listOf(Fixtures.purchase())))
        val viewModel = checkout(gateway, ServerPendingPlayPurchaseVerifier())
        assertFalse(viewModel.state.value.canPurchase)
        assertEquals(3, viewModel.state.value.offers.valueOrNull?.size)
        assertFalse(viewModel.purchase(null, Fixtures.product(PlayProducts.OASE_MONTHLY, "5,00 €")))
        assertTrue("Play's sheet is not even opened", gateway.purchased.isEmpty())
    }

    @Test
    fun `a paid purchase without the server check is reported honestly`() = runTest {
        val gateway = FakeBillingGateway(owned = listOf(Fixtures.purchase()))
        val viewModel = checkout(gateway, ServerPendingPlayPurchaseVerifier())
        viewModel.restore()
        assertEquals(PlayCheckoutViewModel.AWAITING_SERVER, viewModel.state.value.message)
        assertTrue(gateway.acknowledged.isEmpty())
        assertEquals(0, events.version.value)
    }

    @Test
    fun `restore without purchases says so`() = runTest {
        val viewModel = checkout()
        viewModel.restore()
        assertEquals(PlayCheckoutViewModel.NOTHING_TO_RESTORE, viewModel.state.value.message)
    }

    @Test
    fun `members of the website and devices without Play are offered nothing`() = runTest {
        val member = checkout(overview = Fixtures.subscriptionOverview)
        assertEquals(AlreadyMember.Web, member.state.value.alreadyMember)
        assertEquals(emptyList<Any>(), member.state.value.offers.valueOrNull)

        val withoutPlay = checkout(FakeBillingGateway(catalogue = null))
        assertNull(withoutPlay.state.value.alreadyMember)
        assertEquals(emptyList<Any>(), withoutPlay.state.value.offers.valueOrNull)
    }

    @Test
    fun `background purchases are delivered and only acknowledged after the server`() = runTest {
        val unacknowledged = Fixtures.purchase()
        val gateway = FakeBillingGateway(owned = listOf(unacknowledged, Fixtures.purchase(PlayProducts.MAEZEN_YEARLY, acknowledged = true)))
        val verifier = FakeVerifier()
        val transactions = PlayTransactions(gateway, PlayDelivery(gateway, verifier), events)
        val job = launch { transactions.observe() }
        testScheduler.runCurrent()
        assertEquals(listOf(unacknowledged.purchaseToken), gateway.acknowledged)
        assertEquals(1, events.version.value)

        gateway.updates.emit(Fixtures.purchase(PlayProducts.SOLIDAR_YEARLY, purchased = false))
        testScheduler.runCurrent()
        assertEquals("a pending payment is not delivered", 1, verifier.receipts.size)
        job.cancel()

        val waiting = FakeBillingGateway(owned = listOf(Fixtures.purchase()))
        val pending = launch { PlayTransactions(waiting, PlayDelivery(waiting, ServerPendingPlayPurchaseVerifier()), events).observe() }
        testScheduler.runCurrent()
        assertTrue("nothing is acknowledged while the server cannot check it", waiting.acknowledged.isEmpty())
        pending.cancel()
    }
}
