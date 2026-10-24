package de.velwebsolutions.oasis.membership

import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import de.velwebsolutions.oasis.membership.billing.BillingClientConnection
import de.velwebsolutions.oasis.membership.billing.BillingPeriod
import de.velwebsolutions.oasis.membership.billing.PlayBillingConnection
import de.velwebsolutions.oasis.membership.billing.PlayProduct
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.billing.playProduct
import de.velwebsolutions.oasis.membership.billing.playPurchase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** Play's own objects as the membership reads them, and the client on a device without the Play Store. */
@RunWith(RobolectricTestRunner::class)
class BillingClientConnectionTest {
    /** Play's product JSON (as `queryProductDetails` delivers it) with the given offers. */
    private fun details(offers: String?): ProductDetails {
        val json = """
            {"productId":"${PlayProducts.OASE_MONTHLY}","type":"subs","title":"Oase (Oasis)","name":"Oase",
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
        val connection = BillingClientConnection(ApplicationProvider.getApplicationContext())
        var ready: Boolean? = null
        connection.connect { ready = it }
        ShadowLooper.idleMainLooper()
        assertEquals(false, ready)
        connection.listener = PlayBillingConnection.Listener { _, _ -> }
        assertTrue("Robolectric binds the service, so the client counts as ready", connection.isReady)
    }
}
