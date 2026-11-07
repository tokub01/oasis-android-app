package de.velwebsolutions.oasis.membership.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.MembershipTier
import de.velwebsolutions.oasis.membership.api.TierChangeInput
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.data.MembershipRepository
import de.velwebsolutions.oasis.membership.data.TermsObjectionRefused
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The overview: the membership and another screen's outcome for the snackbar. */
data class OverviewUiState(
    val overview: Loadable<MembershipOverview> = Loadable.Idle,
    /** Another screen's outcome (`role="status"`), shown as toast. */
    val message: String? = null,
    /** A failed refresh or another screen's error. */
    val error: String? = null,
    /** While the objection to the announced AGB change or its withdrawal is on its way: the card's button waits. */
    val termsBusy: Boolean = false,
    // „Beitragsstufe ändern“ (portal.blade.php): the chosen tier, the Mäzen amount, and the server's field errors.
    /** The tier chosen for the change; starts at the current one once the overview loaded. */
    val tier: String = "",
    /** The monthly Mäzen amount in whole euros, as typed. */
    val amount: String = "",
    /** The server's errors for `tier` and `amount` (TierChangeController's validation), by the wire name. */
    val tierErrors: Map<String, String> = emptyMap(),
    /** While the tier change is on its way: the card's button waits. */
    val tierBusy: Boolean = false
) {
    /** The Mäzen amount is asked only for that tier (portal.blade.php `@if ($stufe === 'maezen')`). */
    val asksForAmount: Boolean get() = tier == MembershipTier.MAEZEN
}

/**
 * /app/mitgliedschaft in the app: status, the vouchers bought on the web, the objection to an announced change of the
 * AGB (AGB § 19) and the tier change of a Mollie membership (AGB § 5, the portal page on the web); reloads whenever the
 * membership changed.
 */
@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val repository: MembershipRepository,
    private val events: MembershipEvents
) : ViewModel() {
    private val mutableState = MutableStateFlow(OverviewUiState())
    val state: StateFlow<OverviewUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            events.version.collect {
                val pending = events.take()
                if (pending.message != null || pending.error != null) {
                    mutableState.update { it.copy(message = pending.message, error = pending.error) }
                }
                load()
            }
        }
    }

    /** Loads the page again; a failed first load shows the error page, a failed refresh a snackbar. */
    suspend fun load() {
        mutableState.update { it.copy(overview = it.overview.refreshing()) }
        try {
            val overview = repository.overview()
            mutableState.update {
                it.copy(overview = Loadable.Loaded(overview), tier = it.tier.ifEmpty { overview.subscription?.tier.orEmpty() })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    overview = it.overview.failing(error),
                    error = if (it.overview.valueOrNull != null) error.asApiError().userMessage else it.error
                )
            }
        }
    }

    /** Back from managing the store subscription (App Store or Play Store): tier or cancellation may have changed there, so everything reloads. */
    fun subscriptionManaged() = events.changed()

    /** „Ich widerspreche dieser Änderung“ (AGB § 19): the server records the objection, the card then shows it. */
    suspend fun objectToTerms() = termsObjection(repository::objectToTerms)

    /** „Widerspruch zurücknehmen“: the new version applies to this contract again. */
    suspend fun withdrawTermsObjection() = termsObjection(repository::withdrawTermsObjection)

    /**
     * Sends the objection or its withdrawal and shows the server's answer: the overview it returns and its confirmation
     * as toast. A refusal (objected already, nothing to withdraw, no announcement any more) is shown as error, and the
     * overview reloads so the card shows the state the server knows.
     */
    private suspend fun termsObjection(call: suspend () -> Confirmed<MembershipOverview>) {
        mutableState.update { it.copy(termsBusy = true) }
        try {
            val answer = call()
            mutableState.update { it.copy(overview = Loadable.Loaded(answer.data), message = answer.message) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: TermsObjectionRefused) {
            mutableState.update { it.copy(error = error.message) }
            load()
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.asApiError().userMessage) }
        } finally {
            mutableState.update { it.copy(termsBusy = false) }
        }
    }

    /** The tier picked in „Beitragsstufe ändern“; the server's message for `tier` goes with it. */
    fun setTier(tier: String) = mutableState.update { it.copy(tier = tier, tierErrors = it.tierErrors - "tier") }

    /** The Mäzen amount as typed; the server's message for `amount` goes with it. */
    fun setAmount(amount: String) = mutableState.update { it.copy(amount = amount, tierErrors = it.tierErrors - "amount") }

    /**
     * „Stufe ändern“, confirmed (AGB § 5): the server's overview replaces the shown one (the tier changed at once) and
     * its `message` names the new contribution and when it applies, as toast. A refusal of the rules (not running, same
     * tier: a 422 with a `reason` and no field errors) means the card is out of date, so the overview reloads before
     * the refusal shows; the store's hint (409) and Mollie's failure (503) show as they are; field errors stay at
     * their fields.
     */
    suspend fun changeTier() {
        val current = mutableState.value
        mutableState.update { it.copy(tierBusy = true, tierErrors = emptyMap()) }
        // Whole euros only for Mäzen; what is not a number goes to the server as missing, whose message names the rule.
        val amount = if (current.asksForAmount) current.amount.trim().toIntOrNull() else null
        try {
            val answer = repository.changeTier(TierChangeInput(current.tier, amount))
            mutableState.update { it.copy(overview = Loadable.Loaded(answer.data), amount = "", error = null, message = answer.message) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiError.Validation) {
            val fields = error.fieldMessages
            if (fields.isEmpty()) {
                mutableState.update { it.copy(error = error.userMessage) }
                load()
            } else {
                mutableState.update { it.copy(tierErrors = fields) }
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.asApiError().userMessage) }
        } finally {
            mutableState.update { it.copy(tierBusy = false) }
        }
    }

    /** Hides the confirmation once the snackbar showed it. */
    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    /** Hides the error once the snackbar showed it. */
    fun dismissError() = mutableState.update { it.copy(error = null) }
}
