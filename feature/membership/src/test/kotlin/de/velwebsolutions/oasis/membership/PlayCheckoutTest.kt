package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.membership.billing.BillingGateway
import de.velwebsolutions.oasis.membership.billing.BillingPeriod
import de.velwebsolutions.oasis.membership.billing.PlayDelivery
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.billing.PurchaseOutcome
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.ui.AlreadyMember
import de.velwebsolutions.oasis.membership.ui.PlayCheckoutViewModel
import de.velwebsolutions.oasis.membership.ui.text
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import de.velwebsolutions.oasis.testing.StubTransport.Stub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Play checkout against the server's `google-purchases` endpoint: 200 unlocks, 422 says why not, 503 keeps it. */
class PlayCheckoutTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val events = MembershipEvents()
    private val welcome = Fixtures.confirmed(Fixtures.WELCOME)
    private val otherAccount =
        "Dieser Kauf gehört zu einem anderen Oasis-Konto. Melde dich mit dem Konto an, mit dem du ihn abgeschlossen hast."
    private val unreachable = "Dein Kauf konnte gerade nicht übernommen werden. Bitte versuche es später noch einmal."
    private val refused = Stub("POST", "membership/google-purchases", status = 422, json = """{"message":"$otherAccount"}""")

    private lateinit var transport: StubTransport

    private suspend fun checkout(
        gateway: FakeBillingGateway = FakeBillingGateway(),
        overview: String = Fixtures.noMembershipOverview,
        purchase: Stub = Stub("POST", "membership/google-purchases", json = welcome)
    ): PlayCheckoutViewModel {
        val (repository, transport) = Fixtures.repository(Stub("GET", "membership", json = overview), purchase)
        this.transport = transport
        return PlayCheckoutViewModel(repository, gateway, PlayDelivery(repository), events, Fixtures.session()).also { it.load() }
    }

    /** "token product" of the last purchase sent to the server. */
    private fun sent(): String? = (transport.lastBody("membership/google-purchases") as? JsonObject)?.let {
        (it["purchase_token"] as JsonPrimitive).content + " " + (it["product_id"] as JsonPrimitive).content
    }

    @Test
    fun `offers the server's products with Play's prices in both rhythms`() = runTest {
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
    fun `a purchase carries the account id, goes to the server and its welcome is shown`() = runTest {
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Purchased(listOf(Fixtures.purchase())))
        val viewModel = checkout(gateway)
        val oase = viewModel.state.value.offers.valueOrNull!![1].monthly!!

        assertTrue(viewModel.purchase(null, oase))

        assertEquals(listOf(Catalogue.OASE_MONTHLY to Fixtures.ACCOUNT_ID), gateway.purchased)
        assertEquals("token-${Catalogue.OASE_MONTHLY} ${Catalogue.OASE_MONTHLY}", sent())
        assertEquals(Fixtures.WELCOME, viewModel.state.value.message)
        assertEquals(Fixtures.WELCOME, events.take().message)
        assertNull(viewModel.state.value.purchasing)
    }

    @Test
    fun `a refusal shows the server's reason`() = runTest {
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Purchased(listOf(Fixtures.purchase())))
        val viewModel = checkout(gateway, purchase = refused)
        assertFalse(viewModel.purchase(null, Fixtures.product(Catalogue.OASE_MONTHLY, "5,00 €")))
        assertEquals(otherAccount, viewModel.state.value.error)
        assertEquals(0, events.version.value)
    }

    @Test
    fun `an unreachable check keeps the purchase, restoring sends it again`() = runTest {
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Purchased(listOf(Fixtures.purchase())))
        val viewModel = checkout(
            gateway,
            purchase = Stub("POST", "membership/google-purchases", status = 503, json = """{"message":"$unreachable"}""")
        )
        assertFalse(viewModel.purchase(null, Fixtures.product(Catalogue.OASE_MONTHLY, "5,00 €")))
        assertEquals(unreachable, viewModel.state.value.error)

        gateway.owned = listOf(Fixtures.purchase())
        transport.add("POST", "membership/google-purchases", json = welcome)
        viewModel.restore()
        assertEquals(Fixtures.WELCOME, viewModel.state.value.message)
    }

    @Test
    fun `pending, cancelled, failed and unavailable purchases send nothing`() = runTest {
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Pending)
        val viewModel = checkout(gateway)
        val product = Fixtures.product(Catalogue.SOLIDAR_MONTHLY, "3,00 €")
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
        viewModel.dismissError()

        gateway.outcome = PurchaseOutcome.Unavailable
        assertFalse(viewModel.purchase(null, product))
        assertEquals(PlayCheckoutViewModel.UNAVAILABLE, viewModel.state.value.error)

        gateway.outcome = PurchaseOutcome.Purchased(listOf(Fixtures.purchase(purchased = false)))
        assertFalse("a purchase still pending at Play is not sent", viewModel.purchase(null, product))
        assertNull(sent())
    }

    @Test
    fun `a second tap while buying starts nothing`() = runTest {
        val gateway = FakeBillingGateway()
        val viewModel = checkout(gateway)
        val product = Fixtures.product(Catalogue.OASE_MONTHLY, "5,00 €")
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        val first = async { viewModel.purchase(null, product) }
        testScheduler.runCurrent()
        assertEquals(product.id, viewModel.state.value.purchasing)
        assertFalse(viewModel.purchase(null, product))
        gate.complete(Unit)
        assertFalse(first.await())
        assertEquals(1, gateway.purchased.size)
    }

    @Test
    fun `while the server takes no Play purchases the prices show but nothing can be bought`() = runTest {
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Purchased(listOf(Fixtures.purchase())))
        val viewModel = checkout(gateway, overview = Fixtures.noMembershipOverview.replace("\"available\":true", "\"available\":false"))
        assertFalse(viewModel.state.value.canPurchase)
        assertEquals(3, viewModel.state.value.offers.valueOrNull?.size)
        assertFalse(viewModel.purchase(null, Fixtures.product(Catalogue.OASE_MONTHLY, "5,00 €")))
        assertTrue("Play's sheet is not even opened", gateway.purchased.isEmpty())
    }

    @Test
    fun `restore sends the account's purchases, says when there are none, and shows refusals`() = runTest {
        val empty = checkout()
        empty.restore()
        assertEquals(PlayCheckoutViewModel.NOTHING_TO_RESTORE, empty.state.value.message)

        val pending = checkout(FakeBillingGateway(owned = listOf(Fixtures.purchase(purchased = false))))
        pending.restore()
        assertEquals(PlayCheckoutViewModel.NOTHING_TO_RESTORE, pending.state.value.message)

        val refusedOne = checkout(FakeBillingGateway(owned = listOf(Fixtures.purchase())), purchase = refused)
        refusedOne.restore()
        assertNull(refusedOne.state.value.message)
        assertEquals(otherAccount, refusedOne.state.value.error)

        val restored = checkout(FakeBillingGateway(owned = listOf(Fixtures.purchase(acknowledged = true))))
        restored.restore()
        assertEquals("an acknowledged purchase is sent again (idempotent)", Fixtures.WELCOME, restored.state.value.message)
    }

    @Test
    fun `members are not sold a second membership`() = runTest {
        val subscribed = Fixtures.noMembershipOverview.replace("\"state\":\"expired\"", "\"state\":\"subscription\"")
        assertEquals(AlreadyMember.Web, checkout(overview = subscribed).state.value.alreadyMember)
        val google = subscribed.replace("\"managed_by_google\":false", "\"managed_by_google\":true")
        assertEquals(AlreadyMember.Google, checkout(overview = google).state.value.alreadyMember)
        val apple = checkout(overview = subscribed.replace("\"managed_by_apple\":false", "\"managed_by_apple\":true"))
        assertEquals(AlreadyMember.Apple, apple.state.value.alreadyMember)
        assertEquals(emptyList<Any>(), apple.state.value.offers.valueOrNull)
        assertTrue(AlreadyMember.Google.text().contains("Google Play"))
        assertTrue(AlreadyMember.Apple.text().contains("Apple-ID"))
        assertTrue(AlreadyMember.Web.text().contains("Website"))
    }

    @Test
    fun `servers without the Google block, devices without Play and tiers Play does not know`() = runTest {
        val old = checkout(overview = Fixtures.subscriptionOverview.replace("\"state\":\"subscription\"", "\"state\":\"expired\""))
        assertEquals(emptyList<Any>(), old.state.value.offers.valueOrNull)
        assertFalse(old.state.value.canPurchase)

        assertEquals(emptyList<Any>(), checkout(FakeBillingGateway(catalogue = null)).state.value.offers.valueOrNull)

        val yearlyOnly = checkout(FakeBillingGateway(catalogue = listOf(Fixtures.product(Catalogue.MAEZEN_YEARLY, "120,00 €"))))
        val offers = yearlyOnly.state.value.offers.valueOrNull!!
        assertEquals(listOf("maezen"), offers.map { it.tier })
        assertNull(offers.single().product(PlayProducts.MONTHLY))
    }

    @Test
    fun `a failed first load shows the error page, a cancelled one is passed on`() = runTest {
        val failing = FakeMembershipRepository().apply { overview = FakeMembershipRepository.failure() }
        val failed = PlayCheckoutViewModel(failing, FakeBillingGateway(), PlayDelivery(failing), events, Fixtures.session())
        failed.load()
        assertTrue(failed.state.value.offers is Loadable.Failed)

        val cancelled = FakeMembershipRepository().apply { overview = Result.failure(CancellationException("left")) }
        val cancelling = PlayCheckoutViewModel(cancelled, FakeBillingGateway(), PlayDelivery(cancelled), events, Fixtures.session())
        assertTrue(runCatching { cancelling.load() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `purchases outside the checkout go to the server, then the member is reloaded`() = runTest {
        val open = Fixtures.purchase()
        val gateway = FakeBillingGateway(owned = listOf(open, Fixtures.purchase(Catalogue.MAEZEN_YEARLY, acknowledged = true)))
        val repository = FakeMembershipRepository()
        val job = launch { PlayTransactions(gateway, PlayDelivery(repository), events, Fixtures.session()).observe() }
        testScheduler.runCurrent()
        assertEquals("only open purchases are sent at start", listOf(open.purchaseToken), repository.receipts.map { it.purchaseToken })
        assertEquals(1, events.version.value)

        gateway.updates.emit(Fixtures.purchase(Catalogue.SOLIDAR_YEARLY, purchased = false))
        testScheduler.runCurrent()
        assertEquals("a pending payment is not sent", 1, repository.receipts.size)
        job.cancel()
    }

    @Test
    fun `background delivery keeps refused purchases and ends with Play's updates`() = runTest {
        val gateway = object : BillingGateway by FakeBillingGateway(owned = listOf(Fixtures.purchase())) {
            override val purchaseUpdates = flowOf(Fixtures.purchase(Catalogue.SOLIDAR_YEARLY))
        }
        val repository = FakeMembershipRepository().apply { googlePurchase = FakeMembershipRepository.failure("Abgelaufen.") }
        PlayTransactions(gateway, PlayDelivery(repository), events, Fixtures.session()).observe()
        assertEquals("a refused purchase changes nothing", 0, events.version.value)
        assertEquals(2, repository.receipts.size)
    }
}
