package de.velwebsolutions.oasis.membership.billing

import android.app.Activity

/**
 * What [PlayBillingGateway] needs from Google Play Billing, in the membership's own types: the gateway's decisions
 * (connecting, waiting for the purchase sheet, mapping Play's answers) are tested against a fake, and
 * [BillingClientConnection] is the thin layer over Play's `BillingClient`. Response codes are
 * `BillingClient.BillingResponseCode` values; every call may throw a `RuntimeException` when Play's service is gone.
 */
interface PlayBillingConnection {
    /** Purchases Play reports, during a purchase flow or on its own. */
    fun interface Listener {
        fun onPurchasesUpdated(responseCode: Int, purchases: List<PlayPurchase>)
    }

    /** Receives [Listener] calls; set once by the gateway before anything else. */
    var listener: Listener?

    /** Connected and usable. */
    val isReady: Boolean

    /** Connects to Play; [onReady] gets whether the connection is usable (it may never be called). */
    fun connect(onReady: (Boolean) -> Unit)

    /** Play's subscriptions with these ids, or the response code that refused them. */
    suspend fun products(ids: List<String>): Answer<List<PlayProduct>>

    /** Opens Play's purchase sheet for a product [products] returned, over [activity]; the response code of the launch. */
    fun launch(activity: Activity, productId: String, offerToken: String, accountId: String?): Int

    /** The subscriptions Play knows for the signed-in Google account. */
    suspend fun purchases(): Answer<List<PlayPurchase>>

    /** A response code with the data Play sent along. */
    data class Answer<T>(val responseCode: Int, val value: T)
}
