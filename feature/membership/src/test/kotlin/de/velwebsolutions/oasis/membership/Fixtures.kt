package de.velwebsolutions.oasis.membership

import android.app.Activity
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.MembershipPerks
import de.velwebsolutions.oasis.membership.billing.BillingGateway
import de.velwebsolutions.oasis.membership.billing.BillingPeriod
import de.velwebsolutions.oasis.membership.billing.PlayProduct
import de.velwebsolutions.oasis.membership.billing.PlayPurchase
import de.velwebsolutions.oasis.membership.billing.PurchaseOutcome
import de.velwebsolutions.oasis.membership.data.DefaultMembershipRepository
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow

/** The Play products as the server lists them (`google.products`), with the prices they were created with. */
object Catalogue {
    const val SOLIDAR_MONTHLY = "de.velwebsolutions.oasis.solidar.monat"
    const val SOLIDAR_YEARLY = "de.velwebsolutions.oasis.solidar.jahr"
    const val OASE_MONTHLY = "de.velwebsolutions.oasis.oase.monat"
    const val OASE_YEARLY = "de.velwebsolutions.oasis.oase.jahr"
    const val MAEZEN_MONTHLY = "de.velwebsolutions.oasis.maezen.monat"
    const val MAEZEN_YEARLY = "de.velwebsolutions.oasis.maezen.jahr"

    /** One listed product. */
    data class Entry(val id: String, val tier: String, val rhythm: String, val price: String)

    val all = listOf(
        Entry(SOLIDAR_MONTHLY, "solidar", "monat", "3,00 €"),
        Entry(SOLIDAR_YEARLY, "solidar", "jahr", "30,00 €"),
        Entry(OASE_MONTHLY, "oase", "monat", "5,00 €"),
        Entry(OASE_YEARLY, "oase", "jahr", "50,00 €"),
        Entry(MAEZEN_MONTHLY, "maezen", "monat", "12,00 €"),
        Entry(MAEZEN_YEARLY, "maezen", "jahr", "120,00 €")
    )
    val ids = all.map { it.id }
    val tiers = all.map { it.tier }.distinct()

    fun entry(id: String) = all.firstOrNull { it.id == id }
}

/** Real answers of the Laravel resources (copied from http://localhost/api/v1/membership… for demo accounts, as on iOS). */
object Fixtures {
    /** A member without membership (there is no free trial): only "Mitglied werden" is open. */
    val noMembershipOverview = """
        {"data":{"state":"expired","subscription":null,"gift":null,"is_founding_member":false,"remaining_founding_seats":1000,"can_cancel":false,"can_withdraw":false,"purchased_gifts":[],"gift_offers":[{"tier":"solidar","name":"Solidar","price_label":"12 Monate für 30 €"},{"tier":"oase","name":"Mittendrin","price_label":"12 Monate für 50 €"},{"tier":"maezen","name":"Mäzen","price_label":"12 Monate für 120 €"}],"checkout_enabled":true,"referral":{"earned_this_year":0,"waiting":0,"months_per_year":12},"apple":{"managed_by_apple":false,"manage_url":"https:\/\/apps.apple.com\/account\/subscriptions"},"google":{"available":true,"account_id":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","products":[{"id":"de.velwebsolutions.oasis.solidar.monat","tier":"solidar","rhythm":"monat"},{"id":"de.velwebsolutions.oasis.solidar.jahr","tier":"solidar","rhythm":"jahr"},{"id":"de.velwebsolutions.oasis.oase.monat","tier":"oase","rhythm":"monat"},{"id":"de.velwebsolutions.oasis.oase.jahr","tier":"oase","rhythm":"jahr"},{"id":"de.velwebsolutions.oasis.maezen.monat","tier":"maezen","rhythm":"monat"},{"id":"de.velwebsolutions.oasis.maezen.jahr","tier":"maezen","rhythm":"jahr"}],"managed_by_google":false,"manage_url":"https:\/\/play.google.com\/store\/account\/subscriptions"}}}
    """.trimIndent()

    val subscriptionOverview = """
        {"data":{"state":"subscription","subscription":{"tier":"oase","tier_name":"Mittendrin","rhythm":"monat","on_grace_period":false,"ends_at":null,"paid_until":"2026-11-18T15:39:37.000000Z"},"gift":null,"trial_days_left":null,"trial_ends_at":"2026-10-21T15:39:37.000000Z","trial_banner_days_left":null,"is_founding_member":true,"remaining_founding_seats":999,"can_cancel":true,"can_withdraw":true,"purchased_gifts":[],"gift_offers":[{"tier":"solidar","name":"Solidar","price_label":"12 Monate für 30 €"},{"tier":"oase","name":"Mittendrin","price_label":"12 Monate für 50 €"},{"tier":"maezen","name":"Mäzen","price_label":"12 Monate für 120 €"}],"checkout_enabled":true,"referral":{"earned_this_year":0,"waiting":0,"months_per_year":12}}}
    """.trimIndent()

    val payments = """
        {"data":[{"id":2,"invoice_number":"OAS-2","kind":"membership","paid_at":"2026-10-19T15:39:37.000000Z","amount":500,"amount_label":"5 €","refunded":false,"invoice_url":"http:\/\/localhost\/api\/v1\/membership\/invoices\/2"}]}
    """.trimIndent()

    val cancellation = """
        {"data":{"tier_name":"Mittendrin","name":"Mia Mitglied","email":"memsub@example.test","effective_date":"2026-11-18T15:39:37.000000Z"}}
    """.trimIndent()

    const val WITHDRAWAL = """{"data":{"tier_name":"Mittendrin","name":"Mia Mitglied","email":"memsub@example.test"}}"""

    const val CANCELLED = "Deine Kündigung wurde erfolgreich erfasst. Wir haben dir eine Bestätigung per E-Mail geschickt."

    /** An overview as the answer of an action (`{message, data}`). */
    fun confirmed(message: String, overview: String = subscriptionOverview) =
        overview.replaceFirst("""{"data":""", """{"message":"$message","data":""")

    /**
     * The `terms_change` block of an announced change of the AGB (AGB § 19), with or without this member's objection;
     * after the day ([inEffect]) and once the operator terminated the contract for the objection ([terminationEndsAt]).
     */
    fun termsChange(objectedAt: String? = null, inEffect: Boolean = false, terminationEndsAt: String? = null) =
        """{"effective_on":"2026-12-21","summary":"Wir fassen § 14 neu.","reason":"Das Gesetz verlangt es.",""" +
            """"announced_at":"2026-11-01T09:00:00.000000Z","objected_at":${objectedAt?.let { "\"$it\"" } ?: "null"},""" +
            """"in_effect":$inEffect,"applies_to_you":${objectedAt == null},""" +
            """"termination_ends_at":${terminationEndsAt?.let { "\"$it\"" } ?: "null"},"url":"http:\/\/localhost\/agb"}"""

    /** The moment this member objected (13:32 UTC is 14:32 in Berlin). */
    const val OBJECTED_AT = "2026-11-06T13:32:00.000000Z"

    /** The running subscription with the given `terms_change` block. */
    fun withTermsChange(change: String, overview: String = subscriptionOverview) =
        overview.replaceFirst("""{"data":{""", """{"data":{"terms_change":$change,""")

    /** The running subscription whose member agreed to the stored contract text on 18 October 2026 (17:39 in Berlin). */
    val termsAcceptedOverview =
        subscriptionOverview.replaceFirst("""{"data":{""", """{"data":{"terms_accepted_at":"2026-10-18T15:39:37.000000Z",""")

    /** The running subscription while a change of the AGB is announced and not yet objected to. */
    val termsChangeOverview = subscriptionOverview.replaceFirst("""{"data":{""", """{"data":{"terms_change":${termsChange()},""")

    /** … after this member objected. */
    val termsObjectedOverview = withTermsChange(termsChange(OBJECTED_AT))

    /** … after the day of the change, for the objector the server still sends it (the previous version governs them). */
    val termsInEffectObjectedOverview = withTermsChange(termsChange(OBJECTED_AT, inEffect = true))

    /** … once the operator terminated the contract for the objection (AGB § 7), to the end of the paid period. */
    val termsTerminatedOverview = withTermsChange(termsChange(OBJECTED_AT, terminationEndsAt = "2027-01-31T23:00:00.000000Z"))

    const val TIER_CHANGED =
        "Deine Stufe ist jetzt „Mäzen“. Der neue Beitrag von 15 € monatlich gilt ab deiner nächsten Abbuchung am 18.11.2026. " +
            "Die Bestätigung mit dem Vertragstext haben wir dir per E-Mail geschickt."

    const val SAME_TIER = "Das ist bereits deine Stufe mit diesem Beitrag."

    const val NOT_RUNNING = "Du hast derzeit keine laufende, ungekündigte Mitgliedschaft, deren Stufe du ändern könntest."

    /** The running subscription after the change to Mäzen. */
    val maezenOverview = subscriptionOverview.replace(""""tier":"oase","tier_name":"Mittendrin"""", """"tier":"maezen","tier_name":"Mäzen"""")

    const val OBJECTED = "Dein Widerspruch ist am 06.11.2026 um 14:32 Uhr eingegangen. Für dich gilt die bisherige Fassung weiter."

    const val OBJECTION_WITHDRAWN =
        "Du hast deinen Widerspruch zurückgenommen. Die neue Fassung gilt ab dem 21.12.2026 auch für deinen Vertrag."

    const val ALREADY_OBJECTED = "Du hast dieser Änderung bereits widersprochen."

    /** Deine Vorteile of a Mäzen with one taken and one open place (as PerksController answers). */
    val maezenPerks = """
        {"data":{"tier":"maezen","tier_name":"Mäzen","features":{"tier":"maezen","oases":10,"post_length":2000,"images":10,"image_edge":4096,
        "time_capsule":true,"yearbook":true,"statistics":true,"companion_seats":2,"supporter_badge":true},
        "highlights":["Alles aus Mittendrin","Fotos in voller Auflösung","Zwei volle Plätze für deine Leute"],
        "seats":[{"id":1,"member":{"display_name":"Jonas Weber","username":"jonas"},"accepted_at":"2026-10-20T10:00:00.000000Z","url":null},
        {"id":2,"member":null,"accepted_at":null,"url":"http://localhost/app/mitgliedschaft/mitkommen/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],
        "remaining_seats":0,"held_seat":null,"supporter_badge":true,"thank_you_list":false}}
    """.trimIndent()

    /** Deine Vorteile of a companion whose sponsor is no Mäzen any more (the place rests). */
    val companionPerks = """
        {"data":{"tier":"oase","tier_name":"Mittendrin","features":{"companion_seats":0,"supporter_badge":false},"highlights":["Zeitkapseln"],
        "seats":[],"remaining_seats":0,"held_seat":{"sponsor":"Mia Mäzen","active":false},"supporter_badge":false,"thank_you_list":false}}
    """.trimIndent()

    fun perks(json: String = maezenPerks): MembershipPerks = OasisJson.snake.decodeFromString(
        de.velwebsolutions.oasis.core.network.DataEnvelope.serializer(MembershipPerks.serializer()),
        json
    ).data

    fun overview(json: String): MembershipOverview = OasisJson.snake.decodeFromString(
        de.velwebsolutions.oasis.core.network.DataEnvelope.serializer(MembershipOverview.serializer()),
        json
    ).data

    fun repository(vararg stubs: StubTransport.Stub): Pair<DefaultMembershipRepository, StubTransport> {
        val transport = StubTransport(*stubs)
        return DefaultMembershipRepository(transport.client) to transport
    }

    /** A signed-out session: `refreshMe()` does nothing. */
    fun session() = Session(InMemoryTokenStore(), "Test", CoroutineScope(Dispatchers.Unconfined))

    fun product(id: String, price: String) = PlayProduct(
        id = id,
        name = Catalogue.entry(id)!!.tier.replaceFirstChar(Char::uppercase),
        description = "",
        price = price,
        period = if (id.endsWith(".jahr")) BillingPeriod.Year else BillingPeriod.Month,
        offerToken = "offer-$id"
    )

    val playCatalogue = Catalogue.all.map { product(it.id, it.price) }

    /** The account id the server hands out for `obfuscatedAccountId`. */
    const val ACCOUNT_ID = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    const val WELCOME = "Willkommen als Mitglied! Deine Mitgliedschaft „Mittendrin“ läuft, bezahlt bis 18.11.2026."

    fun purchase(productId: String = Catalogue.OASE_MONTHLY, purchased: Boolean = true, acknowledged: Boolean = false) =
        PlayPurchase("token-$productId", listOf(productId), isPurchased = purchased, isAcknowledged = acknowledged)
}

/** Google Play as the tests need it: a fixed catalogue and scripted purchase outcomes. */
class FakeBillingGateway(
    var catalogue: List<PlayProduct>? = Fixtures.playCatalogue,
    var outcome: PurchaseOutcome = PurchaseOutcome.Cancelled,
    var owned: List<PlayPurchase> = emptyList()
) : BillingGateway {
    val purchased = mutableListOf<Pair<String, String?>>()

    /** Holds a purchase until the test completes it. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val updates = MutableSharedFlow<PlayPurchase>(extraBufferCapacity = 8)

    override suspend fun products(ids: List<String>) = catalogue?.filter { it.id in ids }

    override suspend fun purchase(activity: Activity?, product: PlayProduct, accountId: String?): PurchaseOutcome {
        purchased += product.id to accountId
        gate?.await()
        return outcome
    }

    override suspend fun ownedPurchases() = owned

    override val purchaseUpdates = updates
}
