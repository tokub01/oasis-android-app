package de.velwebsolutions.oasis.account.ui.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.api.DeviceOverview
import de.velwebsolutions.oasis.account.api.ReportForm
import de.velwebsolutions.oasis.account.api.SafetyAccount
import de.velwebsolutions.oasis.account.api.SafetyList
import de.velwebsolutions.oasis.account.api.SafetyLists
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.account.data.AccountRepository
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

/** A loaded list with the confirmation and error of the last action (devices, safety lists). */
data class ListUiState<T>(val state: Loadable<T> = Loadable.Idle, val message: String? = null, val error: String? = null)

/** Loads one value and runs actions answered with a confirmation, reloading after each (Geräte, Blockiert & stumm). */
abstract class ListViewModel<T> : ViewModel() {
    protected val mutableUi = MutableStateFlow(ListUiState<T>())
    val ui: StateFlow<ListUiState<T>> = mutableUi.asStateFlow()

    /** Subclasses call [start] from their `init`, once their own properties are set. */
    protected abstract suspend fun fetch(): T

    protected fun start() {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { fetch() }
            .onSuccess { value ->
                mutableUi.update { it.copy(state = Loadable.Loaded(value)) }
            }
            .onFailure { error ->
                mutableUi.update {
                    val failing = it.state.failing(error)
                    it.copy(state = failing, error = if (failing.valueOrNull != null) error.asApiError().userMessage else it.error)
                }
            }
    }

    protected suspend fun change(action: suspend () -> String) {
        catching { action() }
            .onSuccess { message ->
                mutableUi.update { it.copy(message = message) }
                load()
            }
            .onFailure { error ->
                mutableUi.update { it.copy(error = error.asApiError().userMessage) }
            }
    }

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

    fun dismissError() = mutableUi.update { it.copy(error = null) }
}

/** Geräte und Anmeldungen. */
@HiltViewModel
class DevicesViewModel @Inject constructor(private val repository: AccountRepository) : ListViewModel<DeviceOverview>() {
    init {
        start()
    }

    override suspend fun fetch() = repository.devices()

    suspend fun signOutApp(app: DeviceOverview.AppSignIn) = change { repository.signOutApp(app.id) }

    suspend fun endSession(session: DeviceOverview.BrowserSession) = change { repository.endSession(session.key) }

    suspend fun signOutEverywhereElse() = change { repository.signOutEverywhereElse() }

    suspend fun forget(device: DeviceOverview.KnownDevice) = change { repository.forgetKnownDevice(device.id) }

    suspend fun forgetChat(device: DeviceOverview.ChatDevice) = change { repository.forgetChatDevice(device.id) }
}

/** Blockiert & stumm (/app/sicherheit). */
@HiltViewModel
class SafetyViewModel @Inject constructor(private val repository: AccountRepository) : ListViewModel<SafetyLists>() {
    init {
        start()
    }

    override suspend fun fetch() = repository.safety()

    suspend fun lift(list: SafetyList, account: SafetyAccount) = change { repository.lift(list, account.id) }
}

/** What Melden shows. */
data class ReportUiState(
    val state: Loadable<ReportForm> = Loadable.Idle,
    val category: String? = null,
    val comment: String = "",
    val sent: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val error: String? = null
)

/** Melden (/app/melden). */
@HiltViewModel(assistedFactory = ReportViewModel.Factory::class)
class ReportViewModel @AssistedInject constructor(
    @Assisted private val url: String?,
    private val repository: AccountRepository
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(url: String?): ReportViewModel
    }

    private val mutableUi = MutableStateFlow(ReportUiState())
    val ui: StateFlow<ReportUiState> = mutableUi.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun choose(category: String) = mutableUi.update { it.copy(category = category) }

    fun setComment(value: String) = mutableUi.update { it.copy(comment = value) }

    suspend fun load() {
        if (url.isNullOrEmpty()) {
            mutableUi.update { it.copy(state = Loadable.Failed(ApiError.NotFound)) }
            return
        }
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { repository.reportForm(url) }
            .onSuccess { form ->
                mutableUi.update { it.copy(state = Loadable.Loaded(form)) }
            }
            .onFailure { error ->
                mutableUi.update { it.copy(state = it.state.failing(error)) }
            }
    }

    suspend fun send() {
        val current = ui.value
        val form = current.state.valueOrNull ?: return
        mutableUi.update { it.copy(fieldErrors = emptyMap()) }
        catching { repository.report(form.url, current.category, current.comment) }
            .onSuccess {
                mutableUi.update { it.copy(sent = true) }
            }
            .onFailure { error ->
                val failure = error.asApiError()
                val fields = failure.fieldMessages
                mutableUi.update { it.copy(fieldErrors = fields, error = if (fields.isEmpty()) failure.userMessage else it.error) }
            }
    }

    fun dismissError() = mutableUi.update { it.copy(error = null) }
}
