package de.velwebsolutions.oasis.live.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.live.api.HintSettings
import de.velwebsolutions.oasis.live.api.PushDevice
import de.velwebsolutions.oasis.live.data.LiveRepository
import de.velwebsolutions.oasis.live.push.PushAuthorization
import de.velwebsolutions.oasis.live.push.PushFailure
import de.velwebsolutions.oasis.live.push.PushRegistrar
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Hinweise einstellen shows. */
data class HintSettingsUiState(
    val state: Loadable<HintSettings> = Loadable.Idle,
    /** A change is on its way to the server; the controls wait meanwhile. */
    val isSaving: Boolean = false,
    /** Confirmation of the last change. */
    val message: String? = null,
    val error: String? = null,
    /** Messages of refused quiet hours by wire name (`quiet_from`, `quiet_until`). */
    val fieldErrors: Map<String, String> = emptyMap(),
    /** Whether Android allows notifications for Oasis. */
    val authorization: PushAuthorization = PushAuthorization.NotDetermined,
    /** Whether this build can get push hints from this server (Firebase set up, FCM accepted by the server). */
    val pushSupported: Boolean = false
) {
    val settings: HintSettings? get() = state.valueOrNull

    /** Whether this installation is in the device list. */
    val isOnForThisDevice: Boolean get() = settings?.push?.devices?.any { it.type == "app" && it.current } ?: false

    /** Push hints can be switched on here: the server sends app pushes and this build can receive them. */
    val pushAvailable: Boolean get() = settings?.push?.available == true && pushSupported
}

/**
 * Hinweise einstellen (/app/hinweise/einstellungen): hint kinds, the daily e-mail, quiet hours, a pause, the chat
 * switches and the devices with push hints, including this one. Like the privacy centre every change is saved at once:
 * the screen shows a change right away ([edit]), the controls wait while the server saves, and a failed change goes
 * back with the reason. Refused quiet hours stay on screen with their message, so they can be corrected.
 */
@HiltViewModel
class HintSettingsViewModel @Inject constructor(private val repository: LiveRepository, private val push: PushRegistrar) : ViewModel() {
    private val mutableUi = MutableStateFlow(HintSettingsUiState(pushSupported = push.isSupported))
    val ui: StateFlow<HintSettingsUiState> = mutableUi.asStateFlow()

    /** The settings as the server last confirmed them, to go back to after a failed change. */
    private var saved: HintSettings? = null

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        try {
            val settings = repository.settings()
            saved = settings
            mutableUi.update { it.copy(state = Loadable.Loaded(settings)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableUi.update {
                val failing = it.state.failing(error)
                it.copy(state = failing, error = if (failing.valueOrNull != null) error.asApiError().userMessage else it.error)
            }
        }
        refreshAuthorization()
    }

    /** Re-reads the permission (back from the Android settings). */
    fun refreshAuthorization() {
        push.refreshAuthorization()
        mutableUi.update { it.copy(authorization = push.authorization.value, pushSupported = push.isSupported) }
    }

    /** Shows a change at once; [save] or [saveChat] sends it. */
    fun edit(change: (HintSettings) -> HintSettings) {
        mutableUi.update { state -> state.settings?.let { state.copy(state = Loadable.Loaded(change(it))) } ?: state }
    }

    /** Whether the quiet hours on screen differ from the saved ones (the time fields save after a short pause). */
    val quietHoursChanged: Boolean
        get() = ui.value.settings?.quietFrom != saved?.quietFrom || ui.value.settings?.quietUntil != saved?.quietUntil

    /** Saves the kinds, the daily e-mail and the quiet hours as shown. */
    suspend fun save() {
        val settings = ui.value.settings ?: return
        if (ui.value.isSaving) return
        mutableUi.update { it.copy(isSaving = true) }
        try {
            val answer = repository.save(settings)
            saved = answer.data
            mutableUi.update { it.copy(isSaving = false, fieldErrors = emptyMap(), message = answer.message) }
            // The quiet hours may have changed again meanwhile: they stay on screen and are saved, too.
            if (ui.value.settings == settings) {
                mutableUi.update { it.copy(state = Loadable.Loaded(answer.data)) }
            } else {
                save()
            }
        } catch (cancelled: CancellationException) {
            mutableUi.update { it.copy(isSaving = false) }
            throw cancelled
        } catch (error: Exception) {
            val failure = error.asApiError()
            val fields = failure.fieldMessages
            mutableUi.update { state ->
                state.copy(
                    isSaving = false,
                    fieldErrors = fields,
                    error = failure.userMessage,
                    state = if (fields.isEmpty()) saved?.let { Loadable.Loaded(it) } ?: state.state else state.state
                )
            }
        }
    }

    /** A switch that shows its new state at once and saves it. */
    fun toggle(change: (HintSettings) -> HintSettings) {
        edit(change)
        viewModelScope.launch { save() }
    }

    /** Saves a chat switch (`chat_push`, `chat_email`) as shown, through the privacy settings. */
    suspend fun saveChat(key: String) {
        if (ui.value.isSaving) return
        val index = ui.value.settings?.chat?.indexOfFirst { it.key == key }?.takeIf { it >= 0 } ?: return
        val on = ui.value.settings?.chat?.get(index)?.enabled ?: return
        mutableUi.update { it.copy(isSaving = true) }
        try {
            val message = repository.setChatSwitch(key, on)
            saved =
                saved?.let { current ->
                    current.copy(
                        chat = current.chat.mapIndexed { i, chat ->
                            if (i ==
                                index
                            ) {
                                chat.copy(enabled = on)
                            } else {
                                chat
                            }
                        }
                    )
                }
            mutableUi.update { it.copy(message = message) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            edit { settings ->
                settings.copy(
                    chat = settings.chat.mapIndexed { i, chat ->
                        if (i ==
                            index
                        ) {
                            chat.copy(enabled = !on)
                        } else {
                            chat
                        }
                    }
                )
            }
            mutableUi.update { it.copy(error = error.asApiError().userMessage) }
        } finally {
            mutableUi.update { it.copy(isSaving = false) }
        }
    }

    /** Shows a chat switch at once and saves it. */
    fun toggleChat(key: String, on: Boolean) {
        edit { settings -> settings.copy(chat = settings.chat.map { if (it.key == key) it.copy(enabled = on) else it }) }
        viewModelScope.launch { saveChat(key) }
    }

    suspend fun pause(days: Int) = changePause { repository.pause(days) }

    suspend fun resume() = changePause { repository.resume() }

    /** "Entfernen" in the device list. */
    suspend fun remove(device: PushDevice) {
        try {
            val message = repository.removeDevice(device)
            if (device.type == "app") push.forgetDevice(device.id)
            mutableUi.update { it.copy(message = message) }
            refreshDevices()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableUi.update { it.copy(error = error.asApiError().userMessage) }
        }
    }

    /** The screen asked Android for the notification permission. */
    fun permissionAnswered(granted: Boolean) {
        push.permissionAnswered(granted)
        mutableUi.update { it.copy(authorization = push.authorization.value) }
    }

    /** "Auf diesem Gerät einschalten" / "ausschalten". */
    suspend fun togglePushOnThisDevice() {
        try {
            val message = if (ui.value.isOnForThisDevice) push.disableOnThisDevice() else push.enableOnThisDevice()
            mutableUi.update { it.copy(message = message) }
        } catch (failure: PushFailure) {
            mutableUi.update { it.copy(error = failure.message) }
        }
        mutableUi.update { it.copy(authorization = push.authorization.value) }
        refreshDevices()
    }

    fun openSystemSettings() = push.openSystemSettings()

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

    fun dismissError() = mutableUi.update { it.copy(error = null) }

    /** Pause and its end change only the pause, never the switches on screen. */
    private suspend fun changePause(request: suspend () -> Confirmed<HintSettings>) {
        try {
            val answer = request()
            edit { it.copy(pausedUntil = answer.data.pausedUntil) }
            saved = saved?.copy(pausedUntil = answer.data.pausedUntil)
            mutableUi.update { it.copy(message = answer.message) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableUi.update { it.copy(error = error.asApiError().userMessage) }
        }
    }

    /** The device list after a device was added or removed. */
    private suspend fun refreshDevices() {
        val fresh = try {
            repository.settings()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        edit { it.copy(push = fresh.push) }
        saved = saved?.copy(push = fresh.push)
    }

    companion object {
        /** "1 Tag", "3 Tage", "1 Woche" – the pause buttons. */
        fun pauseLabel(days: Int): String = when (days) {
            1 -> "1 Tag"
            7 -> "1 Woche"
            else -> "$days Tage"
        }
    }
}
