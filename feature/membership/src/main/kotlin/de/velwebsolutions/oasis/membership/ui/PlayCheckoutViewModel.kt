package de.velwebsolutions.oasis.membership.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.billing.BillingGateway
import de.velwebsolutions.oasis.membership.billing.PlayDelivery
import de.velwebsolutions.oasis.membership.billing.PlayProduct
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.billing.PlayPurchase
import de.velwebsolutions.oasis.membership.billing.PurchaseOutcome
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.data.MembershipRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One tier in both rhythms, in the checkout's order (Solidar, Oase, Mäzen). */
data class CheckoutOffer(val tier: String, val monthly: PlayProduct?, val yearly: PlayProduct?) {
    /** The product for "monat" or "jahr". */
    fun product(rhythm: String): PlayProduct? = if (rhythm == PlayProducts.YEARLY) yearly else monthly
}

/** Why the checkout sells nothing to this member. */
enum class AlreadyMember { Web, Apple, Google }

/** What the checkout says to someone who already pays, so they do not pay twice. */
fun AlreadyMember.text(): String = when (this) {
    AlreadyMember.Apple -> "Du bist schon Mitglied. Deine Mitgliedschaft läuft über deine Apple-ID; eine zweite brauchst du nicht."

    AlreadyMember.Google ->
        "Du bist schon Mitglied. Deine Mitgliedschaft läuft über Google Play; Stufe und Laufzeit änderst du im Play Store."

    else -> "Du bist schon Mitglied. Deine Mitgliedschaft läuft über die Website; eine zweite brauchst du nicht."
}

/** The checkout: Play's offers, the chosen rhythm, a running purchase and snackbars. */
data class CheckoutUiState(
    val offers: Loadable<List<CheckoutOffer>> = Loadable.Idle,
    /** "monat" or "jahr" (the server's rhythm keys). */
    val rhythm: String = PlayProducts.MONTHLY,
    /** The product being bought, to disable the buttons. */
    val purchasing: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** The member already pays (website, Apple or Google Play); a second subscription would charge twice. */
    val alreadyMember: AlreadyMember? = null,
    /** False while the server does not take Google Play purchases (`google.available`): the prices show, buying is off. */
    val canPurchase: Boolean = false
)

/**
 * Buying the membership in the app through Google Play (iOS `AppleStoreModel`): the server lists the products
 * (`google.products`), Play adds names and prices, a purchase carries the member's account id
 * (`obfuscatedAccountId`), and the purchase goes to the server, which unlocks the membership and acknowledges it
 * (PlayDelivery).
 */
@HiltViewModel
class PlayCheckoutViewModel @Inject constructor(
    private val repository: MembershipRepository,
    private val gateway: BillingGateway,
    private val delivery: PlayDelivery,
    private val events: MembershipEvents,
    private val session: Session
) : ViewModel() {
    private val mutableState = MutableStateFlow(CheckoutUiState())
    val state: StateFlow<CheckoutUiState> = mutableState.asStateFlow()
    private var accountId: String? = null

    init {
        viewModelScope.launch { load() }
    }

    /** Loads the page again; a failed first load shows the error page, a failed refresh a snackbar. */
    suspend fun load() {
        mutableState.update { it.copy(offers = it.offers.refreshing()) }
        try {
            val overview = repository.overview()
            val google = overview.google
            accountId = google?.accountId
            val alreadyMember = when {
                overview.state != MembershipOverview.State.Subscription -> null
                overview.apple?.managedByApple == true -> AlreadyMember.Apple
                google?.managedByGoogle == true -> AlreadyMember.Google
                else -> AlreadyMember.Web
            }
            val offers = if (google == null || alreadyMember != null) emptyList() else offers(google.products)
            mutableState.update {
                it.copy(offers = Loadable.Loaded(offers), alreadyMember = alreadyMember, canPurchase = google?.available == true)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(offers = it.offers.failing(error)) }
        }
    }

    /** The server's products with Play's details, one offer per tier in the server's order; tiers Play does not know are left out. */
    private suspend fun offers(listed: List<MembershipOverview.Google.Product>): List<CheckoutOffer> {
        val products = gateway.products(listed.map { it.id }).orEmpty().associateBy(PlayProduct::id)
        return listed.map { it.tier }.distinct().map { tier ->
            val byRhythm = listed.filter { it.tier == tier }.associate { it.rhythm to products[it.id] }
            CheckoutOffer(tier, byRhythm[PlayProducts.MONTHLY], byRhythm[PlayProducts.YEARLY])
        }.filter { it.monthly != null || it.yearly != null }
    }

    /** Shows the monthly or yearly prices. */
    fun selectRhythm(rhythm: String) = mutableState.update { it.copy(rhythm = rhythm) }

    /** "Abonnieren". Returns true once the server unlocked the membership. */
    suspend fun purchase(activity: Activity?, product: PlayProduct): Boolean {
        if (!mutableState.value.canPurchase || mutableState.value.purchasing != null) return false
        mutableState.update { it.copy(error = null, purchasing = product.id) }
        try {
            return when (val outcome = gateway.purchase(activity, product, accountId)) {
                is PurchaseOutcome.Purchased -> deliver(outcome.purchases)

                PurchaseOutcome.Pending -> {
                    mutableState.update { it.copy(message = PENDING) }
                    false
                }

                PurchaseOutcome.Cancelled -> false

                PurchaseOutcome.Unavailable -> {
                    mutableState.update { it.copy(error = UNAVAILABLE) }
                    false
                }

                PurchaseOutcome.Failed -> {
                    mutableState.update { it.copy(error = FAILED) }
                    false
                }
            }
        } finally {
            mutableState.update { it.copy(purchasing = null) }
        }
    }

    /**
     * "Käufe wiederherstellen": asks Google Play for this Google account's subscriptions and hands them to the server
     * (for example after reinstalling the app or on a new phone).
     */
    suspend fun restore() {
        mutableState.update { it.copy(error = null) }
        val purchases = gateway.ownedPurchases()
        val restored = deliver(purchases)
        // A refusal already says why; otherwise "nothing found".
        if (!restored && mutableState.value.error == null) {
            mutableState.update { it.copy(message = NOTHING_TO_RESTORE) }
        }
    }

    /** True when the server unlocked the membership for one of [purchases]. */
    private suspend fun deliver(purchases: List<PlayPurchase>): Boolean {
        var unlocked = false
        for (purchase in purchases) {
            val result = try {
                delivery.deliver(purchase)
            } catch (error: ApiError) {
                mutableState.update { it.copy(error = error.userMessage) }
                continue
            }
            // Skipped purchases (still pending, without a product) say nothing.
            if (result is PlayDelivery.Result.Unlocked) {
                unlocked = true
                mutableState.update { it.copy(message = result.update.message) }
                events.changed(message = result.update.message)
            }
        }
        if (unlocked) session.refreshMe()
        return unlocked
    }

    /** Hides the confirmation once the snackbar showed it. */
    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    /** Hides the error once the snackbar showed it. */
    fun dismissError() = mutableState.update { it.copy(error = null) }

    companion object {
        const val PENDING =
            "Dein Kauf wartet noch auf eine Bestätigung, zum Beispiel durch deine Eltern. Sobald Google Play ihn bestätigt, bist du Mitglied."
        const val FAILED = "Der Kauf ist leider nicht gelungen. Bitte versuche es noch einmal."
        const val UNAVAILABLE = "Google Play ist auf diesem Gerät gerade nicht erreichbar. Bitte versuche es später noch einmal."
        const val NOTHING_TO_RESTORE = "Für dein Google-Konto haben wir keine laufende Mitgliedschaft gefunden."

        /** Shown above the offers while the server takes no Google Play purchases (`google.available` false). */
        const val NOT_YET_AVAILABLE =
            "Der Kauf über Google Play ist in der App gerade nicht freigeschaltet. Bitte versuche es später noch einmal."
    }
}
