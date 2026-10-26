package de.velwebsolutions.oasis.membership

import android.app.Activity
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.membership.api.MembershipOverview
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
        {"data":{"state":"expired","subscription":null,"gift":null,"is_founding_member":false,"remaining_founding_seats":1000,"can_cancel":false,"can_withdraw":false,"purchased_gifts":[],"gift_offers":[{"tier":"solidar","name":"Solidar","price_label":"12 Monate für 30 €"},{"tier":"oase","name":"Oase","price_label":"12 Monate für 50 €"},{"tier":"maezen","name":"Mäzen","price_label":"12 Monate für 120 €"}],"checkout_enabled":true,"referral":{"earned_this_year":0,"waiting":0,"months_per_year":12},"apple":{"managed_by_apple":false,"manage_url":"https:\/\/apps.apple.com\/account\/subscriptions"},"google":{"available":true,"account_id":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","products":[{"id":"de.velwebsolutions.oasis.solidar.monat","tier":"solidar","rhythm":"monat"},{"id":"de.velwebsolutions.oasis.solidar.jahr","tier":"solidar","rhythm":"jahr"},{"id":"de.velwebsolutions.oasis.oase.monat","tier":"oase","rhythm":"monat"},{"id":"de.velwebsolutions.oasis.oase.jahr","tier":"oase","rhythm":"jahr"},{"id":"de.velwebsolutions.oasis.maezen.monat","tier":"maezen","rhythm":"monat"},{"id":"de.velwebsolutions.oasis.maezen.jahr","tier":"maezen","rhythm":"jahr"}],"managed_by_google":false,"manage_url":"https:\/\/play.google.com\/store\/account\/subscriptions"}}}
    """.trimIndent()

    val subscriptionOverview = """
        {"data":{"state":"subscription","subscription":{"tier":"oase","tier_name":"Oase","rhythm":"monat","on_grace_period":false,"ends_at":null,"paid_until":"2026-11-18T15:39:37.000000Z"},"gift":null,"trial_days_left":null,"trial_ends_at":"2026-10-21T15:39:37.000000Z","trial_banner_days_left":null,"is_founding_member":true,"remaining_founding_seats":999,"can_cancel":true,"can_withdraw":true,"purchased_gifts":[],"gift_offers":[{"tier":"solidar","name":"Solidar","price_label":"12 Monate für 30 €"},{"tier":"oase","name":"Oase","price_label":"12 Monate für 50 €"},{"tier":"maezen","name":"Mäzen","price_label":"12 Monate für 120 €"}],"checkout_enabled":true,"referral":{"earned_this_year":0,"waiting":0,"months_per_year":12}}}
    """.trimIndent()

    val payments = """
        {"data":[{"id":2,"invoice_number":"OAS-2","kind":"membership","paid_at":"2026-10-19T15:39:37.000000Z","amount":500,"amount_label":"5 €","refunded":false,"invoice_url":"http:\/\/localhost\/api\/v1\/membership\/invoices\/2"}]}
    """.trimIndent()

    val cancellation = """
        {"data":{"tier_name":"Oase","name":"Mia Mitglied","email":"memsub@example.test","effective_date":"2026-11-18T15:39:37.000000Z"}}
    """.trimIndent()

    const val WITHDRAWAL = """{"data":{"tier_name":"Oase","name":"Mia Mitglied","email":"memsub@example.test"}}"""

    const val CANCELLED = "Deine Kündigung wurde erfolgreich erfasst. Wir haben dir eine Bestätigung per E-Mail geschickt."

    /** The subscription overview as the answer of an action (`{message, data}`). */
    fun confirmed(message: String) = subscriptionOverview.replaceFirst("""{"data":""", """{"message":"$message","data":""")

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

    const val WELCOME = "Willkommen als Mitglied! Deine Mitgliedschaft „Oase“ läuft, bezahlt bis 18.11.2026."

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
