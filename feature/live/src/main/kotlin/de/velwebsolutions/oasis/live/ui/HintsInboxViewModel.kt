package de.velwebsolutions.oasis.live.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.map
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.live.LiveCoordinator
import de.velwebsolutions.oasis.live.api.Hint
import de.velwebsolutions.oasis.live.catching
import de.velwebsolutions.oasis.live.data.LiveRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The hints under one heading ("Heute", "Diese Woche", "Früher"). */
data class HintGroup(val title: String, val hints: List<Hint>)

/** What the Hinweise list shows. */
data class HintsInboxUiState(
    val state: Loadable<List<Hint>> = Loadable.Idle,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    /**
     * The end of the list offers "Ältere Hinweise laden", because scrolling alone would not go on: the next page failed,
     * or several pages in a row held no hint that is still shown.
     */
    val offersMore: Boolean = false,
    /** Confirmation of the last action ("Alle Hinweise sind gelesen."). */
    val message: String? = null,
    val error: String? = null
) {
    /** The loaded hints under their headings, in list order. */
    val groups: List<HintGroup>
        get() = state.valueOrNull.orEmpty().groupBy { it.group }.map { (title, hints) -> HintGroup(title, hints) }

    /** Whether a loaded hint still shows "Neu". */
    val showsUnread: Boolean get() = state.valueOrNull.orEmpty().any { it.unread }
}

/**
 * The Hinweise list (/app/hinweise): pages of 20, newest first, grouped under "Heute", "Diese Woche" and "Früher".
 * Loading a page marks its hints read on the server (they show "Neu" this once), so the counters are refreshed after.
 */
@HiltViewModel
class HintsInboxViewModel @Inject constructor(private val repository: LiveRepository, private val live: LiveCoordinator) : ViewModel() {
    private val mutableUi = MutableStateFlow(HintsInboxUiState())
    val ui: StateFlow<HintsInboxUiState> = mutableUi.asStateFlow()

    /** The last page in the list. */
    private var page = 1

    /** Counts the lists loaded anew by [load]: a page asked for before belongs to the old list and is dropped. */
    private var generation = 0

    /** Counts the successful "Alle als gelesen markieren": a page asked for before one arrives read, see [hintsOf]. */
    private var allMarkedRead = 0

    /** Unread hints may wait on later pages: the bell counts them. */
    val unreadAtTheBell: StateFlow<Int> = live.hints

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        val read = allMarkedRead
        catching { repository.hints(1) }
            .onSuccess { first ->
                generation += 1
                page = 1
                mutableUi.update {
                    it.copy(
                        state = Loadable.Loaded(hintsOf(first, read)),
                        hasMore = first.nextCursor != null,
                        isLoadingMore = false,
                        offersMore = false
                    )
                }
                // Without a row there is no end of the list to come into view: the next pages follow at once.
                if (first.data.isEmpty()) loadMore()
                live.refresh()
            }
            .onFailure { error ->
                mutableUi.update {
                    val failing = it.state.failing(error)
                    it.copy(state = failing, error = if (failing.valueOrNull != null) error.asApiError().userMessage else it.error)
                }
            }
    }

    /**
     * The next page, when the end of the list appears (and after a first page without a hint still shown, see [load]). A
     * page may hold fewer than 20 visible hints, or none, when content was removed; as such a page leaves the end of the
     * list where it was, the next one follows at once, up to [PAGES_AT_ONCE] pages. After that, or when a page fails, the
     * end of the list offers to go on ([HintsInboxUiState.offersMore]). The loading ends before the bell is asked again:
     * after a page with few hints the new last row is in view at once and asks for the page after it.
     */
    fun loadMore() {
        val current = ui.value
        if (current.state.valueOrNull == null || !current.hasMore || current.isLoadingMore) return
        mutableUi.update { it.copy(isLoadingMore = true, offersMore = false) }
        val started = generation
        viewModelScope.launch {
            val arrived = loadPages(started)
            // A list loaded anew meanwhile ended this loading already.
            if (started == generation) mutableUi.update { it.copy(isLoadingMore = false) }
            if (arrived) live.refresh()
        }
    }

    /**
     * Loads the pages after the shown ones until one adds a hint or none is left; true when a page arrived (its hints are
     * read now). Each page joins the list as it is now, so "Alle als gelesen markieren" meanwhile keeps the "Neu" marks
     * away, those of the page included ([hintsOf]); after a new [load] it belongs to the old list.
     */
    private suspend fun loadPages(started: Int): Boolean {
        var arrived = false
        repeat(PAGES_AT_ONCE) {
            val number = page + 1
            val read = allMarkedRead
            val answer = catching { repository.hints(number) }
            if (started != generation) return arrived
            val next = answer.getOrElse { error ->
                mutableUi.update { it.copy(error = error.asApiError().userMessage, offersMore = true) }
                return arrived
            }
            arrived = true
            page = number
            val hints = hintsOf(next, read)
            var added = 0
            mutableUi.update { state ->
                state.copy(
                    state = state.state.map { shown ->
                        val known = shown.map { it.id }.toSet()
                        val fresh = hints.filterNot { hint -> hint.id in known }
                        added = fresh.size
                        shown + fresh
                    },
                    hasMore = next.nextCursor != null
                )
            }
            if (added > 0 || next.nextCursor == null) return true
        }
        mutableUi.update { it.copy(offersMore = true) }
        return true
    }

    /**
     * The hints of a page ([answer]) asked for after [read] successful "Alle als gelesen markieren". When it succeeded
     * again while the page was on its way, the server may have opened the page first and sent its hints as unread: they
     * come without "Neu", like the rest of the list.
     */
    private fun hintsOf(answer: Page<Hint>, read: Int): List<Hint> =
        if (read == allMarkedRead) answer.data else answer.data.map { it.copy(unread = false) }

    /**
     * "Alle als gelesen markieren": also hints on later pages; the "Neu" marks of the list go too, like the web, and so do
     * those of a page still on its way.
     */
    suspend fun markAllRead() {
        catching { repository.markAllRead() }
            .onSuccess { message ->
                allMarkedRead += 1
                mutableUi.update { state ->
                    val read = state.state.valueOrNull?.let { hints -> Loadable.Loaded(hints.map { it.copy(unread = false) }) }
                    state.copy(message = message, state = read ?: state.state)
                }
                live.refresh()
            }
            .onFailure { error -> mutableUi.update { it.copy(error = error.asApiError().userMessage) } }
    }

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

    fun dismissError() = mutableUi.update { it.copy(error = null) }

    private companion object {
        /** Pages loaded one after the other while they add no hint, before the end of the list asks to go on. */
        const val PAGES_AT_ONCE = 5
    }
}
