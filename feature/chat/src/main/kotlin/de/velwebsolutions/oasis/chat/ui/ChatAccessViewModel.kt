package de.velwebsolutions.oasis.chat.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.chat.api.SafetyLists
import de.velwebsolutions.oasis.chat.core.Biometrics
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatDevices
import de.velwebsolutions.oasis.chat.core.ChatInput
import de.velwebsolutions.oasis.chat.core.ChatKeyringException
import de.velwebsolutions.oasis.chat.core.describeChatError
import de.velwebsolutions.oasis.core.network.ApiError
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which screen the chat gate shows. */
sealed interface ChatPhase {
    data object Loading : ChatPhase

    data object Setup : ChatPhase

    data object Unlock : ChatPhase

    data object Open : ChatPhase

    data class Failed(val message: String) : ChatPhase
}

/** Everything the setup and unlock screens show. */
data class ChatAccessState(
    val phase: ChatPhase = ChatPhase.Loading,
    val passphrase: String = "",
    val confirmation: String = "",
    val formError: String? = null,
    val busy: Boolean = false,
    /** Biometrics on the setup screen ("Mit Fingerabdruck … einrichten"). */
    val deviceSetupStatus: String? = null,
    /** Unlock screen: this device has a biometric copy. */
    val canUnlockWithDevice: Boolean = false,
    val deviceUnlockError: String? = null,
    /** After a passphrase or device-link unlock on a biometric device without its own copy: "Künftig mit Fingerabdruck?". */
    val offersDeviceUnlock: Boolean = false,
    /** "Mit anderem Gerät freigeben": the check number while waiting. */
    val linkCheck: String? = null,
    val linkStatus: String? = null,
    /** Whether the device offers a strong biometric (setup choice and unlock button). */
    val deviceAvailable: Boolean = false,
    /** Whether the stored identity has a passphrase copy. */
    val hasPassphrase: Boolean = false,
    /** Back from the reset to the unlock screen. */
    val canCancelReset: Boolean = false
)

/**
 * The screens before the chat is open (ui/identity-screens.js, ui/device-unlock.js, ui/device-links.js; iOS
 * `ChatAccessModel`): load the stored identity, then "Chat einrichten" (biometrics or passphrase) or "Nachrichten
 * entsperren" (biometrics, passphrase, or another device).
 */
@HiltViewModel
class ChatAccessViewModel @Inject constructor(context: ChatContext) : ChatViewModel(context) {
    private val mutableState = MutableStateFlow(ChatAccessState(deviceAvailable = context.devices.isAvailable))
    val state: StateFlow<ChatAccessState> = mutableState.asStateFlow()

    /** Biometrics start by themselves once per locked episode; cancelling leaves the buttons. */
    private var autoUnlockTried = false
    private var pendingLink: ChatDevices.PendingLink? = null
    private var pollJob: Job? = null

    /** How often the asking device looks for the approval. */
    var pollInterval: Duration = 4.seconds

    init {
        // The vault locks while a chat screen is shown (Sperren, idle, background): back to the unlock screen.
        vault.unlockedFlow.drop(1).filter { unlocked -> !unlocked }.onEach { vaultLocked() }.launchIn(viewModelScope)
    }

    fun setPassphrase(value: String) = mutableState.update { it.copy(passphrase = value) }

    fun setConfirmation(value: String) = mutableState.update { it.copy(confirmation = value) }

    private fun refreshIdentityFlags() = mutableState.update {
        it.copy(hasPassphrase = vault.storedIdentity?.lockedIdentity != null, canCancelReset = vault.storedIdentity != null)
    }

    /** Bind the vault to the signed-in member, load the identity (once per app session) and decide the screen. */
    suspend fun load() = inScope {
        vault.prepare(context.me.id.toString())
        // The word lists of the on-device safety checks, once per app session (best effort).
        if (vault.safety == SafetyLists.Empty) {
            runCatching { context.repository.safety() }.getOrNull()?.let { vault.safety = it }
        }
        if (vault.unlocked) {
            mutableState.update { it.copy(phase = ChatPhase.Open) }
            return@inScope
        }
        if (!vault.stored.isKnown) {
            mutableState.update { it.copy(phase = ChatPhase.Loading) }
            try {
                vault.setStoredIdentity(context.repository.identity())
            } catch (error: Exception) {
                mutableState.update { it.copy(phase = ChatPhase.Failed(describeChatError(error))) }
                return@inScope
            }
        }
        routeLocked()
    }

    /** Called when the vault locks while a chat screen is shown. */
    suspend fun vaultLocked() {
        if (state.value.phase != ChatPhase.Open) return
        routeLocked()
    }

    private suspend fun routeLocked() {
        refreshIdentityFlags()
        when {
            vault.unlocked -> mutableState.update { it.copy(phase = ChatPhase.Open) }

            vault.storedIdentity == null -> mutableState.update { it.copy(phase = ChatPhase.Setup) }

            else -> {
                mutableState.update { it.copy(phase = ChatPhase.Unlock) }
                autoUnlockTried = false
                prepareUnlock()
            }
        }
    }

    /** Unlock screen: biometrics only where this device has a copy on the server. */
    private suspend fun prepareUnlock() {
        mutableState.update { it.copy(deviceUnlockError = null) }
        if (!context.devices.isAvailable || context.secrets.credentialId(vault.userId) == null) {
            mutableState.update { it.copy(canUnlockWithDevice = false) }
            return
        }
        val rows = runCatching { context.repository.deviceUnlocks() }.getOrDefault(emptyList())
        val own = context.devices.ownRow(rows) != null
        mutableState.update { it.copy(canUnlockWithDevice = own) }
    }

    /** "Passphrase vergessen? → Neue Schlüssel erstellen". */
    fun startReset() = mutableState.update {
        it.copy(formError = null, passphrase = "", confirmation = "", phase = ChatPhase.Setup)
    }

    suspend fun cancelReset() = inScope {
        mutableState.update { it.copy(phase = ChatPhase.Unlock) }
        prepareUnlock()
    }

    /** "Schlüssel erstellen" with a passphrase. */
    suspend fun setUp() = inScope {
        val current = state.value
        val problem = ChatInput.passphraseProblem(current.passphrase, current.confirmation)
        mutableState.update { it.copy(formError = problem) }
        if (problem != null) return@inScope
        mutableState.update { it.copy(busy = true) }
        try {
            val stored = context.keyring.setUp(current.passphrase)
            clearInputs()
            if (stored != null) {
                vault.announcement.value = "Deine Chat-Schlüssel sind eingerichtet."
                refreshIdentityFlags()
                mutableState.update { it.copy(phase = ChatPhase.Open) }
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(formError = describeChatError(error)) }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    /** "Mit Fingerabdruck / Gesichtserkennung einrichten" – chat keys without any passphrase. */
    suspend fun setUpWithDevice() = inScope {
        mutableState.update { it.copy(deviceSetupStatus = "Bitte bestätige mit deinem Fingerabdruck oder deinem Gesicht …", busy = true) }
        try {
            if (context.devices.setUpWithDevice() != null) {
                vault.announcement.value = "Dein Chat ist eingerichtet – ohne extra Passwort."
                refreshIdentityFlags()
                mutableState.update { it.copy(deviceSetupStatus = null, phase = ChatPhase.Open) }
            }
        } catch (_: ChatKeyringException.DeviceUnlockUnsupported) {
            mutableState.update { it.copy(deviceSetupStatus = Biometrics.UNSUPPORTED) }
        } catch (error: Exception) {
            mutableState.update { it.copy(deviceSetupStatus = describeChatError(error)) }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    /** "Entsperren" with the passphrase. */
    suspend fun unlock() = inScope {
        val passphrase = state.value.passphrase
        if (passphrase.isEmpty()) {
            mutableState.update { it.copy(formError = "Bitte gib deine Chat-Passphrase ein.") }
            return@inScope
        }
        val stored = vault.storedIdentity ?: return@inScope
        mutableState.update { it.copy(formError = null, busy = true) }
        try {
            val unlocked = context.keyring.unlock(stored, passphrase)
            clearInputs()
            if (unlocked) {
                vault.announcement.value = "Nachrichten entsperrt."
                mutableState.update { it.copy(phase = ChatPhase.Open) }
                offerDeviceUnlockIfUseful()
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(passphrase = "", formError = describeChatError(error)) }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    /** Starts the biometric prompt by itself when the unlock screen shows (and again when the app comes back). */
    suspend fun autoUnlockWithDevice() = inScope {
        val current = state.value
        if (!current.canUnlockWithDevice || autoUnlockTried || current.busy || current.phase != ChatPhase.Unlock) return@inScope
        autoUnlockTried = true
        unlockWithDevice(quietOnCancel = true)
    }

    /** "Mit Fingerabdruck oder Gesichtserkennung entsperren". */
    suspend fun unlockWithDevice(quietOnCancel: Boolean = false) = inScope {
        val stored = vault.storedIdentity ?: return@inScope
        mutableState.update { it.copy(deviceUnlockError = null, busy = true) }
        try {
            val rows = context.repository.deviceUnlocks()
            if (context.devices.unlock(stored, rows)) {
                vault.announcement.value = "Nachrichten entsperrt."
                mutableState.update { it.copy(phase = ChatPhase.Open) }
            }
        } catch (error: Exception) {
            // A cancelled automatic prompt keeps the button and the passphrase without a complaint.
            if (!(quietOnCancel && error is ChatKeyringException.DeviceUnlockUnsupported)) {
                val text = if (state.value.hasPassphrase) {
                    "Das hat nicht geklappt. Versuch es noch einmal oder nimm deine Passphrase."
                } else {
                    "Das hat nicht geklappt. Versuch es noch einmal."
                }
                mutableState.update { it.copy(deviceUnlockError = text) }
            }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    // region Biometric offer

    private suspend fun offerDeviceUnlockIfUseful() {
        val offers = context.devices.isAvailable && !state.value.canUnlockWithDevice &&
            !context.preferences.biometricOfferDeclined(vault.userId)
        mutableState.update { it.copy(offersDeviceUnlock = offers) }
    }

    /** "Fingerabdruck verwenden": stores this device's copy, so the next unlock needs no passphrase. */
    suspend fun acceptDeviceOffer() = inScope {
        mutableState.update { it.copy(offersDeviceUnlock = false) }
        try {
            context.devices.register()
            mutableState.update { it.copy(canUnlockWithDevice = true) }
            vault.announcement.value = "Ab jetzt entsperrst du hier mit Fingerabdruck oder Gesichtserkennung."
        } catch (error: Exception) {
            vault.announcement.value = describeChatError(error)
        }
    }

    /** "Nicht jetzt": not asked again on this device (Chat-Einstellungen → "Dieses Gerät einrichten" still works). */
    suspend fun declineDeviceOffer() = inScope {
        mutableState.update { it.copy(offersDeviceUnlock = false) }
        context.preferences.setBiometricOfferDeclined(vault.userId, true)
    }

    // endregion

    // region Device link (asking side)

    /** "Freigabe anfragen". */
    suspend fun requestLink() = inScope {
        mutableState.update { it.copy(linkStatus = null) }
        try {
            val link = context.devices.requestLink()
            pendingLink = link
            mutableState.update { it.copy(linkCheck = link.check) }
            startPolling()
        } catch (error: Exception) {
            mutableState.update { it.copy(linkStatus = describeChatError(error)) }
        }
    }

    suspend fun cancelLink() = inScope {
        val link = pendingLink
        resetLink()
        if (link != null) context.devices.cancel(link)
    }

    private fun resetLink() {
        pollJob?.cancel()
        pollJob = null
        pendingLink?.keyPair?.wipe()
        pendingLink = null
        mutableState.update { it.copy(linkCheck = null) }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            do {
                delay(pollInterval)
            } while (!pollOnce())
        }
    }

    /** One round of picking up the approval; true when finished (opened, or failed). */
    suspend fun pollOnce(): Boolean {
        val link = pendingLink ?: return true
        val stored = vault.storedIdentity ?: return true
        return try {
            val unlocked = context.devices.pickUp(link, stored) ?: return false
            pendingLink = null
            mutableState.update { it.copy(linkCheck = null) }
            if (unlocked) {
                vault.announcement.value = "Freigegeben – dein Chat ist offen."
                mutableState.update { it.copy(phase = ChatPhase.Open) }
                offerDeviceUnlockIfUseful()
            }
            true
        } catch (cancelled: CancellationException) {
            // A new request or "Abbrechen" stopped this round; the newer request must stay.
            throw cancelled
        } catch (error: Exception) {
            resetLink()
            val text = if (error == ApiError.NotFound) {
                "Die Anfrage ist abgelaufen oder wurde abgelehnt. Versuch es noch einmal."
            } else {
                describeChatError(error)
            }
            mutableState.update { it.copy(linkStatus = text) }
            true
        }
    }

    // endregion

    /** Leaving the screen: stop polling and forget the one-time key and typed passphrases. */
    fun stop() {
        resetLink()
        clearInputs()
    }

    private fun clearInputs() = mutableState.update { it.copy(passphrase = "", confirmation = "") }

    override fun onCleared() {
        stop()
    }
}
