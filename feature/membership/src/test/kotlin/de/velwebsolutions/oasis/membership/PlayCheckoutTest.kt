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
import kotlinx.coroutines.async
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
        overview: String = Fixtures.noMembershipOverview
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

    @Test
    fun `members billed by Apple and servers without in-app purchases are offered nothing`() = runTest {
        val apple = Fixtures.noMembershipOverview.replace("\"state\":\"expired\"", "\"state\":\"subscription\"")
            .replace("\"managed_by_apple\":false", "\"managed_by_apple\":true")
        assertEquals(AlreadyMember.Apple, checkout(overview = apple).state.value.alreadyMember)
        val web = Fixtures.noMembershipOverview.replace("\"state\":\"expired\"", "\"state\":\"subscription\"")
        assertEquals(AlreadyMember.Web, checkout(overview = web).state.value.alreadyMember)

        val switchedOff = checkout(overview = Fixtures.noMembershipOverview.replace("\"available\":true", "\"available\":false"))
        assertEquals(emptyList<Any>(), switchedOff.state.value.offers.valueOrNull)
    }

    @Test
    fun `a failed overview leaves the checkout on its error page`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", status = 500, json = """{"message":"Server Error"}"""))
        val gateway = FakeBillingGateway()
        val verifier = FakeVerifier()
        val viewModel = PlayCheckoutViewModel(repository, gateway, PlayDelivery(gateway, verifier), verifier, events, Fixtures.session())
        viewModel.load()
        assertTrue(viewModel.state.value.offers is de.velwebsolutions.oasis.core.model.Loadable.Failed)
    }

    @Test
    fun `a tier Play only sells yearly still shows`() = runTest {
        val gateway = FakeBillingGateway(catalogue = listOf(Fixtures.product(PlayProducts.MAEZEN_YEARLY, "120,00 €")))
        val offers = checkout(gateway).state.value.offers.valueOrNull!!
        assertEquals(listOf("maezen"), offers.map { it.tier })
        assertNull(offers.single().product(PlayProducts.MONTHLY))
    }

    @Test
    fun `Play missing on the device, a second tap while buying and dismissing the toasts`() = runTest {
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Unavailable)
        val viewModel = checkout(gateway)
        val product = Fixtures.product(PlayProducts.OASE_MONTHLY, "5,00 €")
        assertFalse(viewModel.purchase(null, product))
        assertEquals(PlayCheckoutViewModel.UNAVAILABLE, viewModel.state.value.error)
        viewModel.dismissError()
        assertNull(viewModel.state.value.error)

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.gate = gate
        gateway.outcome = PurchaseOutcome.Cancelled
        val first = async { viewModel.purchase(null, product) }
        testScheduler.runCurrent()
        assertEquals(product.id, viewModel.state.value.purchasing)
        assertFalse("a second purchase does not start", viewModel.purchase(null, product))
        gate.complete(Unit)
        assertFalse(first.await())
        assertEquals("Play opened once per accepted tap", 2, gateway.purchased.size)
    }

    @Test
    fun `restoring a refused or still pending purchase says so instead of "nothing found"`() = runTest {
        val refused = checkout(FakeBillingGateway(owned = listOf(Fixtures.purchase())), FakeVerifier.refusing("Abgelaufen."))
        refused.restore()
        assertEquals("Abgelaufen.", refused.state.value.error)
        assertNull(refused.state.value.message)

        val pending = checkout(FakeBillingGateway(owned = listOf(Fixtures.purchase(purchased = false))))
        pending.restore()
        assertEquals(PlayCheckoutViewModel.NOTHING_TO_RESTORE, pending.state.value.message)
        pending.dismissMessage()
        assertNull(pending.state.value.message)

        val restored = checkout(FakeBillingGateway(owned = listOf(Fixtures.purchase())))
        restored.restore()
        assertEquals("Willkommen als Mitglied!", restored.state.value.message)
    }

    @Test
    fun `a cancelled load is passed on`() = runTest {
        val fake = FakeMembershipRepository().apply { overview = Result.failure(kotlinx.coroutines.CancellationException("left")) }
        val cancelling =
            PlayCheckoutViewModel(
                fake,
                FakeBillingGateway(),
                PlayDelivery(FakeBillingGateway(), FakeVerifier()),
                FakeVerifier(),
                events,
                Fixtures.session()
            )
        val failure = runCatching { cancelling.load() }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `background delivery ends with Play's updates and skips refusals`() = runTest {
        val gateway = object : de.velwebsolutions.oasis.membership.billing.BillingGateway by FakeBillingGateway(
            owned = listOf(Fixtures.purchase())
        ) {
            override val purchaseUpdates = kotlinx.coroutines.flow.flowOf(Fixtures.purchase(PlayProducts.SOLIDAR_YEARLY))
        }
        PlayTransactions(gateway, PlayDelivery(gateway, FakeVerifier.refusing("Abgelaufen.")), events).observe()
        assertEquals("a refused purchase changes nothing", 0, events.version.value)
    }
}
