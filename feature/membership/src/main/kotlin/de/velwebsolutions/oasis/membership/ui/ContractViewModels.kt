package de.velwebsolutions.oasis.membership.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.membership.api.CancellationDetails
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.WithdrawalDetails
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.data.MembershipFiles
import de.velwebsolutions.oasis.membership.data.MembershipRepository
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A contract page: the contract, the data export once fetched, and an error for the snackbar. */
data class ContractUiState<D>(
    val details: Loadable<D> = Loadable.Idle,
    /** The data export, ready for the share sheet. */
    val exportFile: File? = null,
    val error: String? = null
)

/**
 * The confirmation pages that end a contract: /app/mitgliedschaft/kuendigen (§ 312k BGB, [CancellationDetails]) and
 * /app/mitgliedschaft/widerrufen (§§ 355 ff. BGB, [WithdrawalDetails]). Both show the contract, end it with one button
 * and hand the outcome to the overview like the web's flash messages; both offer the data export.
 */
abstract class ContractViewModel<D>(
    protected val repository: MembershipRepository,
    private val files: MembershipFiles,
    private val events: MembershipEvents,
    private val session: Session
) : ViewModel() {
    private val mutableState = MutableStateFlow(ContractUiState<D>())
    val state: StateFlow<ContractUiState<D>> = mutableState.asStateFlow()

    /** Called from the base class' constructor: implementations may only use what the base class holds. */
    protected abstract suspend fun details(): D

    /** Ends the contract (cancel or withdraw) and returns the server's confirmation. */
    protected abstract suspend fun end(): Confirmed<MembershipOverview>

    init {
        viewModelScope.launch { load() }
    }

    /** Loads the page again; a failed first load shows the error page, a failed refresh a snackbar. */
    suspend fun load() {
        mutableState.update { it.copy(details = Loadable.Loading) }
        try {
            val details = details()
            mutableState.update { it.copy(details = Loadable.Loaded(details)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(details = Loadable.Failed(error.asApiError())) }
        }
    }

    /** "Jetzt kündigen" / "Jetzt widerrufen": the overview shows the server's confirmation or error. */
    suspend fun confirm() {
        try {
            events.changed(message = end().message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            events.changed(error = error.asApiError().userMessage)
        }
        session.refreshMe()
    }

    /** "Daten herunterladen": the ZIP as a file for the share sheet. */
    suspend fun prepareExport() {
        try {
            val file = files.export(repository.export())
            mutableState.update { it.copy(exportFile = file) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.asApiError().userMessage) }
        }
    }

    /** Hides the error once the snackbar showed it. */
    fun dismissError() = mutableState.update { it.copy(error = null) }
}

/** /app/mitgliedschaft/kuendigen: the § 312k BGB confirmation. */
@HiltViewModel
class CancelViewModel @Inject constructor(
    repository: MembershipRepository,
    files: MembershipFiles,
    events: MembershipEvents,
    session: Session
) : ContractViewModel<CancellationDetails>(repository, files, events, session) {
    override suspend fun details() = repository.cancellation()

    override suspend fun end() = repository.cancel()
}

/** /app/mitgliedschaft/widerrufen: the withdrawal within 14 days. */
@HiltViewModel
class WithdrawViewModel @Inject constructor(
    repository: MembershipRepository,
    files: MembershipFiles,
    events: MembershipEvents,
    session: Session
) : ContractViewModel<WithdrawalDetails>(repository, files, events, session) {
    override suspend fun details() = repository.withdrawal()

    override suspend fun end() = repository.withdraw()
}
