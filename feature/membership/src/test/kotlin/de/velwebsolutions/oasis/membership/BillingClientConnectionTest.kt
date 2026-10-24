package de.velwebsolutions.oasis.membership

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.AlternativeBillingOnlyAvailabilityListener
import com.android.billingclient.api.AlternativeBillingOnlyInformationDialogListener
import com.android.billingclient.api.AlternativeBillingOnlyReportingDetailsListener
import com.android.billingclient.api.BillingChoiceInfoResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingConfigResponseListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingProgramAvailabilityListener
import com.android.billingclient.api.BillingProgramInformationDialogListener
import com.android.billingclient.api.BillingProgramInformationDialogParams
import com.android.billingclient.api.BillingProgramReportingDetailsListener
import com.android.billingclient.api.BillingProgramReportingDetailsParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ExternalOfferAvailabilityListener
import com.android.billingclient.api.ExternalOfferInformationDialogListener
import com.android.billingclient.api.ExternalOfferReportingDetailsListener
import com.android.billingclient.api.GetBillingChoiceInfoParams
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResponseListener
import com.android.billingclient.api.LaunchExternalLinkParams
import com.android.billingclient.api.LaunchExternalLinkResponseListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import de.velwebsolutions.oasis.membership.billing.BillingClientConnection
import de.velwebsolutions.oasis.membership.billing.BillingPeriod
import de.velwebsolutions.oasis.membership.billing.PlayBillingConnection
import de.velwebsolutions.oasis.membership.billing.PlayProduct
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.billing.playProduct
import de.velwebsolutions.oasis.membership.billing.playPurchase
import de.velwebsolutions.oasis.membership.billing.stateListener
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** Play's own objects as the membership reads them, and the client on a device without the Play Store. */
@RunWith(RobolectricTestRunner::class)
class BillingClientConnectionTest {
    /** Play's product JSON (as `queryProductDetails` delivers it) with the given offers. */
    private fun details(offers: String?): ProductDetails {
        val json = """
            {"productId":"${PlayProducts.OASE_MONTHLY}","type":"${if (offers == null) "inapp" else "subs"}","title":"Oase (Oasis)","name":"Oase",
             "description":"Werbefrei","localizedIn":["de-DE"],"skuDetailsToken":"token"${offers?.let {
            ",\"subscriptionOfferDetails\":[$it]"
        } ?: ""}}
        """.trimIndent()
        val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
        return constructor.newInstance(json)
    }

    private fun offer(offerId: String?, token: String, vararg phases: Pair<String, String>) = """
        {${offerId?.let { "\"offerId\":\"$it\"," } ?: ""}"basePlanId":"monat","offerIdToken":"$token","offerTags":[],
         "pricingPhases":[${phases.joinToString(",") { (price, period) ->
        """{"priceAmountMicros":5000000,"priceCurrencyCode":"EUR","formattedPrice":"$price","billingPeriod":"$period","recurrenceMode":1,"billingCycleCount":0}"""
    }}]}
    """.trimIndent()

    @Test
    fun `the base plan's recurring price is the checkout's price`() {
        val product =
            playProduct(
                details(
                    offer("intro", "intro-token", "0,00 €" to "P1W") + "," + offer(null, "base-token", "1,00 €" to "P1M", "5,00 €" to "P1M")
                )
            )
        assertEquals(PlayProduct(PlayProducts.OASE_MONTHLY, "Oase", "Werbefrei", "5,00 €", BillingPeriod.Month, "base-token"), product)
    }

    @Test
    fun `without a base plan the first offer counts, and products without offers or prices are skipped`() {
        assertEquals("intro-token", playProduct(details(offer("intro", "intro-token", "3,00 €" to "P1Y")))?.offerToken)
        assertNull(playProduct(details("")))
        assertNull("a one-time product has no subscription offers", playProduct(details(null)))
        assertNull(playProduct(details(offer(null, "base-token"))))
    }

    @Test
    fun `purchases keep their token, products, payment and acknowledgement`() {
        val paid = Purchase(
            """{"purchaseToken":"token","productIds":["${PlayProducts.OASE_MONTHLY}"],"purchaseState":0,"acknowledged":true}""",
            "signature"
        )
        val mapped = playPurchase(paid)
        assertEquals("token", mapped.purchaseToken)
        assertEquals(listOf(PlayProducts.OASE_MONTHLY), mapped.productIds)
        assertTrue(mapped.isPurchased)
        assertTrue(mapped.isAcknowledged)
        val pending = Purchase("""{"purchaseToken":"t","productIds":[],"purchaseState":4}""", "signature")
        assertFalse(playPurchase(pending).isPurchased)
    }

    @Test
    fun `without the Play Store the client binds but Play Billing does not set up`() {
        val connection = BillingClientConnection(ApplicationProvider.getApplicationContext<android.content.Context>())
        var ready: Boolean? = null
        connection.connect { ready = it }
        ShadowLooper.idleMainLooper()
        assertEquals(false, ready)
        connection.listener = PlayBillingConnection.Listener { _, _ -> }
        assertTrue("Robolectric binds the service, so the client counts as ready", connection.isReady)
    }

    /** Play's client as a script: every call answers right away with [code]. */
    private class FakeClient(val listener: PurchasesUpdatedListener) : BillingClient() {
        var code = BillingClient.BillingResponseCode.OK
        var details: List<ProductDetails> = emptyList()
        var launches = 0
        private fun result() = BillingResult.newBuilder().setResponseCode(code).build()

        override fun isReady() = true
        override fun startConnection(listener: BillingClientStateListener) = listener.onBillingSetupFinished(result())
        override fun queryProductDetailsAsync(params: QueryProductDetailsParams, listener: ProductDetailsResponseListener) =
            listener.onProductDetailsResponse(result(), QueryProductDetailsResult.create(details, emptyList()))
        override fun queryPurchasesAsync(params: QueryPurchasesParams, listener: PurchasesResponseListener) =
            listener.onQueryPurchasesResponse(
                result(),
                listOf(Purchase("""{"purchaseToken":"t","productIds":["${PlayProducts.OASE_MONTHLY}"],"purchaseState":1}""", "s"))
            )
        override fun acknowledgePurchase(params: AcknowledgePurchaseParams, listener: AcknowledgePurchaseResponseListener) =
            listener.onAcknowledgePurchaseResponse(result())
        override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult {
            launches++
            return result()
        }

        override fun getConnectionState() = 2
        override fun isFeatureSupported(feature: String) = result()
        override fun showAlternativeBillingOnlyInformationDialog(a: Activity, l: AlternativeBillingOnlyInformationDialogListener) = result()
        override fun showExternalOfferInformationDialog(a: Activity, l: ExternalOfferInformationDialogListener) = result()
        override fun showInAppMessages(a: Activity, p: InAppMessageParams, l: InAppMessageResponseListener) = result()
        override fun consumeAsync(p: ConsumeParams, l: ConsumeResponseListener) = Unit
        override fun createAlternativeBillingOnlyReportingDetailsAsync(l: AlternativeBillingOnlyReportingDetailsListener) = Unit
        override fun createBillingProgramReportingDetailsAsync(
            p: BillingProgramReportingDetailsParams,
            l: BillingProgramReportingDetailsListener
        ) = Unit
        override fun createExternalOfferReportingDetailsAsync(l: ExternalOfferReportingDetailsListener) = Unit
        override fun endConnection() = Unit
        override fun getBillingChoiceInfoAsync(p: GetBillingChoiceInfoParams, l: BillingChoiceInfoResponseListener) = Unit
        override fun getBillingConfigAsync(p: GetBillingConfigParams, l: BillingConfigResponseListener) = Unit
        override fun isAlternativeBillingOnlyAvailableAsync(l: AlternativeBillingOnlyAvailabilityListener) = Unit
        override fun isBillingProgramAvailableAsync(program: Int, l: BillingProgramAvailabilityListener) = Unit
        override fun isExternalOfferAvailableAsync(l: ExternalOfferAvailabilityListener) = Unit
        override fun launchExternalLink(a: Activity, p: LaunchExternalLinkParams, l: LaunchExternalLinkResponseListener) = Unit
        override fun showBillingProgramInformationDialog(
            a: Activity,
            p: BillingProgramInformationDialogParams,
            l: BillingProgramInformationDialogListener
        ) = Unit
    }

    @Test
    fun `requests go to Play's client and its answers come back in our types`() = runTest {
        lateinit var client: FakeClient
        val connection = BillingClientConnection { listener -> FakeClient(listener).also { client = it } }
        var ready: Boolean? = null
        connection.connect { ready = it }
        assertEquals(true, ready)
        assertTrue(connection.isReady)

        client.details = listOf(details(offer(null, "base-token", "5,00 € " to "P1M")), details(""))
        val products = connection.products(listOf(PlayProducts.OASE_MONTHLY))
        assertEquals(listOf(PlayProducts.OASE_MONTHLY), products.value.map { it.id })
        client.details = emptyList()
        client.code = BillingClient.BillingResponseCode.ERROR
        assertEquals(BillingClient.BillingResponseCode.ERROR, connection.products(listOf(PlayProducts.OASE_MONTHLY)).responseCode)
        client.code = BillingClient.BillingResponseCode.OK

        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertEquals(BillingClient.BillingResponseCode.OK, connection.launch(activity, PlayProducts.OASE_MONTHLY, "base-token", "account"))
        connection.launch(activity, PlayProducts.OASE_MONTHLY, "base-token", null)
        assertEquals(2, client.launches)

        val purchases = connection.purchases()
        assertEquals(listOf("t"), purchases.value.map { it.purchaseToken })
        client.code = BillingClient.BillingResponseCode.ERROR
        assertEquals(BillingClient.BillingResponseCode.ERROR, connection.acknowledge("t"))
    }

    @Test
    fun `Play's purchase updates reach the listener, also without purchases`() {
        lateinit var client: FakeClient
        val connection = BillingClientConnection { listener -> FakeClient(listener).also { client = it } }
        connection.connect {}
        val reported = mutableListOf<Pair<Int, Int>>()
        val ok = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()
        client.listener.onPurchasesUpdated(ok, null)
        connection.listener = PlayBillingConnection.Listener { code, purchases -> reported += code to purchases.size }
        client.listener.onPurchasesUpdated(ok, null)
        client.listener.onPurchasesUpdated(ok, mutableListOf(Purchase("""{"purchaseToken":"t","productIds":[],"purchaseState":1}""", "s")))
        assertEquals(listOf(0 to 0, 0 to 1), reported)
    }

    @Test
    fun `only a finished setup with OK counts as ready`() {
        val answers = mutableListOf<Boolean>()
        val listener = stateListener { answers += it }
        listener.onBillingSetupFinished(BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build())
        listener.onBillingSetupFinished(
            BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE).build()
        )
        listener.onBillingServiceDisconnected()
        assertEquals(listOf(true, false, false), answers)
    }
}
