package de.velwebsolutions.oasis.membership.api

import de.velwebsolutions.oasis.core.network.OasisDay
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
    val google: Google? = null,
    /** What the tier allows (TierFeatures); empty from servers before the tiers. */
    val features: MembershipPerks.Features = MembershipPerks.Features(),
    /**
     * The place of a Mäzen, while it gives access (`companion_seat`). [state] stays [State.Expired] for it; the status
     * card then shows the place instead (status.blade.php).
     */
    val companionSeat: CompanionSeat? = null,
    /**
     * An announced change of the AGB whose objection period still runs (`terms_change`, AGB § 19); null without one and
     * from servers before announcements existed.
     */
    val termsChange: TermsChange? = null
) {
    /** Paid or gifted: a held companion place is not shown then (Perks.php `isPaying`). */
    val isPaying: Boolean get() = state != State.Expired

    /** A Mäzen's place: who gives it and the tier it brings (TierFeatures::COMPANION_TIER). */
    @Serializable
    data class CompanionSeat(val sponsor: String, val tier: String? = null, val tierName: String = "Oase")

    /**
     * An announced change of the AGB (`oasis:terms-announce`): the day it takes effect, what changes and why, and the
     * member's own objection ([objectedAt], null until they object; POST/DELETE membership/terms-objection). [url] is the
     * new version to read.
     */
    @Serializable
    data class TermsChange(
        val effectiveOn: OasisDay? = null,
        val summary: String = "",
        val reason: String = "",
        val announcedAt: OasisInstant? = null,
        val objectedAt: OasisInstant? = null,
        val url: String? = null
    ) {
        /** Whether this member has objected to the change. */
        val objected: Boolean get() = objectedAt != null
    }

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

/**
 * Deine Vorteile (GET /api/v1/membership/perks): what the tier includes, the companion places a Mäzen gives, the place
 * a companion holds and the supporter choices.
 */
@Serializable
data class MembershipPerks(
    val tier: String? = null,
    val tierName: String = "",
    val features: Features = Features(),
    /** The lines of "In … enthalten". */
    val highlights: List<String> = emptyList(),
    val seats: List<Seat> = emptyList(),
    val remainingSeats: Int = 0,
    /** The place this member holds from a Mäzen, null without one. */
    val heldSeat: HeldSeat? = null,
    val supporterBadge: Boolean = false,
    val thankYouList: Boolean = false
) {
    /** What the tier allows, as far as this screen needs it (TierFeatures). */
    @Serializable
    data class Features(val companionSeats: Int = 0, val supporterBadge: Boolean = false)

    /** A given companion place: taken by [member] since [acceptedAt], or open with the share link [url]. */
    @Serializable
    data class Seat(val id: Int, val member: Member? = null, val acceptedAt: OasisInstant? = null, val url: String? = null) {
        @Serializable
        data class Member(val displayName: String, val username: String)
    }

    /** The held place: whose it is and whether it is active (the sponsor is still Mäzen) or resting. */
    @Serializable
    data class HeldSeat(val sponsor: String, val active: Boolean = false)
}

/** Who offers the place behind a companion link (GET membership/companion-links/{token}); null: no longer valid. */
@Serializable
data class CompanionLinkInfo(val sponsor: String? = null)

/** The supporter choices to change (PATCH membership/supporter); a null choice stays as it is. */
@Serializable
data class SupporterInput(val supporterBadge: Boolean? = null, val thankYouList: Boolean? = null)
