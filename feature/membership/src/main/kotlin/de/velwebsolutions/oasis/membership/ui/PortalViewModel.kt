package de.velwebsolutions.oasis.membership.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.membership.api.MembershipPayment
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

/** The invoices page: paid contributions, the invoice being downloaded or shown, and snackbars. */
data class PortalUiState(
    val payments: Loadable<List<MembershipPayment>> = Loadable.Idle,
    val message: String? = null,
    val error: String? = null,
    /** The downloaded invoice being shown. */
    val invoiceFile: File? = null,
    /** The payment whose invoice is loading. */
    val loadingInvoice: Int? = null
)

/**
 * /app/mitgliedschaft/portal: paid contributions of a web membership with their invoices (the payment method is
 * changed on the web).
 */
@HiltViewModel
class PortalViewModel @Inject constructor(
    private val repository: MembershipRepository,
    private val files: MembershipFiles
) : ViewModel() {
    private val mutableState = MutableStateFlow(PortalUiState())
    val state: StateFlow<PortalUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** Loads the page again; a failed first load shows the error page, a failed refresh a snackbar. */
    suspend fun load() {
        mutableState.update { it.copy(payments = it.payments.refreshing()) }
        try {
            val payments = repository.payments()
            mutableState.update { it.copy(payments = Loadable.Loaded(payments)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    payments = it.payments.failing(error),
                    error = if (it.payments.valueOrNull != null) error.asApiError().userMessage else it.error
                )
            }
        }
    }

    /** Download the invoice and keep it as a file for the invoice view and the share sheet. */
    fun openInvoice(payment: MembershipPayment) {
        if (mutableState.value.loadingInvoice != null) return
        mutableState.update { it.copy(loadingInvoice = payment.id) }
        viewModelScope.launch {
            try {
                val file = files.invoice(repository.invoice(payment.id), payment.invoiceNumber)
                mutableState.update { it.copy(invoiceFile = file, loadingInvoice = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(error = error.asApiError().userMessage, loadingInvoice = null) }
            }
        }
    }

    /** Closes the invoice sheet. */
    fun closeInvoice() = mutableState.update { it.copy(invoiceFile = null) }

    /** Hides the confirmation once the snackbar showed it. */
    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    /** Hides the error once the snackbar showed it. */
    fun dismissError() = mutableState.update { it.copy(error = null) }
}
