package de.velwebsolutions.oasis.staff.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisCard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.AdminIdea
import de.velwebsolutions.oasis.staff.api.AdminVote
import de.velwebsolutions.oasis.staff.api.IdeaStatusInput
import de.velwebsolutions.oasis.staff.api.Labelled
import de.velwebsolutions.oasis.staff.data.StaffRepository
import de.velwebsolutions.oasis.staff.ui.components.OptionPicker
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle
import de.velwebsolutions.oasis.staff.ui.components.apiError
import de.velwebsolutions.oasis.staff.ui.components.attempt
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The ideas page: the ideas, their „Stand setzen“ forms (by idea) and the member votes to link. */
data class AdminIdeasUiState(
    val ideas: Loadable<List<AdminIdea>> = Loadable.Idle,
    val votes: List<AdminVote> = emptyList(),
    val sort: String = "popular",
    /** The page „Weitere Ideen“ loads; null on the last one. */
    val nextPage: Int? = null,
    val forms: Map<Int, IdeaStatusInput> = emptyMap(),
    val fieldErrors: Map<Int, Map<String, String>> = emptyMap(),
    val message: String? = null,
    val error: String? = null
) {
    /** The form of [idea], at first what the idea has. */
    fun form(idea: AdminIdea): IdeaStatusInput = forms[idea.id] ?: IdeaStatusInput(idea.status, idea.statusReply.orEmpty(), idea.vote?.id)
}

/** „Ideen“ for admins (web: the status form on /app/ideen, only for `administrate`): status, reply and linked vote. */
@HiltViewModel
class AdminIdeasViewModel @Inject constructor(private val repository: StaffRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AdminIdeasUiState())
    val state: StateFlow<AdminIdeasUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** The first page; a failed first load shows the error page, a failed refresh a snackbar. */
    suspend fun load() {
        mutableState.update { it.copy(ideas = it.ideas.refreshing()) }
        val result = attempt { repository.ideas(mutableState.value.sort, 1) }
        result.onSuccess { page ->
            mutableState.update {
                it.copy(ideas = Loadable.Loaded(page.data), nextPage = page.nextCursor?.toIntOrNull(), forms = emptyMap())
            }
            attempt { repository.votes() }.onSuccess { votes -> mutableState.update { it.copy(votes = votes) } }
        }
        result.apiError?.let { failure ->
            mutableState.update {
                it.copy(ideas = it.ideas.failing(failure), error = it.ideas.valueOrNull?.let { failure.userMessage } ?: it.error)
            }
        }
    }

    /** „Beliebt“ or „Neu“. */
    suspend fun choose(sort: String) {
        mutableState.update { it.copy(sort = sort) }
        load()
    }

    /** „Weitere Ideen“. */
    suspend fun loadMore() {
        val current = mutableState.value
        val shown = current.ideas.valueOrNull ?: return
        val page = current.nextPage ?: return
        val result = attempt { repository.ideas(current.sort, page) }
        result.apiError?.let { failure -> mutableState.update { it.copy(error = failure.userMessage) } }
        val next = result.getOrNull() ?: return
        val known = shown.map { it.id }.toSet()
        val more = next.data.filterNot { it.id in known }
        mutableState.update { it.copy(ideas = Loadable.Loaded(shown + more), nextPage = next.nextCursor?.toIntOrNull()) }
    }

    /** Changes the form of [idea]. */
    fun edit(idea: AdminIdea, change: (IdeaStatusInput) -> IdeaStatusInput) =
        mutableState.update { it.copy(forms = it.forms + (idea.id to change(it.form(idea)))) }

    /** „Stand speichern“; field errors stay at the idea. */
    suspend fun save(idea: AdminIdea) {
        val shown = mutableState.value.ideas.valueOrNull ?: return
        val input = mutableState.value.form(idea)
        val result = attempt { repository.setIdeaStatus(idea.id, input) }
        result.onSuccess { answer ->
            mutableState.update { state ->
                state.copy(
                    ideas = Loadable.Loaded(shown.map { if (it.id == idea.id) answer.data else it }),
                    forms = state.forms - idea.id,
                    fieldErrors = state.fieldErrors - idea.id,
                    message = answer.message
                )
            }
        }
        result.apiError?.let { failure ->
            val fields = failure.fieldMessages
            mutableState.update {
                it.copy(fieldErrors = it.fieldErrors + (idea.id to fields), error = if (fields.isEmpty()) failure.userMessage else it.error)
            }
        }
    }

    /** Hides the confirmation once the snackbar showed it. */
    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    /** Hides the error once the snackbar showed it. */
    fun dismissError() = mutableState.update { it.copy(error = null) }
}

/** „Ideen“ for admins: each idea with its votes and „Stand setzen“; the author gets a hint. */
@Composable
fun AdminIdeasScreen(viewModel: AdminIdeasViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OasisScreen(
        "Ideen",
        message = state.message,
        error = state.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        StaffLoadable(state.ideas, viewModel::load) { ideas ->
            StaffPage(viewModel::load, spacing = 20) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Admin")
                    StaffTitle("Ideen")
                    Text(
                        "Setz den Stand einer Idee und schreib dazu, wie es steht. Wer die Idee eingereicht hat, bekommt einen Hinweis.",
                        style = OasisType.Small,
                        color = OasisColors.Ink2
                    )
                }
                OptionPicker(
                    "Sortieren",
                    listOf(Labelled("popular", "Beliebt"), Labelled("new", "Neu")),
                    state.sort,
                    { sort -> viewModel.viewModelScope.launch { viewModel.choose(sort) } },
                    Modifier.testTag("ideas.sort")
                )
                if (ideas.isEmpty()) Text("Noch keine Ideen.", style = OasisType.Body, color = OasisColors.Ink2)
                ideas.forEach { idea -> AdminIdeaCard(idea, state, viewModel) }
                if (state.nextPage != null) {
                    AsyncOasisButton(
                        "Weitere Ideen",
                        viewModel::loadMore,
                        Modifier.testTag("ideas.more"),
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false
                    )
                }
            }
        }
    }
}

/** „3 Stimmen“, „1 Stimme“. */
fun ideaVotes(votes: Int): String = if (votes == 1) "1 Stimme" else "$votes Stimmen"

@Composable
private fun AdminIdeaCard(idea: AdminIdea, state: AdminIdeasUiState, viewModel: AdminIdeasViewModel) {
    val form = state.form(idea)
    val errors = state.fieldErrors[idea.id].orEmpty()
    OasisCard(Modifier.testTag("idea.${idea.id}"), padding = 16) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "${idea.statusLabel} · ${ideaVotes(idea.votes)} · ${idea.author?.displayName ?: "Unbekannt"}",
                style = OasisType.Caption,
                color = OasisColors.Muted
            )
            Text(idea.title, style = OasisType.BodyBold, color = OasisColors.Night)
            idea.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = OasisType.Small, color = OasisColors.Ink2) }
            Text("Stand setzen (nur Betreiber)", style = OasisType.SmallBold, color = OasisColors.Night)
            OptionPicker(
                "Stand",
                AdminIdea.statuses,
                form.status,
                { status -> viewModel.edit(idea) { it.copy(status = status) } },
                Modifier.testTag("idea.${idea.id}.status")
            )
            errors["status"]?.let { FieldError(it) }
            OasisTextField(
                "Antwort (optional)",
                form.reply,
                { reply -> viewModel.edit(idea) { it.copy(reply = reply) } },
                Modifier.testTag("idea.${idea.id}.reply"),
                error = errors["reply"],
                lines = 2..6,
                maxLength = 1000
            )
            OptionPicker(
                "Abstimmung (optional)",
                listOf(Labelled("", "Keine Abstimmung")) + state.votes.map { Labelled("${it.id}", it.title) },
                form.voteId?.toString().orEmpty(),
                { vote -> viewModel.edit(idea) { it.copy(voteId = vote.toIntOrNull()) } },
                Modifier.testTag("idea.${idea.id}.vote")
            )
            errors["vote_id"]?.let { FieldError(it) }
            AsyncOasisButton("Stand speichern", { viewModel.save(idea) }, Modifier.testTag("idea.${idea.id}.save"), fullWidth = false)
        }
    }
}
