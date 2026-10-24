package de.velwebsolutions.oasis.live.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.live.LiveCoordinator
import de.velwebsolutions.oasis.live.api.Hint
import de.velwebsolutions.oasis.live.data.LiveRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
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
    /** Confirmation of the last action ("Alle Hinweise sind gelesen."). */
    val message: String? = null,
    val error: String? = null
) {
    /** The loaded hints under their headings, in list order. */
    val groups: List<HintGroup>
        get() = (state.valueOrNull ?: emptyList()).groupBy { it.group }.map { (title, hints) -> HintGroup(title, hints) }

    /** Whether a loaded hint still shows "Neu". */
    val showsUnread: Boolean get() = state.valueOrNull?.any { it.unread } ?: false
}

/**
 * The Hinweise list (/app/hinweise): pages of 20, newest first, grouped under "Heute", "Diese Woche" and "Früher".
 * Loading a page marks its hints read on the server (they show "Neu" this once), so the counters are refreshed after.
 */
@HiltViewModel
class HintsInboxViewModel @Inject constructor(private val repository: LiveRepository, private val live: LiveCoordinator) : ViewModel() {
    private val mutableUi = MutableStateFlow(HintsInboxUiState())
    val ui: StateFlow<HintsInboxUiState> = mutableUi.asStateFlow()
    private var page = 1

    /** Unread hints may wait on later pages: the bell counts them. */
    val unreadAtTheBell: StateFlow<Int> = live.hints

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        try {
            val first = repository.hints(1)
            page = 1
            mutableUi.update { it.copy(state = Loadable.Loaded(first.data), hasMore = first.nextCursor != null) }
            live.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableUi.update {
                val failing = it.state.failing(error)
                it.copy(state = failing, error = if (failing.valueOrNull != null) error.asApiError().userMessage else it.error)
            }
        }
    }

    /** The next page (a page may hold fewer than 20 visible hints, or none, when content was removed). */
    fun loadMore() {
        val current = ui.value
        val hints = current.state.valueOrNull ?: return
        if (!current.hasMore || current.isLoadingMore) return
        mutableUi.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            try {
                val next = repository.hints(page + 1)
                page += 1
                val known = hints.map { it.id }.toSet()
                mutableUi.update {
                    it.copy(
                        state = Loadable.Loaded(hints + next.data.filterNot { hint -> hint.id in known }),
                        hasMore =
                            next.nextCursor != null
                    )
                }
                live.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableUi.update { it.copy(error = error.asApiError().userMessage) }
            } finally {
                mutableUi.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    /** "Alle als gelesen markieren": also hints on later pages; the "Neu" marks of the list go too, like the web. */
    suspend fun markAllRead() {
        try {
            val message = repository.markAllRead()
            mutableUi.update { state ->
                state.copy(
                    message = message,
                    state =
                        state.state.valueOrNull?.let { hints -> Loadable.Loaded(hints.map { it.copy(unread = false) }) } ?: state.state
                )
            }
            live.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableUi.update { it.copy(error = error.asApiError().userMessage) }
        }
    }

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

    fun dismissError() = mutableUi.update { it.copy(error = null) }
}
