package de.velwebsolutions.oasis.account.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.api.AccountSettings
import de.velwebsolutions.oasis.account.api.SettingsChange
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.account.data.needsPasswordConfirmation
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

/** Actions that need the password first. */
enum class ProtectedAction { EnableTwoFactor, ConfirmTwoFactor, RegenerateCodes, DisableTwoFactor, DeleteAccount }

/** What /app/einstellungen shows. */
data class SettingsUiState(
    val state: Loadable<AccountSettings> = Loadable.Idle,
    val displayName: String = "",
    val email: String = "",
    val currentPassword: String = "",
    val newPassword: String = "",
    val newPasswordConfirmation: String = "",
    val twoFactorCode: String = "",
    val deleteConfirmed: Boolean = false,
    /** Confirmation of the last change. */
    val message: String? = null,
    /** An error not tied to a field. */
    val error: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    /** Shown once after confirming two-factor or creating new codes. */
    val recoveryCodes: List<String>? = null,
    /** Waiting for the password confirmation sheet. */
    val pendingAction: ProtectedAction? = null,
    /** True once the account is gone; the screen then signs the device out (also after a resumed deletion). */
    val accountDeleted: Boolean = false
) {
    val settings: AccountSettings? get() = state.valueOrNull
}

/**
 * /app/einstellungen: profile, password, two-factor sign-in and account deletion. Two-factor changes and the deletion
 * need a recent password confirmation of this device; when the server answers 423 the action waits in
 * [SettingsUiState.pendingAction], the screen asks for the password and runs it again afterwards (like the web's
 * resume).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(private val repository: AccountRepository, private val session: Session) : ViewModel() {
    private val mutableUi = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = mutableUi.asStateFlow()

    /** Whether the welcome tour link shows (verified, not locked). */
    val canSeeWelcome: Boolean get() = session.me?.let { it.emailVerified && !it.isSuspended } ?: false

    init {
        viewModelScope.launch { load() }
    }

    fun edit(change: (SettingsUiState) -> SettingsUiState) = mutableUi.update(change)

    suspend fun load() {
        val firstLoad = ui.value.settings == null
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { apply(repository.settings(), fillFields = firstLoad) }
            .onFailure { error ->
                mutableUi.update {
                    it.copy(state = it.state.failing(error), error = if (!firstLoad) error.asApiError().userMessage else it.error)
                }
            }
    }

    /** „Profil speichern“: the session gets the refreshed member (a new address needs verification). */
    suspend fun saveProfile() {
        mutableUi.update { it.copy(fieldErrors = emptyMap()) }
        catching { repository.updateProfile(ui.value.displayName, ui.value.email) }
            .onSuccess { change ->
                mutableUi.update { it.copy(displayName = change.data.displayName, email = change.data.email, message = change.message) }
                load()
                session.update(change.data)
            }
            .onFailure { error ->
                handle(error.asApiError())
            }
    }

    /** „Passwort ändern“; the fields are cleared in every case, like the web. */
    suspend fun changePassword() {
        val current = ui.value
        mutableUi.update { it.copy(fieldErrors = emptyMap()) }
        val result = try {
            catching { repository.updatePassword(current.currentPassword, current.newPassword, current.newPasswordConfirmation) }
        } finally {
            mutableUi.update { it.copy(currentPassword = "", newPassword = "", newPasswordConfirmation = "") }
        }
        result
            .onSuccess { message ->
                mutableUi.update { it.copy(message = message) }
            }
            .onFailure { error ->
                handle(error.asApiError())
            }
    }

    /** The „Kurz bestätigen“ sheet; afterwards the waiting action runs again. Returns false on a wrong password. */
    suspend fun confirmPassword(password: String): Boolean {
        mutableUi.update { it.copy(fieldErrors = it.fieldErrors - "password") }
        catching { apply(repository.confirmPassword(password), fillFields = false) }
            .onFailure { error ->
                val failure = error.asApiError()
                mutableUi.update {
                    it.copy(fieldErrors = it.fieldErrors + ("password" to (failure.message("password") ?: failure.userMessage)))
                }
                return false
            }
        val action = ui.value.pendingAction
        if (action != null) {
            mutableUi.update { it.copy(pendingAction = null) }
            run(action)
        }
        return true
    }

    fun cancelPasswordConfirmation() = mutableUi.update { it.copy(pendingAction = null) }

    /** Runs a protected action; true when the account was deleted (the session ends then). */
    suspend fun run(action: ProtectedAction): Boolean {
        mutableUi.update { it.copy(fieldErrors = emptyMap()) }
        catching {
            when (action) {
                ProtectedAction.EnableTwoFactor -> applyChange(repository.enableTwoFactor())

                ProtectedAction.ConfirmTwoFactor -> {
                    applyChange(repository.confirmTwoFactor(ui.value.twoFactorCode.trim()))
                    mutableUi.update { it.copy(twoFactorCode = "") }
                }

                ProtectedAction.RegenerateCodes -> applyChange(repository.regenerateRecoveryCodes())

                ProtectedAction.DisableTwoFactor -> applyChange(repository.disableTwoFactor())

                ProtectedAction.DeleteAccount -> {
                    repository.deleteAccount(ui.value.deleteConfirmed)
                    mutableUi.update { it.copy(accountDeleted = true) }
                }
            }
        }.onFailure { error ->
            val failure = error.asApiError()
            if (failure.needsPasswordConfirmation) {
                mutableUi.update { it.copy(pendingAction = action) }
            } else {
                handle(failure)
            }
        }
        return ui.value.accountDeleted
    }

    suspend fun signOut() = session.signOut()

    suspend fun export(): ByteArray = repository.export()

    fun showError(message: String) = mutableUi.update { it.copy(error = message) }

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

    fun dismissError() = mutableUi.update { it.copy(error = null) }

    private fun applyChange(change: SettingsChange) {
        mutableUi.update { it.copy(message = change.message, recoveryCodes = change.recoveryCodes) }
        apply(change.data, fillFields = false)
    }

    private fun apply(settings: AccountSettings, fillFields: Boolean) {
        mutableUi.update {
            if (fillFields) {
                it.copy(state = Loadable.Loaded(settings), displayName = settings.displayName, email = settings.email)
            } else {
                it.copy(state = Loadable.Loaded(settings))
            }
        }
    }

    private fun handle(failure: ApiError) {
        val fields = failure.fieldMessages
        mutableUi.update { it.copy(fieldErrors = fields, error = if (fields.isEmpty()) failure.userMessage else it.error) }
    }
}
