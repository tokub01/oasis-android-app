package de.velwebsolutions.oasis.account.ui.member

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.api.ComplaintDecision
import de.velwebsolutions.oasis.account.api.GlossaryTerm
import de.velwebsolutions.oasis.account.api.OpenVote
import de.velwebsolutions.oasis.account.api.Suspension
import de.velwebsolutions.oasis.account.api.VotesOverview
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.asApiError
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
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
    val statementError: String? = null
)

/** The lock screen (/app/gesperrt): accusation and the member's statement. */
@HiltViewModel
class SuspensionViewModel @Inject constructor(private val repository: AccountRepository, private val session: Session) : ViewModel() {
    private val mutableUi = MutableStateFlow(SuspensionUiState())
    val ui: StateFlow<SuspensionUiState> = mutableUi.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun setStatement(value: String) = mutableUi.update { it.copy(statement = value) }

    /** A lifted lock answers 404 (`state` fails with NotFound), which the screen shows as the way back into Oasis. */
    suspend fun load() {
        val firstLoad = ui.value.state.valueOrNull == null
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        try {
            val suspension = repository.suspension()
            mutableUi.update {
                it.copy(state = Loadable.Loaded(suspension), statement = if (firstLoad) suspension.statement.orEmpty() else it.statement)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ApiError.NotFound) {
            mutableUi.update { it.copy(state = Loadable.Failed(ApiError.NotFound)) }
        } catch (error: Exception) {
            mutableUi.update {
                it.copy(state = it.state.failing(error), error = if (!firstLoad) error.asApiError().userMessage else it.error)
            }
        }
    }

    suspend fun save() {
        mutableUi.update { it.copy(statementError = null) }
        try {
            val saved = repository.saveStatement(ui.value.statement)
            mutableUi.update { state ->
                val suspension = state.state.valueOrNull?.copy(statementAt = saved.statementAt, statement = state.statement.trim())
                state.copy(message = saved.message, state = suspension?.let { Loadable.Loaded(it) } ?: state.state)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = error.asApiError()
            val refusal = failure.message("statement")
            mutableUi.update { if (refusal != null) it.copy(statementError = refusal) else it.copy(error = failure.userMessage) }
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

/** Widerspruch einlegen. */
data class ComplaintUiState(
    val state: Loadable<ComplaintDecision> = Loadable.Idle,
    val reason: String = "",
    val submitted: Boolean = false,
    val error: String? = null,
    /** The refusal of the reason (empty, too long). */
    val reasonError: String? = null
)

/** Widerspruch einlegen (/app/moderation/complaint/{decision}). */
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
        try {
            val decision = repository.complaintDecision(decisionId)
            mutableUi.update { it.copy(state = Loadable.Loaded(decision)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableUi.update { it.copy(state = it.state.failing(error)) }
        }
    }

    suspend fun submit() {
        mutableUi.update { it.copy(reasonError = null) }
        try {
            repository.submitComplaint(decisionId, ui.value.reason)
            mutableUi.update { it.copy(submitted = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = error.asApiError()
            val refusal = failure.message("reason")
            mutableUi.update {
                when {
                    failure is ApiError.TooManyRequests -> it.copy(
                        error = "Zu viele Beschwerden. Bitte versuche es in einer Minute erneut."
                    )

                    refusal != null -> it.copy(reasonError = refusal)

                    else -> it.copy(error = failure.userMessage)
                }
            }
        }
    }

    fun dismissError() = mutableUi.update { it.copy(error = null) }
}

/** Abstimmungen. */
data class VotesUiState(
    val state: Loadable<VotesOverview> = Loadable.Idle,
    /** Vote id → chosen option id. */
    val choices: Map<Int, Int> = emptyMap(),
    val message: String? = null,
    val error: String? = null,
    /** Vote id → the refusal shown at that vote. */
    val errors: Map<Int, String> = emptyMap()
)

/** Abstimmungen (/app/abstimmungen). */
@HiltViewModel
class VotesViewModel @Inject constructor(private val repository: AccountRepository) : ViewModel() {
    private val mutableUi = MutableStateFlow(VotesUiState())
    val ui: StateFlow<VotesUiState> = mutableUi.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun choose(voteId: Int, optionId: Int) = mutableUi.update { it.copy(choices = it.choices + (voteId to optionId)) }

    suspend fun load() {
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        try {
            val votes = repository.votes()
            mutableUi.update { it.copy(state = Loadable.Loaded(votes)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableUi.update {
                val failing = it.state.failing(error)
                it.copy(state = failing, error = if (failing.valueOrNull != null) error.asApiError().userMessage else it.error)
            }
        }
    }

    suspend fun cast(vote: OpenVote) {
        mutableUi.update { it.copy(errors = it.errors - vote.id) }
        try {
            val message = repository.cast(vote.id, ui.value.choices[vote.id])
            mutableUi.update { it.copy(message = message) }
            load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = error.asApiError()
            mutableUi.update { it.copy(errors = it.errors + (vote.id to (failure.message("option") ?: failure.userMessage))) }
        }
    }

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

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
        try {
            mutableState.value = Loadable.Loaded(repository.glossary())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.failing(error) }
        }
    }
}
