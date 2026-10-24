package de.velwebsolutions.oasis.membership

import android.app.Activity
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.billing.BillingGateway
import de.velwebsolutions.oasis.membership.billing.BillingPeriod
import de.velwebsolutions.oasis.membership.billing.PlayProduct
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.billing.PlayPurchase
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseReceipt
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseVerifier
import de.velwebsolutions.oasis.membership.billing.PurchaseOutcome
import de.velwebsolutions.oasis.membership.data.DefaultMembershipRepository
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow

/** Real answers of the Laravel resources (copied from http://localhost/api/v1/membership… for demo accounts, as on iOS). */
object Fixtures {
    val trialOverview = """
        {"data":{"state":"trial","subscription":null,"gift":null,"trial_days_left":20,"trial_ends_at":"2026-11-11T13:32:15.000000Z","trial_banner_days_left":20,"is_founding_member":false,"remaining_founding_seats":1000,"can_cancel":false,"can_withdraw":false,"purchased_gifts":[],"gift_offers":[{"tier":"solidar","name":"Solidar","price_label":"12 Monate für 30 €"},{"tier":"oase","name":"Oase","price_label":"12 Monate für 50 €"},{"tier":"maezen","name":"Mäzen","price_label":"12 Monate für 120 €"}],"checkout_enabled":true,"referral":{"earned_this_year":0,"waiting":0,"months_per_year":12},"apple":{"available":true,"account_token":"3f2b7c1e-8a4d-5b6e-9c0f-1a2b3c4d5e6f","products":[{"id":"de.velwebsolutions.oasis.solidar.monat","tier":"solidar","rhythm":"monat"},{"id":"de.velwebsolutions.oasis.oase.jahr","tier":"oase","rhythm":"jahr"}],"managed_by_apple":false,"manage_url":"https:\/\/apps.apple.com\/account\/subscriptions"}}}
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
        name = PlayProducts.entry(id)!!.tier.replaceFirstChar(Char::uppercase),
        description = "",
        price = price,
        period = if (id.endsWith(".jahr")) BillingPeriod.Year else BillingPeriod.Month,
        offerToken = "offer-$id"
    )

    val playCatalogue = PlayProducts.all.map { product(it.id, it.fallbackPrice) }

    fun purchase(productId: String = PlayProducts.OASE_MONTHLY, purchased: Boolean = true, acknowledged: Boolean = false) =
        PlayPurchase("token-$productId", listOf(productId), isPurchased = purchased, isAcknowledged = acknowledged)
}

/** Google Play as the tests need it: a fixed catalogue and scripted purchase outcomes. */
class FakeBillingGateway(
    var catalogue: List<PlayProduct>? = Fixtures.playCatalogue,
    var outcome: PurchaseOutcome = PurchaseOutcome.Cancelled,
    var owned: List<PlayPurchase> = emptyList()
) : BillingGateway {
    val purchased = mutableListOf<Pair<String, String?>>()
    val acknowledged = mutableListOf<String>()
    val updates = MutableSharedFlow<PlayPurchase>(extraBufferCapacity = 8)

    override suspend fun products(ids: List<String>) = catalogue?.filter { it.id in ids }

    override suspend fun purchase(activity: Activity?, product: PlayProduct, accountId: String?): PurchaseOutcome {
        purchased += product.id to accountId
        return outcome
    }

    override suspend fun ownedPurchases() = owned

    override suspend fun acknowledge(purchase: PlayPurchase): Boolean {
        acknowledged += purchase.purchaseToken
        return true
    }

    override val purchaseUpdates = updates
}

/** The server check of Play purchases as it will work once the backend has its endpoint. */
class FakeVerifier(
    override val isAvailable: Boolean = true,
    var answer: Result<Confirmed<MembershipOverview>> = Result.success(
        Confirmed(Fixtures.overview(Fixtures.subscriptionOverview), "Willkommen als Mitglied!")
    )
) : PlayPurchaseVerifier {
    val receipts = mutableListOf<PlayPurchaseReceipt>()

    override suspend fun verify(receipt: PlayPurchaseReceipt): Confirmed<MembershipOverview> {
        receipts += receipt
        return answer.getOrThrow()
    }

    companion object {
        fun refusing(message: String) =
            FakeVerifier(answer = Result.failure(ApiError.Validation(message, mapOf("purchase_token" to listOf(message)))))
    }
}
