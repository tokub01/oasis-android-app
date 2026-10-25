package de.velwebsolutions.oasis.membership

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode
import de.velwebsolutions.oasis.membership.billing.PlayBillingConnection
import de.velwebsolutions.oasis.membership.billing.PlayBillingGateway
import de.velwebsolutions.oasis.membership.billing.PlayProduct
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.billing.PlayPurchase
import de.velwebsolutions.oasis.membership.billing.PurchaseOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Play Billing as a script: what it answers, whether it connects, and which calls blow up. */
private class FakeConnection : PlayBillingConnection {
    override var listener: PlayBillingConnection.Listener? = null
    override var isReady = false

    /** What [connect] reports: true/false, or null for "never answers". */
    var connects: Boolean? = true
    var connectThrows = false

    /** Play answers "not ready" and then the call still fails. */
    var answersThenThrows = false
    var productsAnswer = PlayBillingConnection.Answer(BillingResponseCode.OK, Fixtures.playCatalogue)
    var purchasesAnswer = PlayBillingConnection.Answer(BillingResponseCode.OK, listOf(Fixtures.purchase()))
    var launchCode = BillingResponseCode.OK
    var acknowledgeCode = BillingResponseCode.OK
    var failing = false
    var cancelling = false
    val launched = mutableListOf<Pair<String, String?>>()
    var connectCalls = 0

    override fun connect(onReady: (Boolean) -> Unit) {
        connectCalls++
        if (connectThrows) throw IllegalStateException("no Play")
        if (answersThenThrows) {
            onReady(false)
            throw IllegalStateException("service gone")
        }
        connects?.let { ready ->
            isReady = ready
            onReady(ready)
            // Play may report twice (setup finished, then disconnected); only the first answer counts.
            onReady(false)
        }
    }

    override suspend fun products(ids: List<String>): PlayBillingConnection.Answer<List<PlayProduct>> {
        if (cancelling) throw kotlinx.coroutines.CancellationException("left")
        check(!failing) { "service gone" }
        return productsAnswer
    }

    override fun launch(activity: Activity, productId: String, offerToken: String, accountId: String?): Int {
        check(!failing) { "service gone" }
        launched += productId to accountId
        return launchCode
    }

    override suspend fun purchases(): PlayBillingConnection.Answer<List<PlayPurchase>> {
        check(!failing) { "service gone" }
        return purchasesAnswer
    }

    override suspend fun acknowledge(purchaseToken: String): Int {
        check(!failing) { "service gone" }
        return acknowledgeCode
    }

    fun report(code: Int, vararg purchases: PlayPurchase) = listener!!.onPurchasesUpdated(code, purchases.toList())
}

@RunWith(RobolectricTestRunner::class)
class PlayBillingGatewayTest {
    private val connection = FakeConnection()
    private val gateway = PlayBillingGateway(connection)
    private val activity = Robolectric.buildActivity(Activity::class.java).get()
    private val oase = Fixtures.playCatalogue.first { it.id == PlayProducts.OASE_MONTHLY }

    @Test
    fun `connects once on first use and lists Play's products`() = runTest {
        assertEquals(Fixtures.playCatalogue, gateway.products(PlayProducts.ids))
        assertEquals(listOf(Fixtures.purchase()), gateway.ownedPurchases())
        assertEquals(1, connection.connectCalls)
    }

    @Test
    fun `a device without Play gets no products, no purchases and no purchase sheet`() = runTest {
        connection.connects = false
        assertNull(gateway.products(PlayProducts.ids))
        assertEquals(emptyList<PlayPurchase>(), gateway.ownedPurchases())
        assertEquals(PurchaseOutcome.Unavailable, gateway.purchase(activity, oase, null))
        assertFalse(gateway.acknowledge(Fixtures.purchase()))

        connection.connectThrows = true
        assertNull(gateway.products(PlayProducts.ids))
        connection.connectThrows = false
        connection.answersThenThrows = true
        assertNull(gateway.products(PlayProducts.ids))
    }

    @Test
    fun `a connection that never answers gives up after the timeout`() = runTest {
        connection.connects = null
        val products = async { gateway.products(PlayProducts.ids) }
        advanceTimeBy(PlayBillingGateway.CONNECT_TIMEOUT_MS + 1)
        assertNull(products.await())
    }

    @Test
    fun `refusals and a lost service end as nothing, never as a crash`() = runTest {
        connection.productsAnswer = PlayBillingConnection.Answer(BillingResponseCode.ERROR, emptyList())
        assertNull(gateway.products(PlayProducts.ids))
        connection.purchasesAnswer = PlayBillingConnection.Answer(BillingResponseCode.ERROR, listOf(Fixtures.purchase()))
        assertEquals(emptyList<PlayPurchase>(), gateway.ownedPurchases())
        connection.acknowledgeCode = BillingResponseCode.ERROR
        assertFalse(gateway.acknowledge(Fixtures.purchase()))

        connection.failing = true
        assertNull(gateway.products(PlayProducts.ids))
        assertEquals(emptyList<PlayPurchase>(), gateway.ownedPurchases())
        assertFalse(gateway.acknowledge(Fixtures.purchase()))
    }

    @Test
    fun `a purchase waits for Play's answer and carries the account token`() = runTest {
        gateway.products(PlayProducts.ids)
        val outcome = async { gateway.purchase(activity, oase, "account-token") }
        testScheduler.runCurrent()
        connection.report(BillingResponseCode.OK, Fixtures.purchase())
        assertEquals(PurchaseOutcome.Purchased(listOf(Fixtures.purchase())), outcome.await())
        assertEquals(listOf(oase.id to "account-token"), connection.launched)
        assertTrue(gateway.acknowledge(Fixtures.purchase()))
    }

    @Test
    fun `too long account ids are not sent to Play`() = runTest {
        gateway.products(PlayProducts.ids)
        val outcome = async { gateway.purchase(activity, oase, "x".repeat(PlayBillingGateway.MAX_ACCOUNT_ID + 1)) }
        testScheduler.runCurrent()
        connection.report(BillingResponseCode.USER_CANCELED)
        assertEquals(PurchaseOutcome.Cancelled, outcome.await())
        assertNull(connection.launched.single().second)
    }

    @Test
    fun `nothing is launched without an activity, an offer or Play's details`() = runTest {
        assertEquals("products not loaded yet", PurchaseOutcome.Failed, gateway.purchase(activity, oase, null))
        gateway.products(PlayProducts.ids)
        assertEquals(PurchaseOutcome.Failed, gateway.purchase(null, oase, null))
        assertEquals(PurchaseOutcome.Failed, gateway.purchase(activity, oase.copy(offerToken = null), null))
        assertTrue(connection.launched.isEmpty())
    }

    @Test
    fun `a sheet that does not open reports why`() = runTest {
        gateway.products(PlayProducts.ids)
        connection.launchCode = BillingResponseCode.BILLING_UNAVAILABLE
        assertEquals(PurchaseOutcome.Unavailable, gateway.purchase(activity, oase, null))
        connection.failing = true
        assertEquals(PurchaseOutcome.Failed, gateway.purchase(activity, oase, null))
    }

    @Test
    fun `purchases outside a purchase flow go to the updates, refusals do not`() = runTest {
        gateway.products(PlayProducts.ids)
        val update = async { gateway.purchaseUpdates.first() }
        testScheduler.runCurrent()
        connection.report(BillingResponseCode.ERROR, Fixtures.purchase(PlayProducts.SOLIDAR_MONTHLY))
        connection.report(BillingResponseCode.OK, Fixtures.purchase(PlayProducts.MAEZEN_YEARLY))
        assertEquals(PlayProducts.MAEZEN_YEARLY, update.await().productId)
    }

    @Test
    fun `Play's response codes map to the purchase outcomes`() {
        val paid = Fixtures.purchase()
        val pending = Fixtures.purchase(purchased = false)
        assertEquals(PurchaseOutcome.Purchased(listOf(paid)), PlayBillingGateway.outcome(BillingResponseCode.OK, listOf(paid, pending)))
        assertEquals(PurchaseOutcome.Pending, PlayBillingGateway.outcome(BillingResponseCode.OK, listOf(pending)))
        assertEquals(PurchaseOutcome.Failed, PlayBillingGateway.outcome(BillingResponseCode.OK, emptyList()))
        assertEquals(PurchaseOutcome.Cancelled, PlayBillingGateway.outcome(BillingResponseCode.USER_CANCELED, emptyList()))
        listOf(BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            .forEach { assertEquals(PurchaseOutcome.Unavailable, PlayBillingGateway.outcome(it, emptyList())) }
        assertEquals(PurchaseOutcome.Failed, PlayBillingGateway.outcome(BillingResponseCode.ERROR, emptyList()))
        assertEquals(PurchaseOutcome.Failed, PlayBillingGateway.outcome(null, emptyList()))
    }

    @Test
    fun `a cancelled call is passed on, not swallowed`() = runTest {
        connection.cancelling = true
        assertTrue(runCatching { gateway.products(PlayProducts.ids) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
