package de.velwebsolutions.oasis.network.ui

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.data.NetworkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What a list of Tropfen shows. */
data class PostListState<C>(
    val posts: List<Post> = emptyList(),
    val context: C? = null,
    /** The first page: loading, failed (nothing to show) or loaded; a failed refresh keeps the list ([actionError]). */
    val state: Loadable<Unit> = Loadable.Idle,
    /** A refresh or a new filter is loading while the current list stays on screen. */
    val isReloading: Boolean = false,
    val filter: String? = null,
    val nextCursor: String? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreError: ApiError? = null,
    /** The confirmation of the last action, shown as a toast. */
    val status: String? = null,
    /** An action or refresh that failed. */
    val actionError: ApiError? = null,
) {
    /** Whether every page is loaded (the list shows its honest end). */
    val reachedEnd: Boolean get() = state.valueOrNull != null && nextCursor == null
}

/**
 * A chronological list of Tropfen with cursor paging (Quelle, Entdecken, oasis, profile, topic, search, Merkliste): the
 * first page with its side information, "Ältere Tropfen" by scrolling, pull to refresh and the card actions. `filter` is
 * the list's one variable input (the Quelle's view, the search term); after changing it, [reload] keeps the current list
 * on screen until the new one arrives.
 */
class PostListModel<C>(
    private val repository: NetworkRepository,
    filter: String? = null,
    /** The Merkliste drops a Tropfen once it is no longer bookmarked, like the web's re-query. */
    private val dropsUnbookmarked: Boolean = false,
    private val fetch: suspend NetworkRepository.(filter: String?, cursor: String?) -> FeedPage<C>,
) {
    private val mutableState = MutableStateFlow(PostListState<C>(filter = filter))
    val state: StateFlow<PostListState<C>> = mutableState.asStateFlow()

    private val current: PostListState<C> get() = mutableState.value

    @Volatile
    private var generation = 0

    val posts: List<Post> get() = current.posts

    var filter: String?
        get() = current.filter
        set(value) = mutableState.update { it.copy(filter = value) }

    /** Load the first page; keeps the shown list until the new one arrives (pull to refresh, a new filter). */
    suspend fun reload() {
        val started = ++generation
        mutableState.update { it.copy(state = it.state.refreshing(), isReloading = true) }
        try {
            val page = repository.fetch(current.filter, null)
            if (started != generation) return
            mutableState.update {
                it.copy(posts = page.data, context = page.context, nextCursor = page.nextCursor, loadMoreError = null, state = Loadable.Loaded(Unit))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (started != generation) return
            mutableState.update {
                if (it.state.valueOrNull == null) it.copy(state = it.state.failing(error)) else it.copy(actionError = ApiError.from(error))
            }
        } finally {
            if (started == generation) mutableState.update { it.copy(isReloading = false) }
        }
    }

    /** Forget the list (a search term that became too short), so nothing stale shows up when it is long enough again. */
    fun reset() {
        generation++
        mutableState.update { it.copy(posts = emptyList(), context = null, nextCursor = null, state = Loadable.Idle, isReloading = false) }
    }

    /** Load the first page once. */
    suspend fun loadIfNeeded() {
        if (current.state == Loadable.Idle) reload()
    }

    /** The next page, when the last card appears. */
    suspend fun loadMore() {
        val cursor = current.nextCursor ?: return
        if (current.isLoadingMore || current.state.valueOrNull == null) return
        mutableState.update { it.copy(isLoadingMore = true) }
        val started = generation
        try {
            val page = repository.fetch(current.filter, cursor)
            if (started != generation) return
            mutableState.update { state ->
                val known = state.posts.map { it.id }.toSet()
                state.copy(posts = state.posts + page.data.filterNot { it.id in known }, nextCursor = page.nextCursor, loadMoreError = null)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(loadMoreError = ApiError.from(error)) }
        } finally {
            mutableState.update { it.copy(isLoadingMore = false) }
        }
    }

    /**
     * A card action. Reactions and bookmarks change at once and roll back if the server refuses; everything else changes
     * the list after the server's confirmation, like the web's re-query.
     */
    suspend fun perform(action: PostAction, post: Post) {
        val undo = action.undo
        val optimistic = undo != null && !(action == PostAction.Unbookmark && dropsUnbookmarked)
        if (optimistic) apply(action, post)
        try {
            val message = repository.perform(action, post)
            mutableState.update { it.copy(status = message, actionError = null) }
            if (!optimistic) apply(action, post)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (optimistic && undo != null) mutableState.update { it.copy(posts = it.posts.applying(undo, post)) }
            mutableState.update { it.copy(actionError = ApiError.from(error)) }
        }
    }

    /** Put a just-published Tropfen on top. */
    fun insert(post: Post) {
        mutableState.update { state -> state.copy(posts = listOf(post) + state.posts.filterNot { it.id == post.id }) }
    }

    fun dismissStatus() = mutableState.update { it.copy(status = null) }

    fun dismissError() = mutableState.update { it.copy(actionError = null) }

    private fun apply(action: PostAction, post: Post) {
        mutableState.update { state ->
            if (action == PostAction.Unbookmark && dropsUnbookmarked) {
                state.copy(posts = state.posts.filterNot { it.id == post.id })
            } else {
                state.copy(posts = state.posts.applying(action, post))
            }
        }
    }
}
