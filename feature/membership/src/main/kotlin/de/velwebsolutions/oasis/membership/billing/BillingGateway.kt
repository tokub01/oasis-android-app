package de.velwebsolutions.oasis.membership.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** A subscription as Google Play sells it: Play's name, description and localised price. */
data class PlayProduct(
    val id: String,
    val name: String,
    val description: String,
    /** "5,00 €" as Play formats it for the member's country. */
    val price: String,
    val period: BillingPeriod,
    /** The base plan's offer token Play needs to start the purchase. */
    val offerToken: String?
)

/** The renewal period of a subscription (ISO 8601 `P1M`, `P1Y`, `P1W`). */
enum class BillingPeriod(val per: String) {
    Week("/ Woche"),
    Month("/ Monat"),
    Year("/ Jahr"),
    Unknown("")
    ;

    companion object {
        fun parse(iso: String?): BillingPeriod = when {
            iso == null -> Unknown
            iso.endsWith("Y") -> Year
            iso.endsWith("M") -> Month
            iso.endsWith("W") -> Week
            else -> Unknown
        }
    }
}

/** A purchase Google Play reported (right after buying, later for pending payments, or from the member's account). */
data class PlayPurchase(
    val purchaseToken: String,
    val productIds: List<String>,
    /** Paid; false while the payment is still pending (for example cash payments or parental approval). */
    val isPurchased: Boolean,
    val isAcknowledged: Boolean
) {
    /** The first of our products in the purchase. */
    val productId: String? get() = productIds.firstOrNull { PlayProducts.entry(it) != null }

    /** What the server needs to check the purchase with the Google Play Developer API. */
    fun receipt(): PlayPurchaseReceipt? = productId?.let { PlayPurchaseReceipt(purchaseToken, it) }
}

/** The body for the server check of a Google Play purchase: `{"purchase_token": "…", "product_id": "…"}`. */
@Serializable
data class PlayPurchaseReceipt(val purchaseToken: String, val productId: String)

/** How a purchase flow ended. */
sealed interface PurchaseOutcome {
    /** Paid; the purchases go to the server. */
    data class Purchased(val purchases: List<PlayPurchase>) : PurchaseOutcome

    /** Bought, but the payment is still pending; Play reports it again once it is paid. */
    data object Pending : PurchaseOutcome

    /** The member closed Play's sheet. */
    data object Cancelled : PurchaseOutcome

    /** Google Play is not available on this device (no Play Store, not signed in, unsupported country). */
    data object Unavailable : PurchaseOutcome

    /** Play could not complete the purchase. */
    data object Failed : PurchaseOutcome
}

/**
 * Google Play Billing as the membership needs it, behind an interface so the checkout and the background delivery can
 * be tested with a fake. Nothing here may throw because Play is missing: an emulator without the Play Store gets
 * `null` products, [PurchaseOutcome.Unavailable] and no purchases.
 */
interface BillingGateway {
    /** Play's details of the subscriptions with these ids; null when Google Play Billing is not available. */
    suspend fun products(ids: List<String>): List<PlayProduct>?

    /**
     * Starts Play's purchase sheet over [activity] and waits for its result. [accountId] (the member's account token)
     * ties the purchase to the Oasis account, like StoreKit's `appAccountToken`.
     */
    suspend fun purchase(activity: Activity?, product: PlayProduct, accountId: String?): PurchaseOutcome

    /** The subscriptions Play knows for the signed-in Google account ("Käufe wiederherstellen"). */
    suspend fun ownedPurchases(): List<PlayPurchase>

    /** Confirms the purchase to Play – only after the server unlocked the membership. Unacknowledged ones are refunded. */
    suspend fun acknowledge(purchase: PlayPurchase): Boolean

    /** Purchases that arrive outside a running purchase flow (pending payments that went through, renewals). */
    val purchaseUpdates: Flow<PlayPurchase>
}
