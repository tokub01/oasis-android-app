package de.velwebsolutions.oasis.account.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.api.Passkey
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.account.platform.PasskeyAuthenticator
import de.velwebsolutions.oasis.account.platform.PasskeyFailure
import de.velwebsolutions.oasis.account.platform.credentialObject
import de.velwebsolutions.oasis.core.di.DeviceName
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
import kotlinx.serialization.json.JsonObject

/** A passkey Credential Manager just created, waiting for its name before it goes to the server. */
data class NewPasskey(val challenge: String, val credential: JsonObject)

/** The „Passkeys“ block of /app/einstellungen. */
data class PasskeysUiState(
    val state: Loadable<List<Passkey>> = Loadable.Idle,
    /** The name dialog after creating a passkey. */
    val naming: NewPasskey? = null,
    /** The rename dialog. */
    val renaming: Passkey? = null,
    /** The confirmation before removing. */
    val removing: Passkey? = null,
    /** Field errors of the name dialogs (`name`). */
    val fieldErrors: Map<String, String> = emptyMap(),
    val message: String? = null,
    val error: String? = null
)

/**
 * Passkeys of the account: list, add through Credential Manager (then a name, suggested: this device), rename and
 * remove. Adding and removing need a recent password confirmation ([PasswordGate]); afterwards the action continues by
 * itself. The screen attaches the [PasskeyAuthenticator] of its activity while it is shown.
 */
@HiltViewModel
class PasskeysViewModel @Inject constructor(private val repository: AccountRepository, deviceName: DeviceName) : ViewModel() {
    private val mutableUi = MutableStateFlow(PasskeysUiState())
    val ui: StateFlow<PasskeysUiState> = mutableUi.asStateFlow()

    private val gate = PasswordGate(repository)

    /** „Kurz bestätigen“ for a waiting action. */
    val prompt: StateFlow<PasswordPrompt?> = gate.prompt

    /** The name the dialog suggests for a new passkey. */
    val suggestedName: String = deviceName.value

    private var authenticator: PasskeyAuthenticator? = null

    init {
        viewModelScope.launch { load() }
    }

    /** The activity's passkeys, while its screen is shown. */
    fun attach(passkeys: PasskeyAuthenticator) {
        authenticator = passkeys
    }

    /** The screen went away; a later retry waits for the next one. */
    fun detach(passkeys: PasskeyAuthenticator) {
        if (authenticator === passkeys) authenticator = null
    }

    suspend fun load() {
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { repository.passkeys() }
            .onSuccess { list -> mutableUi.update { it.copy(state = Loadable.Loaded(list)) } }
            .onFailure { error -> mutableUi.update { it.copy(state = it.state.failing(error)) } }
    }

    /** „Passkey hinzufügen“: creation options (423 possible), the system sheet, then the name dialog. */
    suspend fun add() {
        mutableUi.update { it.copy(error = null) }
        gate.run(::create, ::handle)
    }

    private suspend fun create() {
        val ceremony = repository.passkeyOptions()
        val passkeys = authenticator ?: return
        try {
            val attestation = passkeys.register(ceremony.options.toString())
            mutableUi.update { it.copy(naming = NewPasskey(ceremony.challenge, credentialObject(attestation)), fieldErrors = emptyMap()) }
        } catch (failure: PasskeyFailure) {
            mutableUi.update { it.copy(error = if (failure is PasskeyFailure.Failed) REGISTRATION_FAILED else failure.userMessage) }
        }
    }

    /** Sends the new passkey with [name] (blank: the server names it after the password manager). */
    suspend fun saveNew(name: String) {
        val pending = ui.value.naming ?: return
        gate.run({
            repository.addPasskey(pending.challenge, pending.credential, name.trim().ifEmpty { null })
            mutableUi.update { it.copy(naming = null, message = ADDED) }
            load()
        }, ::handle)
    }

    fun startRename(passkey: Passkey) = mutableUi.update { it.copy(renaming = passkey, fieldErrors = emptyMap()) }

    fun cancelRename() = mutableUi.update { it.copy(renaming = null, fieldErrors = emptyMap()) }

    suspend fun rename(name: String) {
        val passkey = ui.value.renaming ?: return
        gate.run({
            repository.renamePasskey(passkey.id, name.trim())
            mutableUi.update { it.copy(renaming = null, message = RENAMED) }
            load()
        }, ::handle)
    }

    fun askRemove(passkey: Passkey) = mutableUi.update { it.copy(removing = passkey) }

    fun cancelRemove() = mutableUi.update { it.copy(removing = null) }

    /** Removes [passkey] (the one the confirmation asked about; the dialog closes itself first). */
    suspend fun remove(passkey: Passkey) {
        mutableUi.update { it.copy(removing = null) }
        gate.run({
            repository.removePasskey(passkey.id)
            mutableUi.update { it.copy(message = REMOVED) }
            load()
        }, ::handle)
    }

    suspend fun confirmPassword(password: String): Boolean = gate.confirm(password)

    fun cancelPasswordConfirmation() = gate.cancel()

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

    fun dismissError() = mutableUi.update { it.copy(error = null) }

    /**
     * A refused name stays at the field of the open dialog. Everything else (an expired ceremony, a passkey that is
     * gone) closes the dialogs and goes to the snackbar.
     */
    private fun handle(failure: ApiError) {
        val name = failure.message("name")
        mutableUi.update {
            if (name != null) {
                it.copy(fieldErrors = mapOf("name" to name))
            } else {
                it.copy(error = failure.userMessage, naming = null, renaming = null)
            }
        }
    }

    /** The web's texts (Livewire Account\Settings, resources/js/passkeys.js). */
    companion object {
        const val ADDED = "Der Passkey ist gespeichert. Du kannst dich ab jetzt damit anmelden."
        const val RENAMED = "Der Name des Passkeys ist gespeichert."
        const val REMOVED = "Der Passkey ist entfernt."

        /** The device could not create the passkey (the sign-in says [PasskeyFailure.Failed]'s text instead). */
        const val REGISTRATION_FAILED = "Dein Gerät konnte keinen Passkey anlegen. Bitte versuche es noch einmal."
    }
}
