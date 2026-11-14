package de.velwebsolutions.oasis.network.ui.feeds

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.api.Idea
import de.velwebsolutions.oasis.network.api.IdeaInput
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.WeeklyLetter
import de.velwebsolutions.oasis.network.api.WeeklyLetterSummary
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.BusyCards
import de.velwebsolutions.oasis.network.ui.NetworkViewModel
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.apiError
import de.velwebsolutions.oasis.network.ui.applying
import de.velwebsolutions.oasis.network.ui.attempt
import de.velwebsolutions.oasis.network.ui.perform
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** „26. Oktober 2026“ of a `YYYY-MM-DD` in [pattern]; the text itself when it is no date. */
fun letterDay(text: String, pattern: String): String =
    runCatching { LocalDate.parse(text).format(DateTimeFormatter.ofPattern(pattern, Locale.GERMAN)) }.getOrDefault(text)

/** „Woche vom 26. Oktober bis 1. November 2026“. */
fun letterWeek(start: String, end: String): String = "Woche vom ${letterDay(start, "d. MMMM")} bis ${letterDay(end, "d. MMMM yyyy")}"

/** „Ein neues Mitglied diese Woche.“ / „3 neue Mitglieder diese Woche.“ */
fun newMembersText(count: Int): String = (if (count == 1) "Ein neues Mitglied" else "$count neue Mitglieder") + " diese Woche."

/**
 * „Dein Crowd-Wochenbrief“ (/app/wochenbrief/{id?}): one letter – the newest when none is named – and the earlier
 * ones. Card actions change the Posts of the letter; it is not loaded again.
 */
@HiltViewModel(assistedFactory = WeeklyLetterViewModel.Factory::class)
class WeeklyLetterViewModel @AssistedInject constructor(@Assisted id: Int?, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(id: Int?): WeeklyLetterViewModel
    }

    private var id: Int? = id
    private val mutableLetter = MutableStateFlow<Loadable<WeeklyLetter?>>(Loadable.Idle)

    /** The letter; loaded `null` while there is none yet. */
    val letter: StateFlow<Loadable<WeeklyLetter?>> = mutableLetter.asStateFlow()
    private val mutableEarlier = MutableStateFlow<List<WeeklyLetterSummary>>(emptyList())

    /** „Frühere Wochenbriefe“ (the last 12, the shown one among them). */
    val earlier: StateFlow<List<WeeklyLetterSummary>> = mutableEarlier.asStateFlow()

    suspend fun load() {
        mutableLetter.load { repository.weeklyLetter(id) }
        attempt { repository.weeklyLetters() }.onSuccess { mutableEarlier.value = it }
    }

    suspend fun loadIfNeeded() {
        if (letter.value is Loadable.Idle) load()
    }

    /** Another letter from „Frühere Wochenbriefe“. */
    suspend fun show(id: Int) {
        this.id = id
        load()
    }

    private val busy = BusyCards()

    /** Reactions and bookmarks change at once and roll back if the server refuses; one action per card at a time. */
    suspend fun perform(action: PostAction, post: Post) {
        busy.run(post.id) { run(action, post) }
    }

    private suspend fun run(action: PostAction, post: Post) {
        val undo = action.undo
        if (undo != null) apply(action, post)
        val result = attempt { repository.perform(action, post) }
        result.onSuccess { mutableStatus.value = it }
        mutableError.value = result.apiError
        // Reactions and bookmarks already changed: roll them back on failure; the rest follows the confirmation.
        if (result.isSuccess == (undo == null)) apply(undo ?: action, post)
    }

    private fun apply(action: PostAction, post: Post) {
        val current = letter.value.valueOrNull ?: return
        val oases = current.oases.map { it.copy(posts = it.posts.applying(action, post)) }
        mutableLetter.value = Loadable.Loaded(current.copy(oases = oases))
    }
}

/** The shown ideas and the page „Weitere Ideen“ loads ([nextPage] null on the last one). */
data class IdeaList(val ideas: List<Idea> = emptyList(), val nextPage: Int? = null)

/** The suggest form of „Ideen für Mildcrowd“: title (3–100) and description (≤ 1000); [error] keeps a refusal. */
data class IdeaForm(val title: String = "", val description: String = "", val error: ApiError? = null)

/**
 * „Ideen für Mildcrowd“ (/app/ideen/{id?}, docs/mildcrowd-admin.md): suggest, vote (one per idea, withdrawable), sort, filter
 * by status, delete the own while open; the idea of an opened link stands in front.
 */
@HiltViewModel(assistedFactory = IdeasViewModel.Factory::class)
class IdeasViewModel @AssistedInject constructor(@Assisted private val focusedId: Int?, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(focusedId: Int?): IdeasViewModel
    }

    private val mutableIdeas = MutableStateFlow<Loadable<IdeaList>>(Loadable.Idle)

    /** The pages loaded so far in the chosen order and status. */
    val ideas: StateFlow<Loadable<IdeaList>> = mutableIdeas.asStateFlow()
    private val mutableFocused = MutableStateFlow<Idea?>(null)

    /** The idea of the opened link, once it loaded (gone once deleted). */
    val focused: StateFlow<Idea?> = mutableFocused.asStateFlow()
    private val mutableSort = MutableStateFlow(POPULAR)

    /** `popular` („Beliebt“) or `new` („Neu“). */
    val sort: StateFlow<String> = mutableSort.asStateFlow()
    private val mutableStatusFilter = MutableStateFlow("")

    /** One of [Idea.statuses], "" for all. */
    val statusFilter: StateFlow<String> = mutableStatusFilter.asStateFlow()
    private val mutableForm = MutableStateFlow<IdeaForm?>(null)

    /** The suggest form while it is open. */
    val form: StateFlow<IdeaForm?> = mutableForm.asStateFlow()
    private var focusLoaded = false

    /** The first page (and, once, the idea of the link). */
    suspend fun load() {
        mutableIdeas.load {
            val page = repository.ideas(sort.value, statusFilter.value.ifEmpty { null }, 1)
            IdeaList(page.data, page.nextCursor?.toIntOrNull())
        } ?: return
        val id = focusedId?.takeUnless { focusLoaded } ?: return
        focusLoaded = true
        mutableFocused.value = attempt { repository.idea(id) }.getOrNull()
    }

    suspend fun loadIfNeeded() {
        if (ideas.value is Loadable.Idle) load()
    }

    /** „Weitere Ideen“. */
    suspend fun loadMore() {
        val shown = ideas.value.valueOrNull ?: return
        val page = shown.nextPage ?: return
        val result = attempt { repository.ideas(sort.value, statusFilter.value.ifEmpty { null }, page) }
        mutableError.value = result.apiError
        val next = result.getOrNull() ?: return
        val known = shown.ideas.map { it.id }.toSet()
        mutableIdeas.value = Loadable.Loaded(IdeaList(shown.ideas + next.data.filterNot { it.id in known }, next.nextCursor?.toIntOrNull()))
    }

    suspend fun choose(sort: String) {
        mutableSort.value = sort
        load()
    }

    suspend fun filter(status: String) {
        mutableStatusFilter.value = status
        load()
    }

    /** „Idee vorschlagen“. */
    fun openForm() {
        mutableForm.value = IdeaForm()
    }

    fun editForm(change: (IdeaForm) -> IdeaForm) = mutableForm.update { it?.let(change) }

    fun closeForm() {
        mutableForm.value = null
    }

    /** „Idee einreichen“: the list again once it is in; a refusal stays at its field. */
    suspend fun suggest() {
        val form = form.value ?: return
        val result = attempt { repository.suggestIdea(IdeaInput(form.title.trim(), form.description.trim())) }
        val answer = result.getOrNull()
        if (answer == null) {
            mutableForm.value = form.copy(error = result.apiError)
            return
        }
        mutableStatus.value = answer.message
        mutableForm.value = null
        load()
    }

    /** „Für diese Idee stimmen“ / „Stimme zurücknehmen“. */
    suspend fun toggleVote(idea: Idea) {
        val result = attempt { repository.voteIdea(idea.id, !idea.voted) }
        mutableError.value = result.apiError
        val answer = result.getOrNull() ?: return
        mutableStatus.value = answer.message
        replace(answer.data)
    }

    /** „Löschen“ of the own idea (after confirming). */
    suspend fun delete(idea: Idea) {
        if (!confirm { repository.deleteIdea(idea.id) }) return
        mutableFocused.update { focused -> focused?.takeUnless { it.id == idea.id } }
        load()
    }

    private fun replace(idea: Idea) {
        mutableIdeas.update { shown ->
            shown.valueOrNull?.let { list -> Loadable.Loaded(list.copy(ideas = list.ideas.map { if (it.id == idea.id) idea else it })) }
                ?: shown
        }
        mutableFocused.update { focused -> if (focused?.id == idea.id) idea else focused }
    }

    companion object {
        const val POPULAR = "popular"
    }
}
