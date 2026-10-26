package de.velwebsolutions.oasis.account.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.api.PhoneNumberStatus
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The „Handynummer“ block of /app/einstellungen. */
data class PhoneNumberUiState(
    val state: Loadable<PhoneNumberStatus> = Loadable.Idle,
    val number: String = "",
    val code: String = "",
    /** „Ändern“: the number field shows although a number is saved or waiting. */
    val editing: Boolean = false,
    /** The confirmation before deleting the number. */
    val confirmingRemoval: Boolean = false,
    /** `phone_number` and `code`. */
    val fieldErrors: Map<String, String> = emptyMap(),
    val message: String? = null,
    val error: String? = null
) {
    val status: PhoneNumberStatus? get() = state.valueOrNull

    /** The number field: SMS works and there is no number yet, or the member wants another one. */
    val showsNumberField: Boolean
        get() = status?.let { it.smsAvailable && (editing || (it.phoneNumber == null && it.pending == null)) } ?: false
}

/**
 * The optional mobile number for sign-in codes by SMS: type it, get a code by SMS, confirm it; change or delete it.
 * Sending a code and deleting need a recent password confirmation ([PasswordGate]); afterwards the action continues.
 */
@HiltViewModel
class PhoneNumberViewModel @Inject constructor(private val repository: AccountRepository) : ViewModel() {
    private val mutableUi = MutableStateFlow(PhoneNumberUiState())
    val ui: StateFlow<PhoneNumberUiState> = mutableUi.asStateFlow()

    private val gate = PasswordGate(repository)

    /** „Kurz bestätigen“ for a waiting action. */
    val prompt: StateFlow<PasswordPrompt?> = gate.prompt

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { repository.phone() }
            .onSuccess(::show)
            .onFailure { error -> mutableUi.update { it.copy(state = it.state.failing(error)) } }
    }

    fun setNumber(value: String) = mutableUi.update { it.copy(number = value) }

    fun setCode(value: String) = mutableUi.update { it.copy(code = value) }

    fun startEditing() = mutableUi.update { it.copy(editing = true, fieldErrors = emptyMap()) }

    fun cancelEditing() = mutableUi.update { it.copy(editing = false, number = "", fieldErrors = emptyMap()) }

    /** „Code schicken“: an SMS to the typed number; the number counts only once its code is confirmed. */
    suspend fun sendCode() {
        val number = ui.value.number.trim()
        mutableUi.update { it.copy(fieldErrors = emptyMap(), error = null) }
        gate.run({
            val status = repository.requestPhoneCode(number)
            show(status)
            mutableUi.update { it.copy(editing = false, number = "", code = "", message = CODE_SENT) }
        }, ::handle)
    }

    /** „Nummer bestätigen“ with the six digits of the SMS. */
    suspend fun confirm() {
        val code = ui.value.code.trim()
        mutableUi.update { it.copy(fieldErrors = emptyMap(), error = null) }
        gate.run({
            show(repository.confirmPhone(code))
            mutableUi.update { it.copy(code = "", message = CONFIRMED) }
        }, ::handle)
    }

    fun askRemoval() = mutableUi.update { it.copy(confirmingRemoval = true) }

    fun cancelRemoval() = mutableUi.update { it.copy(confirmingRemoval = false) }

    /** „Nummer löschen“: sign-in codes come by e-mail only again. */
    suspend fun remove() {
        mutableUi.update { it.copy(confirmingRemoval = false, error = null) }
        gate.run({
            repository.removePhone()
            mutableUi.update { it.copy(editing = false, message = REMOVED) }
            load()
        }, ::handle)
    }

    suspend fun confirmPassword(password: String): Boolean = gate.confirm(password)

    fun cancelPasswordConfirmation() = gate.cancel()

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

    fun dismissError() = mutableUi.update { it.copy(error = null) }

    private fun show(status: PhoneNumberStatus) = mutableUi.update { it.copy(state = Loadable.Loaded(status)) }

    /** Refusals of a field stay at the field (`phone_number`, `code`); everything else goes to the snackbar. */
    private fun handle(failure: ApiError) {
        val fields = failure.fieldMessages
        mutableUi.update { if (fields.isEmpty()) it.copy(error = failure.userMessage) else it.copy(fieldErrors = fields) }
    }

    /** The web's texts (Livewire Account\Settings). */
    companion object {
        const val CODE_SENT = "Wir haben dir einen Code per SMS geschickt. Er gilt 10 Minuten."
        const val CONFIRMED = "Deine Handynummer ist bestätigt. Du kannst dir Anmeldecodes jetzt auch per SMS schicken lassen."
        const val REMOVED = "Deine Handynummer ist gelöscht."
    }
}
