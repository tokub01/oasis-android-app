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
    val isFoundingMember: Boolean = false,
    val remainingFoundingSeats: Int = 0,
    val canCancel: Boolean = false,
    val canWithdraw: Boolean = false,
    val purchasedGifts: List<PurchasedGift> = emptyList(),
    /** A membership billed by Apple (bought on an iPhone); null from servers before in-app subscriptions existed. */
    val apple: Apple? = null,
    /** In-app subscriptions through Google Play (`google` of MembershipResource); null from servers before they existed. */
    val google: Google? = null
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

        /** No membership: Oasis is closed until one is chosen (there is no free trial). */
        @SerialName("expired")
        Expired
    }

    /** A paid subscription. */
    @Serializable
    data class Subscription(
        val tierName: String,
        /** Cancelled, access until [endsAt]. */
        val onGracePeriod: Boolean = false,
        val endsAt: OasisInstant? = null
    )

    /** A redeemed gift voucher. */
    @Serializable
    data class Gift(val tierName: String, val expiresAt: OasisInstant? = null)

    /** A voucher the member bought on the web, with its code. */
    @Serializable
    data class PurchasedGift(val id: Int, val code: String, val tierName: String, val redeemed: Boolean = false)

    /**
     * The App Store side of the membership, as far as Android needs it: whether the running membership is billed by
     * Apple (then it is changed and cancelled there, see [manageUrl]).
     */
    @Serializable
    data class Apple(val managedByApple: Boolean = false, val manageUrl: String? = null)

    /**
     * The Google Play side of the membership: whether the app may sell ([available]), the products to offer, the
     * account id a purchase carries (`obfuscatedAccountId`) and whether the running membership is billed by Google
     * (then it is changed and cancelled in the Play Store, see [manageUrl]).
     */
    @Serializable
    data class Google(
        val available: Boolean = false,
        val accountId: String? = null,
        val products: List<Product> = emptyList(),
        val managedByGoogle: Boolean = false,
        val manageUrl: String? = null
    ) {
        /** One Play subscription: the product id, its tier and its rhythm ("monat" or "jahr"). */
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
