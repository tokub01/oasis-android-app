package de.velwebsolutions.oasis.chat.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.chat.api.ChatSettings
import de.velwebsolutions.oasis.chat.api.DeviceUnlockRow
import de.velwebsolutions.oasis.chat.core.Biometrics
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatInput
import de.velwebsolutions.oasis.chat.core.ChatKeyringException
import de.velwebsolutions.oasis.chat.core.describeChatError
import de.velwebsolutions.oasis.chat.data.SettingValue
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** What the chat settings show. */
data class ChatSettingsState(
    val settings: ChatSettings? = null,
    /** A switch or choice is being saved: the controls wait for the answer. */
    val savingSetting: Boolean = false,
    val devices: List<DeviceUnlockRow> = emptyList(),
    /** The id of this device's row, if it is set up here. */
    val ownDeviceId: String? = null,
    val passphrase: String = "",
    val confirmation: String = "",
    val backupError: String? = null,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val hasPassphrase: Boolean = false,
    val deviceAvailable: Boolean = false
) {
    /** "Chat-Passphrase ändern" or "Reserve-Passphrase festlegen (optional)". */
    val backupTitle: String get() = if (hasPassphrase) "Chat-Passphrase ändern" else "Reserve-Passphrase festlegen (optional)"

    /** "Passphrase abschalten" needs a passphrase and at least one device. */
    val canRemovePassphrase: Boolean get() = hasPassphrase && devices.isNotEmpty()

    val thisDeviceIsSetUp: Boolean get() = ownDeviceId != null

    /** Removing the last device without a passphrase means new keys next time – the screen confirms first. */
    val removalNeedsConfirmation: Boolean get() = !hasPassphrase && devices.size == 1
}

/**
 * The chat settings below the web's list screen (ui/conversation-list.js, ui/device-unlock.js; iOS `ChatSettingsModel`):
 * "Wer darf dir schreiben?", friend requests, biometric devices and the chat passphrase (set, change, switch off).
 */
@HiltViewModel
class ChatSettingsViewModel @Inject constructor(context: ChatContext) : ChatViewModel(context) {
    private val mutableState = MutableStateFlow(ChatSettingsState(deviceAvailable = context.devices.isAvailable))
    val state: StateFlow<ChatSettingsState> = mutableState.asStateFlow()

    init {
        refreshPassphraseFlag()
    }

    private fun refreshPassphraseFlag() = mutableState.update { it.copy(hasPassphrase = vault.storedIdentity?.lockedIdentity != null) }

    /** The new passphrase. */
    fun setPassphrase(value: String) = mutableState.update { it.copy(passphrase = value) }

    /** The repeated passphrase. */
    fun setConfirmation(value: String) = mutableState.update { it.copy(confirmation = value) }

    /** The confirmation toast was shown. */
    fun dismissStatus() = mutableState.update { it.copy(status = null) }

    /** The error toast was shown. */
    fun dismissError() = mutableState.update { it.copy(error = null) }

    /** The settings and this member's devices. */
    suspend fun load() = inScope {
        val settings = runCatching { context.repository.settings() }.getOrNull()
        mutableState.update { it.copy(settings = settings) }
        loadDevices()
    }

    private suspend fun loadDevices() {
        try {
            val rows = context.repository.deviceUnlocks()
            val own = context.devices.ownRow(rows)
            mutableState.update { it.copy(devices = rows, ownDeviceId = own?.id) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    // region Who may write

    /** "Chats beginnen dürfen". */
    suspend fun setAudience(value: String) = saveSetting("chat_invites_audience", SettingValue.Text(value)) {
        it.copy(chatInvitesAudience = value)
    }

    /** "Freundschaftsanfragen erhalten". */
    suspend fun setFriendRequests(value: Boolean) = saveSetting("friend_requests", SettingValue.Flag(value)) {
        it.copy(friendRequests = value)
    }

    /** Shows the change at once and saves it; the server's answer replaces it, an error puts the previous state back. */
    private suspend fun saveSetting(key: String, value: SettingValue, apply: (ChatSettings) -> ChatSettings) = inScope {
        val previous = state.value.settings ?: return@inScope
        if (state.value.savingSetting) return@inScope
        mutableState.update { it.copy(settings = apply(previous), savingSetting = true) }
        try {
            val saved = context.repository.updateSetting(key, value)
            mutableState.update { it.copy(settings = saved, status = "Gespeichert.") }
        } catch (error: Exception) {
            mutableState.update { it.copy(settings = previous, error = describeChatError(error)) }
        } finally {
            mutableState.update { it.copy(savingSetting = false) }
        }
    }

    // endregion

    // region Biometrics

    /** "Dieses Gerät einrichten". */
    suspend fun addThisDevice() = inScope {
        try {
            context.devices.register()
            mutableState.update {
                it.copy(status = "Eingerichtet. Ab jetzt kannst du hier mit Fingerabdruck oder Gesichtserkennung entsperren.")
            }
            loadDevices()
        } catch (_: ChatKeyringException.DeviceUnlockUnsupported) {
            mutableState.update { it.copy(error = Biometrics.UNSUPPORTED) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    /** "Entfernen" of a device (after the confirmation where needed). */
    suspend fun remove(row: DeviceUnlockRow) = inScope {
        try {
            context.devices.remove(row)
            mutableState.update { it.copy(status = "„${row.label}“ entfernt.") }
            loadDevices()
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    // endregion

    // region Passphrase

    /** Lock the open keys with a (new) passphrase – the backup, or the change of an existing one. */
    suspend fun saveBackup() = inScope {
        val current = state.value
        val problem = ChatInput.passphraseProblem(current.passphrase, current.confirmation)
        mutableState.update { it.copy(backupError = problem) }
        val identity = vault.identity
        val stored = vault.storedIdentity
        if (problem != null || identity == null || stored == null) return@inScope
        mutableState.update { it.copy(busy = true) }
        try {
            val locked = withContext(context.cryptoDispatcher) { context.crypto.lockIdentity(identity, current.passphrase) }
            vault.setStoredIdentity(context.repository.saveIdentity(locked, stored.publicKeys))
            refreshPassphraseFlag()
            clearInputs()
            mutableState.update { it.copy(status = "Deine Chat-Passphrase ist gespeichert.") }
        } catch (error: Exception) {
            mutableState.update { it.copy(backupError = describeChatError(error)) }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    /** "Passphrase abschalten": the same keys without the passphrase copy; only devices open them afterwards. */
    suspend fun removePassphrase() = inScope {
        val stored = vault.storedIdentity ?: return@inScope
        try {
            vault.setStoredIdentity(context.repository.saveIdentity(null, stored.publicKeys))
            refreshPassphraseFlag()
            mutableState.update {
                it.copy(
                    status = "Die Chat-Passphrase ist abgeschaltet. Du entsperrst jetzt nur noch mit Fingerabdruck oder Gesichtserkennung."
                )
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    /** Forgets typed passphrases (leaving the screen). */
    fun clearInputs() = mutableState.update { it.copy(passphrase = "", confirmation = "") }

    // endregion

    override fun onCleared() {
        clearInputs()
    }
}
