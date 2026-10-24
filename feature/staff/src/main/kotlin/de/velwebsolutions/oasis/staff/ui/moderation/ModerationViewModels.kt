package de.velwebsolutions.oasis.staff.ui.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.staff.api.CaseSummary
import de.velwebsolutions.oasis.staff.api.ComplaintDetail
import de.velwebsolutions.oasis.staff.api.Labelled
import de.velwebsolutions.oasis.staff.api.ModerationCaseDetail
import de.velwebsolutions.oasis.staff.api.ModerationOverview
import de.velwebsolutions.oasis.staff.api.QueueFilter
import de.velwebsolutions.oasis.staff.api.RoleUser
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.api.Suspension
import de.velwebsolutions.oasis.staff.api.SuspensionList
import de.velwebsolutions.oasis.staff.data.StaffFiles
import de.velwebsolutions.oasis.staff.data.StaffRepository
import de.velwebsolutions.oasis.staff.ui.components.apiError
import de.velwebsolutions.oasis.staff.ui.components.attempt
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModerationOverviewUiState(
    val overview: Loadable<ModerationOverview> = Loadable.Idle,
    val cases: List<CaseSummary> = emptyList(),
    val hasMore: Boolean = false,
    val isLoadingQueue: Boolean = false,
    /** Changing it reloads the queue from page 1, like the web. */
    val filter: QueueFilter = QueueFilter(),
    /** The filter of the loaded queue, so a filter change reloads only once. */
    val loadedFilter: QueueFilter = QueueFilter(),
    val error: String? = null
)

/** The moderation start page: figures, waiting locks, activity and the filterable queue (like /moderation). */
@HiltViewModel
class ModerationOverviewViewModel @Inject constructor(private val repository: StaffRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ModerationOverviewUiState())
    val state: StateFlow<ModerationOverviewUiState> = mutableState.asStateFlow()
    private var page = 1
    private var pendingReload: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(overview = it.overview.refreshing()) }
        val result = attempt { repository.overview() }
        val failure = result.apiError
        if (failure != null) {
            mutableState.update {
                it.copy(
                    overview = it.overview.failing(failure),
                    error = if (it.overview.valueOrNull !=
                        null
                    ) {
                        failure.userMessage
                    } else {
                        it.error
                    }
                )
            }
            return
        }
        mutableState.update { it.copy(overview = Loadable.Loaded(result.getOrThrow())) }
        reloadQueue()
    }

    /** The first page for the current filter. */
    suspend fun reloadQueue() {
        val filter = mutableState.value.filter
        mutableState.update { it.copy(loadedFilter = filter, isLoadingQueue = true) }
        val result = attempt { repository.cases(filter, 1) }
        result.onSuccess { cases ->
            page = 1
            mutableState.update { it.copy(cases = cases.data, hasMore = cases.nextCursor != null, isLoadingQueue = false) }
        }
        result.apiError?.let { failure -> mutableState.update { it.copy(error = failure.userMessage, isLoadingQueue = false) } }
    }

    suspend fun loadMore() {
        val current = mutableState.value
        if (!current.hasMore || current.isLoadingQueue) return
        mutableState.update { it.copy(isLoadingQueue = true) }
        val result = attempt { repository.cases(current.filter, page + 1) }
        result.onSuccess { cases ->
            page += 1
            mutableState.update { it.copy(cases = it.cases + cases.data, hasMore = cases.nextCursor != null, isLoadingQueue = false) }
        }
        result.apiError?.let { failure -> mutableState.update { it.copy(error = failure.userMessage, isLoadingQueue = false) } }
    }

    /** A changed filter (or a figure card's filter): the queue reloads once typing pauses. */
    fun apply(filter: QueueFilter) {
        mutableState.update { it.copy(filter = filter) }
        pendingReload?.cancel()
        pendingReload = viewModelScope.launch {
            delay(300)
            if (mutableState.value.filter != mutableState.value.loadedFilter) reloadQueue()
        }
    }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}

data class CaseDetailUiState(
    val detail: Loadable<ModerationCaseDetail> = Loadable.Idle,
    val message: String? = null,
    val error: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val assignTo: Int? = null,
    val statement: StatementOfReasons = StatementOfReasons(),
    val reference: String = "",
    /** The downloaded evidence archive, ready for the share sheet. */
    val archiveFile: File? = null
)

/** One case: assignment, decision, escalation and the admin steps (like /moderation/cases/{id}). */
@HiltViewModel(assistedFactory = CaseDetailViewModel.Factory::class)
class CaseDetailViewModel @AssistedInject constructor(
    @Assisted val caseId: Int,
    private val repository: StaffRepository,
    private val files: StaffFiles
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(caseId: Int): CaseDetailViewModel
    }

    private val mutableState = MutableStateFlow(CaseDetailUiState())
    val state: StateFlow<CaseDetailUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(detail = it.detail.refreshing()) }
        val result = attempt { repository.moderationCase(caseId) }
        result.onSuccess(::apply)
        result.apiError?.let { failure ->
            mutableState.update {
                it.copy(detail = it.detail.failing(failure), error = if (it.detail.valueOrNull != null) failure.userMessage else it.error)
            }
        }
    }

    fun selectAssignee(id: Int) = mutableState.update { it.copy(assignTo = id) }

    fun updateStatement(statement: StatementOfReasons) = mutableState.update { it.copy(statement = statement) }

    fun updateReference(reference: String) = mutableState.update { it.copy(reference = reference) }

    suspend fun assign() {
        val staffId = mutableState.value.assignTo ?: return
        run { repository.assign(caseId, staffId) }
    }

    suspend fun decide() {
        if (run {
                repository.decide(caseId, mutableState.value.statement)
            }
        ) {
            mutableState.update { it.copy(statement = StatementOfReasons()) }
        }
    }

    suspend fun escalate(type: String) {
        run { repository.escalate(caseId, type) }
    }

    suspend fun recordReport() {
        if (run { repository.recordAuthorityReport(caseId, mutableState.value.reference) }) mutableState.update { it.copy(reference = "") }
    }

    suspend fun extendEvidence() {
        run { repository.extendEvidence(caseId) }
    }

    suspend fun deleteEvidence() {
        run { repository.deleteEvidence(caseId) }
    }

    suspend fun liftQuarantine() {
        run { repository.liftQuarantine(caseId) }
    }

    /** Downloads the evidence ZIP for the police into a file the share sheet hands on. */
    suspend fun prepareArchive() {
        val result = attempt { files.evidenceArchive(caseId, repository.evidenceArchive(caseId)) }
        result.onSuccess { file ->
            mutableState.update { it.copy(archiveFile = file) }
            load()
        }
        result.apiError?.let { failure -> mutableState.update { it.copy(error = failure.userMessage) } }
    }

    private suspend fun run(request: suspend () -> Confirmed<ModerationCaseDetail>): Boolean {
        val result = attempt { request() }
        result.onSuccess { answer ->
            apply(answer.data)
            mutableState.update { it.copy(fieldErrors = emptyMap(), message = answer.message) }
        }
        val failure = result.apiError ?: return true
        val fields = failure.fieldMessages
        mutableState.update { it.copy(fieldErrors = fields, error = if (fields.isEmpty()) failure.userMessage else it.error) }
        return false
    }

    private fun apply(detail: ModerationCaseDetail) = mutableState.update {
        it.copy(detail = Loadable.Loaded(detail), assignTo = detail.assignee?.id ?: it.assignTo ?: detail.assignDefault)
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}

data class ComplaintReviewUiState(
    val complaint: Loadable<ComplaintDetail> = Loadable.Idle,
    val result: String = "",
    val explanation: String = "",
    val statement: StatementOfReasons = StatementOfReasons(),
    val message: String? = null,
    val error: String? = null,
    val fieldErrors: Map<String, String> = emptyMap()
)

/** Review of a complaint (like /moderation/complaints/{id}). */
@HiltViewModel(assistedFactory = ComplaintReviewViewModel.Factory::class)
class ComplaintReviewViewModel @AssistedInject constructor(
    @Assisted private val complaintId: Int,
    private val repository: StaffRepository
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(complaintId: Int): ComplaintReviewViewModel
    }

    private val mutableState = MutableStateFlow(ComplaintReviewUiState())
    val state: StateFlow<ComplaintReviewUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(complaint = it.complaint.refreshing()) }
        val result = attempt { repository.complaint(complaintId) }
        result.onSuccess { complaint -> mutableState.update { it.copy(complaint = Loadable.Loaded(complaint)) } }
        result.apiError?.let { failure ->
            mutableState.update {
                it.copy(
                    complaint = it.complaint.failing(failure),
                    error = if (it.complaint.valueOrNull !=
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

    fun selectResult(result: String) = mutableState.update { it.copy(result = result) }

    fun updateExplanation(explanation: String) = mutableState.update { it.copy(explanation = explanation) }

    fun updateStatement(statement: StatementOfReasons) = mutableState.update { it.copy(statement = statement) }

    suspend fun review() {
        val current = mutableState.value
        val result = attempt { repository.review(complaintId, current.result, current.explanation, current.statement) }
        result.onSuccess { answer ->
            mutableState.update {
                it.copy(
                    complaint = Loadable.Loaded(answer.data),
                    message = answer.message,
                    fieldErrors = emptyMap(),
                    result = "",
                    explanation = "",
                    statement = StatementOfReasons()
                )
            }
        }
        result.apiError?.let { failure ->
            val fields = failure.fieldMessages
            mutableState.update { it.copy(fieldErrors = fields, error = if (fields.isEmpty()) failure.userMessage else it.error) }
        }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}

data class RolesUiState(
    val users: Loadable<List<RoleUser>> = Loadable.Idle,
    val roleNames: List<Labelled> = emptyList(),
    val chosen: Map<Int, String> = emptyMap(),
    val message: String? = null,
    val error: String? = null,
    /** User id → why the server refused the new role, shown at the row. */
    val errors: Map<Int, String> = emptyMap(),
    val hasMore: Boolean = false
)

/** Role management (like /moderation/roles). */
@HiltViewModel
class RolesViewModel @Inject constructor(private val repository: StaffRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(RolesUiState())
    val state: StateFlow<RolesUiState> = mutableState.asStateFlow()
    private var page = 1

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(users = it.users.refreshing()) }
        val result = attempt { repository.roles(1) }
        result.onSuccess { roles ->
            page = 1
            mutableState.update { it.copy(roleNames = roles.roles) }
            take(roles.data, roles.page.nextCursor != null, replacing = true)
        }
        result.apiError?.let { failure ->
            mutableState.update {
                it.copy(
                    users = it.users.failing(failure),
                    error = if (it.users.valueOrNull !=
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
        if (!mutableState.value.hasMore) return
        val result = attempt { repository.roles(page + 1) }
        result.onSuccess { roles ->
            page += 1
            take(roles.data, roles.page.nextCursor != null, replacing = false)
        }
        result.apiError?.let { failure -> mutableState.update { it.copy(error = failure.userMessage) } }
    }

    fun choose(userId: Int, role: String) = mutableState.update { it.copy(chosen = it.chosen + (userId to role)) }

    suspend fun save(user: RoleUser) {
        val role = mutableState.value.chosen[user.id] ?: user.role.value
        val result = attempt { repository.changeRole(user.id, role) }
        result.onSuccess { answer ->
            mutableState.update { state ->
                state.copy(
                    errors = state.errors - user.id,
                    message = answer.message,
                    users =
                        state.users.valueOrNull?.let { users -> Loadable.Loaded(users.map { if (it.id == user.id) answer.data else it }) }
                            ?: state.users
                )
            }
        }
        result.apiError?.let { failure ->
            mutableState.update { it.copy(errors = it.errors + (user.id to (failure.message("role") ?: failure.userMessage))) }
        }
    }

    private fun take(users: List<RoleUser>, hasMore: Boolean, replacing: Boolean) = mutableState.update { state ->
        val chosen = state.chosen.toMutableMap()
        users.forEach { user -> chosen.putIfAbsent(user.id, user.role.value) }
        state.copy(
            users = Loadable.Loaded(if (replacing) users else state.users.valueOrNull.orEmpty() + users),
            chosen = chosen,
            hasMore = hasMore
        )
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}

data class SuspensionsUiState(val list: Loadable<SuspensionList> = Loadable.Idle, val message: String? = null, val error: String? = null)

/** Locked accounts (like /moderation/sperren). */
@HiltViewModel
class SuspensionsViewModel @Inject constructor(private val repository: StaffRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SuspensionsUiState())
    val state: StateFlow<SuspensionsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(list = it.list.refreshing()) }
        val result = attempt { repository.suspensions() }
        result.onSuccess { list -> mutableState.update { it.copy(list = Loadable.Loaded(list)) } }
        result.apiError?.let { failure ->
            mutableState.update {
                it.copy(
                    list = it.list.failing(failure),
                    error = if (it.list.valueOrNull !=
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

    suspend fun release(suspension: Suspension) {
        val result = attempt { repository.release(suspension.id) }
        result.onSuccess { message ->
            mutableState.update { it.copy(message = message) }
            load()
        }
        result.apiError?.let { failure -> mutableState.update { it.copy(error = failure.userMessage) } }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }
}
