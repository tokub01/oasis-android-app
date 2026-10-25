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
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseVerifier
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
enum class AlreadyMember { Web, Apple }

/** The checkout: Play's offers, the chosen rhythm, a running purchase and snackbars. */
data class CheckoutUiState(
    val offers: Loadable<List<CheckoutOffer>> = Loadable.Idle,
    /** "monat" or "jahr" (the server's rhythm keys). */
    val rhythm: String = PlayProducts.MONTHLY,
    /** The product being bought, to disable the buttons. */
    val purchasing: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** The member already pays (website or Apple); a second subscription would charge twice. */
    val alreadyMember: AlreadyMember? = null,
    /** False while the server cannot check Google Play purchases: the prices show, buying is switched off. */
    val canPurchase: Boolean = false
)

/**
 * Buying the membership in the app through Google Play (iOS `AppleStoreModel`): the products and prices come from
 * Play, a purchase carries the member's account token, and the purchase goes to the server, which unlocks the
 * membership; it is acknowledged only after that (PlayDelivery).
 */
@HiltViewModel
class PlayCheckoutViewModel @Inject constructor(
    private val repository: MembershipRepository,
    private val gateway: BillingGateway,
    private val delivery: PlayDelivery,
    private val verifier: PlayPurchaseVerifier,
    private val events: MembershipEvents,
    private val session: Session
) : ViewModel() {
    private val mutableState = MutableStateFlow(CheckoutUiState(canPurchase = verifier.isAvailable))
    val state: StateFlow<CheckoutUiState> = mutableState.asStateFlow()
    private var accountToken: String? = null

    init {
        viewModelScope.launch { load() }
    }

    /** Loads the page again; a failed first load shows the error page, a failed refresh a snackbar. */
    suspend fun load() {
        mutableState.update { it.copy(offers = it.offers.refreshing()) }
        try {
            val overview = repository.overview()
            val apple = overview.apple
            accountToken = apple?.accountToken
            val alreadyMember = when {
                overview.state != MembershipOverview.State.Subscription -> null
                apple?.managedByApple == true -> AlreadyMember.Apple
                else -> AlreadyMember.Web
            }
            val offers = if (apple?.available != true || alreadyMember != null) emptyList() else offers()
            mutableState.update { it.copy(offers = Loadable.Loaded(offers), alreadyMember = alreadyMember) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(offers = it.offers.failing(error)) }
        }
    }

    private suspend fun offers(): List<CheckoutOffer> {
        val products = gateway.products(PlayProducts.ids).orEmpty().associateBy(PlayProduct::id)
        return PlayProducts.tiers.map { tier ->
            val byRhythm = PlayProducts.all.filter { it.tier == tier }.associate { it.rhythm to products[it.id] }
            CheckoutOffer(tier, byRhythm[PlayProducts.MONTHLY], byRhythm[PlayProducts.YEARLY])
        }.filter { it.monthly != null || it.yearly != null }
    }

    /** Shows the monthly or yearly prices. */
    fun selectRhythm(rhythm: String) = mutableState.update { it.copy(rhythm = rhythm) }

    /** "Abonnieren". Returns true once the server unlocked the membership. */
    suspend fun purchase(activity: Activity?, product: PlayProduct): Boolean {
        if (!verifier.isAvailable || mutableState.value.purchasing != null) return false
        mutableState.update { it.copy(error = null, purchasing = product.id) }
        try {
            return when (val outcome = gateway.purchase(activity, product, accountToken)) {
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
        val state = mutableState.value
        if (!restored && state.error == null && state.message == null) {
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
            // Skipped purchases (pending, not ours) say nothing.
            if (result is PlayDelivery.Result.Unlocked) {
                unlocked = true
                mutableState.update { it.copy(message = result.update.message) }
                events.changed(message = result.update.message)
            } else if (result == PlayDelivery.Result.AwaitingServer) {
                mutableState.update { it.copy(message = AWAITING_SERVER) }
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
        const val AWAITING_SERVER =
            "Dein Kauf ist bei Google Play eingegangen, Oasis kann ihn aber noch nicht bestätigen. Deine Mitgliedschaft ist deshalb " +
                "noch nicht freigeschaltet. Bestätigt Oasis den Kauf nicht innerhalb von drei Tagen, erstattet Google Play den Betrag " +
                "automatisch."

        /** Shown above the offers while the server cannot check Google Play purchases. */
        const val NOT_YET_AVAILABLE =
            "Der Kauf über Google Play ist in der App noch nicht freigeschaltet, weil Oasis solche Käufe noch nicht bestätigen kann. " +
                "Bitte versuche es später noch einmal."
    }
}
