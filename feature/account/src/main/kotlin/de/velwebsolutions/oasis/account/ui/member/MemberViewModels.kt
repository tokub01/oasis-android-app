package de.velwebsolutions.oasis.account.ui.member

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.api.ComplaintDecision
import de.velwebsolutions.oasis.account.api.GlossaryTerm
import de.velwebsolutions.oasis.account.api.Suspension
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.asApiError
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The lock screen. */
data class SuspensionUiState(
    val state: Loadable<Suspension> = Loadable.Idle,
    val statement: String = "",
    val message: String? = null,
    val error: String? = null,
    /** The refusal of the statement (too long, empty). */
    val statementError: String? = null,
    /** „Warum hältst du die Entscheidung für falsch?“ of the complaint form. */
    val complaintReason: String = "",
    /** The refusal of the complaint's reason (empty, too long). */
    val complaintError: String? = null,
    /** The complaint went out in this session (the server's `submitted_at` only arrives with the next load). */
    val complaintSubmitted: Boolean = false
)

/** The lock screen (/app/gesperrt): accusation, the member's statement and the complaint against the decision. */
@HiltViewModel
class SuspensionViewModel @Inject constructor(private val repository: AccountRepository, private val session: Session) : ViewModel() {
    private val mutableUi = MutableStateFlow(SuspensionUiState())
    val ui: StateFlow<SuspensionUiState> = mutableUi.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun setStatement(value: String) = mutableUi.update { it.copy(statement = value) }

    fun setComplaintReason(value: String) = mutableUi.update { it.copy(complaintReason = value) }

    /** A lifted lock answers 404 (`state` fails with NotFound), which the screen shows as the way back into Oasis. */
    suspend fun load() {
        val firstLoad = ui.value.state.valueOrNull == null
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { repository.suspension() }
            .onSuccess { suspension ->
                mutableUi.update {
                    it.copy(
                        state = Loadable.Loaded(suspension),
                        statement = if (firstLoad) suspension.statement.orEmpty() else it.statement
                    )
                }
            }
            .onFailure { error ->
                mutableUi.update {
                    when {
                        error is ApiError.NotFound -> it.copy(state = Loadable.Failed(ApiError.NotFound))
                        firstLoad -> it.copy(state = it.state.failing(error))
                        else -> it.copy(state = it.state.failing(error), error = error.asApiError().userMessage)
                    }
                }
            }
    }

    suspend fun save() {
        mutableUi.update { it.copy(statementError = null) }
        catching { repository.saveStatement(ui.value.statement) }
            .onSuccess { saved ->
                mutableUi.update { state ->
                    val suspension = state.state.valueOrNull?.copy(statementAt = saved.statementAt, statement = state.statement.trim())
                    state.copy(message = saved.message, state = suspension?.let { Loadable.Loaded(it) } ?: state.state)
                }
            }
            .onFailure { error ->
                val failure = error.asApiError()
                val refusal = failure.message("statement")
                mutableUi.update { if (refusal != null) it.copy(statementError = refusal) else it.copy(error = failure.userMessage) }
            }
    }

    /**
     * „Beschwerde einlegen“ against the decision behind the lock (api.v1.complaints.store with the `decision_id` of
     * `complaint`): the refusal of the reason shows at the field, the throttle and anything else as an error.
     */
    suspend fun complain() {
        val decisionId = ui.value.state.valueOrNull?.complaint?.decisionId ?: return
        mutableUi.update { it.copy(complaintError = null) }
        catching { repository.submitComplaint(decisionId, ui.value.complaintReason) }
            .onSuccess { mutableUi.update { it.copy(complaintSubmitted = true, message = "Danke, deine Beschwerde ist da.") } }
            .onFailure { error ->
                val failure = error.asApiError()
                val refusal = failure.message("reason")
                mutableUi.update {
                    when {
                        failure is ApiError.TooManyRequests -> it.copy(error = TOO_MANY_COMPLAINTS)
                        refusal != null -> it.copy(complaintError = refusal)
                        else -> it.copy(error = failure.userMessage)
                    }
                }
            }
    }

    /** „Weiter zu Oasis“ and pull to refresh: the app leaves the lock screen once `me` is no longer locked. */
    suspend fun refreshMe() = session.refreshMe()

    suspend fun signOut() = session.signOut()

    suspend fun export(): ByteArray = repository.export()

    fun showError(message: String) = mutableUi.update { it.copy(error = message) }

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

    fun dismissError() = mutableUi.update { it.copy(error = null) }
}

/** The throttle of complaints (5 per minute and address), in the web's words. */
internal const val TOO_MANY_COMPLAINTS = "Zu viele Beschwerden. Bitte versuche es in einer Minute erneut."

/** Beschwerde einlegen. */
data class ComplaintUiState(
    val state: Loadable<ComplaintDecision> = Loadable.Idle,
    val reason: String = "",
    val submitted: Boolean = false,
    val error: String? = null,
    /** The refusal of the reason (empty, too long). */
    val reasonError: String? = null
)

/** Beschwerde einlegen (/app/moderation/complaint/{decision}). */
@HiltViewModel(assistedFactory = ComplaintViewModel.Factory::class)
class ComplaintViewModel @AssistedInject constructor(
    @Assisted private val decisionId: Int,
    private val repository: AccountRepository
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(decisionId: Int): ComplaintViewModel
    }

    private val mutableUi = MutableStateFlow(ComplaintUiState())
    val ui: StateFlow<ComplaintUiState> = mutableUi.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun setReason(value: String) = mutableUi.update { it.copy(reason = value) }

    suspend fun load() {
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { repository.complaintDecision(decisionId) }
            .onSuccess { decision -> mutableUi.update { it.copy(state = Loadable.Loaded(decision)) } }
            .onFailure { error -> mutableUi.update { it.copy(state = it.state.failing(error)) } }
    }

    suspend fun submit() {
        mutableUi.update { it.copy(reasonError = null) }
        catching { repository.submitComplaint(decisionId, ui.value.reason) }
            .onSuccess { mutableUi.update { it.copy(submitted = true) } }
            .onFailure { error ->
                val failure = error.asApiError()
                val refusal = failure.message("reason")
                mutableUi.update {
                    when {
                        failure is ApiError.TooManyRequests -> it.copy(error = TOO_MANY_COMPLAINTS)
                        refusal != null -> it.copy(reasonError = refusal)
                        else -> it.copy(error = failure.userMessage)
                    }
                }
            }
    }

    fun dismissError() = mutableUi.update { it.copy(error = null) }
}

/** Begriffe erklärt (/app/begriffe). */
@HiltViewModel
class GlossaryViewModel @Inject constructor(private val repository: AccountRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<Loadable<List<GlossaryTerm>>>(Loadable.Idle)
    val state: StateFlow<Loadable<List<GlossaryTerm>>> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.refreshing() }
        catching { repository.glossary() }
            .onSuccess { terms -> mutableState.value = Loadable.Loaded(terms) }
            .onFailure { error -> mutableState.update { it.failing(error) } }
    }
}
