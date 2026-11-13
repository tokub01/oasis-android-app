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
     * An announced change of the AGB whose objection period still runs (`terms_change`, AGB § 19) – and, for a member
     * with a standing objection, the change after its day; null without one and from servers before announcements.
     */
    val termsChange: TermsChange? = null,
    /**
     * When the member agreed to the stored contract text (`terms_accepted_at`, the latest contract confirmation,
     * AGB § 21); null without a confirmation and from servers before it.
     */
    val termsAcceptedAt: OasisInstant? = null
) {
    /** Paid or gifted: a held companion place is not shown then (Perks.php `isPaying`). */
    val isPaying: Boolean get() = state != State.Expired

    /**
     * A running, uncancelled membership billed through the website (Mollie): its tier is changed here (POST
     * membership/tier); a store membership is changed in the store, a cancelled one not at all
     * (MembershipTierChanges::ensureChangeable).
     */
    val canChangeTier: Boolean
        get() = state == State.Subscription && subscription?.onGracePeriod == false && canCancel &&
            apple?.managedByApple != true && google?.managedByGoogle != true

    /** "06.11.2026 um 14:32 Uhr" – when the member agreed to the contract text; null without. */
    val termsAcceptedMoment: String? get() = termsAcceptedAt?.let(MembershipFormat::moment)

    /** A Mäzen's place: who gives it and the tier it brings (TierFeatures::COMPANION_TIER). */
    @Serializable
    data class CompanionSeat(val sponsor: String, val tier: String? = null, val tierName: String = "Mittendrin")

    /**
     * An announced change of the AGB (`oasis:terms-announce`, `TermsAnnouncement::currentFor`): the day it takes
     * effect, what changes and why, and the member's own objection ([objectedAt], null until they object; POST/DELETE
     * membership/terms-objection). After the day ([inEffect]) only a member with a standing objection still gets it:
     * the version they agreed to governs them ([appliesToYou] false), and [terminationEndsAt] names the end of the
     * contract once the operator terminated it for the objection (AGB § 7). [url] is the new version to read.
     */
    @Serializable
    data class TermsChange(
        val effectiveOn: OasisDay? = null,
        val summary: String = "",
        val reason: String = "",
        val announcedAt: OasisInstant? = null,
        val objectedAt: OasisInstant? = null,
        /** The day has come: the new version is in force (`in_effect`); false from servers before it. */
        val inEffect: Boolean = false,
        /** The new version governs this member (`applies_to_you`): false with a standing objection; null from servers before it. */
        val appliesToYou: Boolean? = null,
        /** The end of the contract the operator terminated for the objection (`termination_ends_at`); null while not. */
        val terminationEndsAt: OasisInstant? = null,
        val url: String? = null
    ) {
        /** Whether this member has objected to the change. */
        val objected: Boolean get() = objectedAt != null

        /** The operator terminated the contract for the objection (TermsObjection::isTerminated). */
        val isTerminated: Boolean get() = terminationEndsAt != null

        /** "21.12.2026" – the day as the web names it. */
        val effectiveDay: String get() = MembershipFormat.calendarDay(effectiveOn)

        /** The card's heading (overview.blade.php): „Unsere AGB ändern sich am …“ until the day, „… haben sich am … geändert“ from it on. */
        val title: String
            get() = if (inEffect) "Unsere AGB haben sich am $effectiveDay geändert" else "Unsere AGB ändern sich am $effectiveDay"

        /**
         * The standing objection as the web words it (TermsObjections::standingText): the moment, that the previous
         * version keeps applying – until the end of a terminated contract, as the version agreed to once the change is
         * in force, or with the promise to get in touch before a termination. Null while the member did not object.
         */
        val standingText: String?
            get() {
                val moment = objectedAt ?: return null
                val line = "Du hast am ${MembershipFormat.moment(moment)} widersprochen. Für dich gilt die bisherige Fassung weiter"
                return when {
                    terminationEndsAt != null ->
                        "$line – bis zum Ende deines Vertrags am ${MembershipFormat.day(terminationEndsAt)}, den wir deshalb nach " +
                            "AGB § 7 gekündigt haben (Begründung per E-Mail)."

                    inEffect ->
                        "$line: die Fassung, der du zugestimmt hast (unten unter „Dein Vertragstext“). Wir melden uns, falls wir " +
                            "deshalb kündigen müssen (AGB § 7)."

                    else -> "$line; wir melden uns, falls wir deshalb kündigen müssen (AGB § 7)."
                }
            }

        /**
         * „Widerspruch zurücknehmen“ only while the change can still be objected to and nothing was terminated
         * (overview.blade.php `@unless ($termsAnnouncement->inEffect() || $termsObjection->isTerminated())`).
         */
        val canWithdrawObjection: Boolean get() = objected && !inEffect && !isTerminated
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
        /** The tier's key (`solidar`, `oase`, `maezen`); null from servers before the tier change. */
        val tier: String? = null,
        /** Cancelled, access until [endsAt]. */
        val onGracePeriod: Boolean = false,
        val endsAt: OasisInstant? = null,
        /** The end of the paid period: the next charge of a running Mollie membership (the tier change names it). */
        val paidUntil: OasisInstant? = null
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

/**
 * A tier the tier change offers (`config('oasis.tiers')`, as the portal's select lists them: „Mäzen – ab 12 €
 * monatlich“). The server validates the key and the Mäzen minimum again (TierChangeController).
 */
data class MembershipTier(
    val key: String,
    val name: String,
    /** Euros per month; the minimum for Mäzen. */
    val monthly: Int,
    val isMinimum: Boolean
) {
    /** „Oase – 5 € monatlich“, „Mäzen – ab 12 € monatlich“ (portal.blade.php). */
    val label: String get() = "$name – ${if (isMinimum) "ab " else ""}$monthly\u00A0€ monatlich"

    companion object {
        /** The Mäzen tier, whose amount the member chooses (at least its [monthly]). */
        const val MAEZEN = "maezen"

        /** The three tiers in the web's order. */
        val all = listOf(
            MembershipTier("solidar", "Solidar", 3, isMinimum = false),
            MembershipTier("oase", "Mittendrin", 5, isMinimum = false),
            MembershipTier(MAEZEN, "Mäzen", 12, isMinimum = true)
        )

        /** The least a Mäzen gives per month, in whole euros (CheckoutSelection::minimumMaezen). */
        val minimumMaezen: Int = all.first { it.key == MAEZEN }.monthly
    }
}

/** The body of `POST membership/tier`: the tier's key and, for Mäzen, the monthly amount in whole euros (left out otherwise). */
@Serializable
data class TierChangeInput(val tier: String, val amount: Int? = null)
