package de.velwebsolutions.oasis.staff.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.staff.api.AdminDashboard
import de.velwebsolutions.oasis.staff.api.AdminVote
import de.velwebsolutions.oasis.staff.api.BreachRegister
import de.velwebsolutions.oasis.staff.api.DataBreach
import de.velwebsolutions.oasis.staff.api.MollieLookup
import de.velwebsolutions.oasis.staff.api.NewBreach
import de.velwebsolutions.oasis.staff.api.NewVote
import de.velwebsolutions.oasis.staff.api.PaymentPage
import de.velwebsolutions.oasis.staff.api.PaymentRecord
import de.velwebsolutions.oasis.staff.api.SystemStatus
import de.velwebsolutions.oasis.staff.data.StaffRepository
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.components.apiError
import de.velwebsolutions.oasis.staff.ui.components.attempt
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminDashboardUiState(
    val dashboard: Loadable<AdminDashboard> = Loadable.Idle,
    val days: Int = 30,
    /** "" means the current month. */
    val month: String = "",
    val error: String? = null
)

/** Admin statistics (like /admin): period and revenue month. */
@HiltViewModel
class AdminDashboardViewModel @Inject constructor(private val repository: StaffRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AdminDashboardUiState())
    val state: StateFlow<AdminDashboardUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(dashboard = it.dashboard.refreshing()) }
        val current = mutableState.value
        val result = attempt { repository.dashboard(current.days, current.month) }
        result.onSuccess { dashboard -> mutableState.update { it.copy(dashboard = Loadable.Loaded(dashboard), days = dashboard.days) } }
        result.apiError?.let { failure ->
            mutableState.update {
                it.copy(
                    dashboard = it.dashboard.failing(failure),
                    error = if (it.dashboard.valueOrNull !=
                        null
                    ) {
                        failure.userMessage
                    } else {
                        it.error
                    }
                )
            }
        }
    }

    suspend fun selectDays(days: Int) {
        mutableState.update { it.copy(days = days) }
        load()
    }

    suspend fun showMonth(month: String) {
        mutableState.update { it.copy(month = month) }
        load()
    }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}

data class AdminVotesUiState(
    val votes: Loadable<List<AdminVote>> = Loadable.Idle,
    val title: String = "",
    val description: String = "",
    val startsAt: Instant = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES),
    val endsAt: Instant = Instant.now().plus(8, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES),
    val options: List<String> = List(6) { "" },
    val message: String? = null,
    val error: String? = null,
    val fieldErrors: Map<String, String> = emptyMap()
)

/** Member votes for admins (like /admin/abstimmungen). */
@HiltViewModel
class AdminVotesViewModel @Inject constructor(private val repository: StaffRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AdminVotesUiState())
    val state: StateFlow<AdminVotesUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(votes = it.votes.refreshing()) }
        val result = attempt { repository.votes() }
        result.onSuccess { votes -> mutableState.update { it.copy(votes = Loadable.Loaded(votes)) } }
        result.apiError?.let { failure ->
            mutableState.update {
                it.copy(
                    votes = it.votes.failing(failure),
                    error = if (it.votes.valueOrNull !=
                        null
                    ) {
                        failure.userMessage
                    } else {
                        it.error
                    }
                )
            }
        }
    }

    fun edit(transform: (AdminVotesUiState) -> AdminVotesUiState) = mutableState.update(transform)

    suspend fun create() {
        val form = mutableState.value
        val vote =
            NewVote(form.title, form.description, StaffDate.localInput(form.startsAt), StaffDate.localInput(form.endsAt), form.options)
        val result = attempt { repository.createVote(vote) }
        result.onSuccess { answer ->
            mutableState.update {
                it.copy(message = answer.message, fieldErrors = emptyMap(), title = "", description = "", options = List(6) { "" })
            }
            load()
        }
        result.apiError?.let { failure ->
            val fields = failure.fieldMessages
            mutableState.update { it.copy(fieldErrors = fields, error = if (fields.isEmpty()) failure.userMessage else it.error) }
        }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}

data class AdminPaymentsUiState(
    val page: Loadable<PaymentPage> = Loadable.Idle,
    val records: List<PaymentRecord> = emptyList(),
    val search: String = "",
    val statusFilter: String = "",
    val mollie: MollieLookup? = null,
    val message: String? = null,
    val error: String? = null,
    val refundFor: Int? = null,
    val refundAmount: String = "",
    val refundReason: String = "",
    val refundErrors: Map<String, String> = emptyMap()
) {
    val hasMore: Boolean get() = page.valueOrNull?.page?.nextCursor != null

    val total: Int get() = page.valueOrNull?.meta?.total ?: records.size
}

/** Payment search (like /admin/zahlungen): local records, live Mollie lookups, sync and refunds. */
@HiltViewModel
class AdminPaymentsViewModel @Inject constructor(private val repository: StaffRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AdminPaymentsUiState())
    val state: StateFlow<AdminPaymentsUiState> = mutableState.asStateFlow()
    private var pageNumber = 1
    private var pendingSearch: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(page = it.page.refreshing()) }
        val current = mutableState.value
        val result = attempt { repository.payments(current.search.trim(), current.statusFilter, 1) }
        result.onSuccess { page ->
            pageNumber = 1
            mutableState.update { it.copy(page = Loadable.Loaded(page), records = page.data) }
        }
        result.apiError?.let { failure ->
            mutableState.update {
                it.copy(
                    page = it.page.failing(failure),
                    error = if (it.page.valueOrNull !=
                        null
                    ) {
                        failure.userMessage
                    } else {
                        it.error
                    }
                )
            }
        }
    }

    suspend fun loadMore() {
        val current = mutableState.value
        if (!current.hasMore) return
        val result = attempt { repository.payments(current.search.trim(), current.statusFilter, pageNumber + 1) }
        result.onSuccess { page ->
            pageNumber += 1
            mutableState.update { it.copy(page = Loadable.Loaded(page), records = it.records + page.data) }
        }
        result.apiError?.let { failure -> mutableState.update { it.copy(error = failure.userMessage) } }
    }

    /** The search reloads once typing pauses. */
    fun updateSearch(search: String) {
        mutableState.update { it.copy(search = search) }
        pendingSearch?.cancel()
        pendingSearch = viewModelScope.launch {
            delay(400)
            load()
        }
    }

    fun selectStatus(status: String) {
        mutableState.update { it.copy(statusFilter = status) }
        viewModelScope.launch { load() }
    }

    suspend fun checkPayment(record: PaymentRecord) = lookup { repository.checkPayment(record.id) }

    suspend fun checkCustomer(record: PaymentRecord) = lookup { repository.checkCustomer(record.id) }

    suspend fun searchMollie() = lookup { repository.searchMollie(mutableState.value.search) }

    fun closeMollie() = mutableState.update { it.copy(mollie = null) }

    suspend fun sync(record: PaymentRecord) {
        val result = attempt { repository.syncPayment(record.id) }
        result.onSuccess { answer -> replace(answer, refunded = false) }
        result.apiError?.let { failure -> mutableState.update { it.copy(error = failure.userMessage) } }
    }

    fun startRefund(record: PaymentRecord) = mutableState.update {
        it.copy(refundFor = record.id, refundAmount = record.refundDefault, refundReason = "", refundErrors = emptyMap())
    }

    fun cancelRefund() = mutableState.update { it.copy(refundFor = null, refundErrors = emptyMap()) }

    fun editRefund(amount: String? = null, reason: String? = null) =
        mutableState.update { it.copy(refundAmount = amount ?: it.refundAmount, refundReason = reason ?: it.refundReason) }

    suspend fun refund(record: PaymentRecord) {
        val current = mutableState.value
        val result = attempt { repository.refund(record.id, current.refundAmount, current.refundReason) }
        result.onSuccess { answer -> replace(answer, refunded = true) }
        result.apiError?.let { failure ->
            val fields = failure.fieldMessages
            mutableState.update { it.copy(refundErrors = fields, error = if (fields.isEmpty()) failure.userMessage else it.error) }
        }
    }

    private suspend fun lookup(request: suspend () -> MollieLookup) {
        val result = attempt { request() }
        mutableState.update { it.copy(mollie = result.getOrNull() ?: MollieLookup(error = result.apiError?.userMessage)) }
    }

    private fun replace(answer: Confirmed<PaymentRecord>, refunded: Boolean) = mutableState.update { state ->
        state.copy(
            message = answer.message,
            records = state.records.map { if (it.id == answer.data.id) answer.data else it },
            refundFor = if (refunded) null else state.refundFor,
            refundErrors = if (refunded) emptyMap() else state.refundErrors
        )
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}

data class DataBreachesUiState(
    val register: Loadable<BreachRegister> = Loadable.Idle,
    val form: NewBreach = NewBreach(),
    val discoveredAt: Instant = Instant.now().truncatedTo(ChronoUnit.MINUTES),
    val references: Map<Int, String> = emptyMap(),
    val notes: Map<Int, String> = emptyMap(),
    val message: String? = null,
    val error: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    /** Breach id → why a step was refused, shown at the breach. */
    val stepErrors: Map<Int, String> = emptyMap()
)

/** Register of data breaches (like /admin/datenpannen). */
@HiltViewModel
class DataBreachesViewModel @Inject constructor(private val repository: StaffRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(DataBreachesUiState())
    val state: StateFlow<DataBreachesUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(register = it.register.refreshing()) }
        val result = attempt { repository.breaches() }
        result.onSuccess { register -> mutableState.update { it.copy(register = Loadable.Loaded(register)) } }
        result.apiError?.let { failure ->
            mutableState.update {
                it.copy(
                    register = it.register.failing(failure),
                    error = if (it.register.valueOrNull !=
                        null
                    ) {
                        failure.userMessage
                    } else {
                        it.error
                    }
                )
            }
        }
    }

    fun edit(transform: (DataBreachesUiState) -> DataBreachesUiState) = mutableState.update(transform)

    suspend fun record() {
        val current = mutableState.value
        val breach = current.form.copy(discoveredAt = StaffDate.localInput(current.discoveredAt))
        val result = attempt { repository.recordBreach(breach) }
        result.onSuccess { answer ->
            mutableState.update {
                it.copy(
                    message = answer.message,
                    fieldErrors = emptyMap(),
                    form = NewBreach(),
                    discoveredAt = Instant.now().truncatedTo(ChronoUnit.MINUTES)
                )
            }
            load()
        }
        result.apiError?.let { failure ->
            val fields = failure.fieldMessages
            mutableState.update { it.copy(fieldErrors = fields, error = if (fields.isEmpty()) failure.userMessage else it.error) }
        }
    }

    suspend fun recordReport(breach: DataBreach) {
        val reference = mutableState.value.references[breach.id].orEmpty()
        if (step(breach) { repository.recordBreachReport(breach.id, reference) }) {
            mutableState.update {
                it.copy(
                    references =
                        it.references - breach.id
                )
            }
        }
    }

    suspend fun recordNotified(breach: DataBreach) {
        step(breach) { repository.recordPeopleNotified(breach.id) }
    }

    suspend fun addNote(breach: DataBreach) {
        val note = mutableState.value.notes[breach.id].orEmpty()
        if (step(breach) { repository.addBreachNote(breach.id, note) }) mutableState.update { it.copy(notes = it.notes - breach.id) }
    }

    suspend fun close(breach: DataBreach) {
        step(breach) { repository.closeBreach(breach.id) }
    }

    private suspend fun step(breach: DataBreach, request: suspend () -> Confirmed<DataBreach>): Boolean {
        val result = attempt { request() }
        result.onSuccess { answer ->
            mutableState.update { it.copy(message = answer.message, stepErrors = it.stepErrors - breach.id) }
            load()
        }
        val failure = result.apiError ?: return true
        mutableState.update { it.copy(stepErrors = it.stepErrors + (breach.id to failure.userMessage)) }
        return false
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}

data class SystemUiState(val status: Loadable<SystemStatus> = Loadable.Idle, val error: String? = null)

/** Systemzustand (like /admin/system); reloading runs the checks again ("Jetzt prüfen"). */
@HiltViewModel
class SystemViewModel @Inject constructor(private val repository: StaffRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SystemUiState())
    val state: StateFlow<SystemUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(status = it.status.refreshing()) }
        val result = attempt { repository.system() }
        result.onSuccess { status -> mutableState.update { it.copy(status = Loadable.Loaded(status)) } }
        result.apiError?.let { failure ->
            mutableState.update {
                it.copy(
                    status = it.status.failing(failure),
                    error = if (it.status.valueOrNull !=
                        null
                    ) {
                        failure.userMessage
                    } else {
                        it.error
                    }
                )
            }
        }
    }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}
