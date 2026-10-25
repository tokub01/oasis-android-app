package de.velwebsolutions.oasis.membership.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.data.MembershipRepository
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
    val error: String? = null
)

/** /app/mitgliedschaft in the app: status and the vouchers bought on the web; reloads whenever the membership changed. */
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
            mutableState.update { it.copy(overview = Loadable.Loaded(overview)) }
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

    /** Back from managing the Apple subscription: tier or cancellation may have changed there, so everything reloads. */
    fun subscriptionManaged() = events.changed()

    /** Hides the confirmation once the snackbar showed it. */
    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    /** Hides the error once the snackbar showed it. */
    fun dismissError() = mutableState.update { it.copy(error = null) }
}
