package de.velwebsolutions.oasis.membership.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The membership overview (`App\Http\Resources\Api\V1\Membership\MembershipResource`, GET /api/v1/membership): the data
 * of /app/mitgliedschaft.
 */
@Serializable
data class MembershipOverview(
    val state: State = State.Expired,
    val subscription: Subscription? = null,
    val gift: Gift? = null,
    val trialDaysLeft: Int? = null,
    val trialEndsAt: OasisInstant? = null,
    val isFoundingMember: Boolean = false,
    val remainingFoundingSeats: Int = 0,
    val canCancel: Boolean = false,
    val canWithdraw: Boolean = false,
    val purchasedGifts: List<PurchasedGift> = emptyList(),
    val giftOffers: List<GiftOffer> = emptyList(),
    /** In-app subscriptions through Apple (`apple` of MembershipResource); null from servers before they existed. */
    val apple: Apple? = null
) {
    /** Which status card the web shows. */
    @Serializable
    enum class State {
        /** A paid subscription (also while cancelled but still paid). */
        @SerialName("subscription")
        Subscription,

        /** A redeemed, not yet expired gift voucher. */
        @SerialName("gift")
        Gift,

        /** The free trial is running. */
        @SerialName("trial")
        Trial,

        /** Trial over, read-only mode. */
        @SerialName("expired")
        Expired
    }

    @Serializable
    data class Subscription(
        val tierName: String,
        /** Cancelled, access until [endsAt]. */
        val onGracePeriod: Boolean = false,
        val endsAt: OasisInstant? = null
    )

    @Serializable
    data class Gift(val tierName: String, val expiresAt: OasisInstant? = null)

    @Serializable
    data class PurchasedGift(val id: Int, val code: String, val tierName: String, val redeemed: Boolean = false)

    @Serializable
    data class GiftOffer(val tier: String, val name: String, val priceLabel: String)

    /**
     * The App Store side of the membership. The server has no Google Play counterpart yet, so the Android app reads only
     * [available] (in-app subscriptions are switched on) and whether the running membership is billed by Apple (then it
     * is changed and cancelled there, see [manageUrl]).
     */
    @Serializable
    data class Apple(
        val available: Boolean = false,
        val accountToken: String? = null,
        val products: List<Product> = emptyList(),
        val managedByApple: Boolean = false,
        val manageUrl: String? = null
    ) {
        @Serializable
        data class Product(val id: String, val tier: String, val rhythm: String)
    }
}

/** One paid contribution (`PaymentResource`). */
@Serializable
data class MembershipPayment(
    val id: Int,
    val invoiceNumber: String,
    val paidAt: OasisInstant? = null,
    val amountLabel: String,
    val refunded: Boolean = false
)

/** The § 312k BGB confirmation page data (GET /api/v1/membership/cancellation). */
@Serializable
data class CancellationDetails(
    val tierName: String,
    val name: String,
    val email: String,
    val effectiveDate: OasisInstant? = null
)

/** The withdrawal confirmation page data (GET /api/v1/membership/withdrawal). */
@Serializable
data class WithdrawalDetails(val tierName: String, val name: String, val email: String)
