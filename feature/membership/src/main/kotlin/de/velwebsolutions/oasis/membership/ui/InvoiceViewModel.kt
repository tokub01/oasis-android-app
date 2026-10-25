package de.velwebsolutions.oasis.membership.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.membership.data.MembershipFiles
import de.velwebsolutions.oasis.membership.data.MembershipRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The invoice page: the downloaded file and whether its sheet is open. */
data class InvoiceUiState(val file: Loadable<File> = Loadable.Idle, val preview: File? = null)

/** /app/mitgliedschaft/rechnungen/{id} opened from a link: downloads the invoice and shows it right away. */
@HiltViewModel(assistedFactory = InvoiceViewModel.Factory::class)
class InvoiceViewModel @AssistedInject constructor(
    @Assisted val paymentId: Int,
    private val repository: MembershipRepository,
    private val files: MembershipFiles
) : ViewModel() {
    /** Creates the view model for one id (Hilt assisted injection). */
    @AssistedFactory
    interface Factory {
        fun create(paymentId: Int): InvoiceViewModel
    }

    private val mutableState = MutableStateFlow(InvoiceUiState())
    val state: StateFlow<InvoiceUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** Loads the page again; a failed first load shows the error page, a failed refresh a snackbar. */
    suspend fun load() {
        mutableState.update { it.copy(file = Loadable.Loading) }
        try {
            val file = files.invoice(repository.invoice(paymentId), "OAS-$paymentId")
            mutableState.update { InvoiceUiState(Loadable.Loaded(file), preview = file) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(file = Loadable.Failed(error.asApiError())) }
        }
    }

    /** Opens the invoice sheet again. */
    fun showPreview() = mutableState.update { it.copy(preview = it.file.valueOrNull) }

    /** Closes the invoice sheet. */
    fun closePreview() = mutableState.update { it.copy(preview = null) }
}
