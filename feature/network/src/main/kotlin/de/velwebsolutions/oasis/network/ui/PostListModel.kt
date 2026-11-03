package de.velwebsolutions.oasis.network.ui

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.network.NetworkSignals
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
    val actionError: ApiError? = null
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
    /** The Sammlung this list shows: a Tropfen taken out of it leaves the list. */
    private val collectionId: Int? = null,
    private val fetch: suspend NetworkRepository.(filter: String?, cursor: String?) -> FeedPage<C>
) {
    private val mutableState = MutableStateFlow(PostListState<C>(filter = filter))
    val state: StateFlow<PostListState<C>> = mutableState.asStateFlow()

    private val current: PostListState<C> get() = mutableState.value

    @Volatile
    private var generation = 0
    private val busy = BusyCards()

    val posts: List<Post> get() = current.posts

    var filter: String?
        get() = current.filter
        set(value) = mutableState.update { it.copy(filter = value) }

    /**
     * Load the first page; keeps the shown list until the new one arrives (pull to refresh, a new filter). Older Tropfen
     * still on their way belong to the list that goes and are dropped. When the caller goes away meanwhile (a rotation
     * cancels the screen's coroutine), the list stops showing that it reloads, and a first page that never came loads
     * again with [loadIfNeeded].
     */
    suspend fun reload() {
        val started = ++generation
        mutableState.update { it.copy(state = it.state.refreshing(), isReloading = true, isLoadingMore = false) }
        try {
            val result = attempt { repository.fetch(current.filter, null) }
            // A list forgotten or reloaded meanwhile ignores the late answer.
            if (started != generation) return
            result.onSuccess { page ->
                mutableState.update {
                    it.copy(
                        posts = page.data,
                        context = page.context,
                        nextCursor = page.nextCursor,
                        loadMoreError = null,
                        state = Loadable.Loaded(Unit)
                    )
                }
            }
            result.apiError?.let { error ->
                mutableState.update {
                    if (it.state.valueOrNull ==
                        null
                    ) {
                        it.copy(state = Loadable.Failed(error))
                    } else {
                        it.copy(actionError = error)
                    }
                }
            }
        } finally {
            if (started == generation) {
                mutableState.update { it.copy(state = if (it.state == Loadable.Loading) Loadable.Idle else it.state, isReloading = false) }
            }
        }
    }

    /** Forget the list (a search term that became too short), so nothing stale shows up when it is long enough again. */
    fun reset() {
        generation++
        mutableState.update {
            it.copy(
                posts = emptyList(),
                context = null,
                nextCursor = null,
                state = Loadable.Idle,
                isReloading = false,
                isLoadingMore = false,
                loadMoreError = null
            )
        }
    }

    /** Load the first page once (again, when the load before was cancelled before its answer came). */
    suspend fun loadIfNeeded() {
        if (current.state == Loadable.Idle) reload()
    }

    /**
     * The next page, when the last card appears. Not while the first page loads again: the page would belong to the list
     * that goes (the other view of the Quelle, an older state). A reload or [reset] meanwhile ends this load, and its late
     * page or error is dropped.
     */
    suspend fun loadMore() {
        val cursor = current.nextCursor ?: return
        if (current.isLoadingMore || current.isReloading) return
        mutableState.update { it.copy(isLoadingMore = true) }
        val started = generation
        val result = attempt { repository.fetch(current.filter, cursor) }
        if (started != generation) return
        // An older page the app cannot read ends the list quietly: trying again would bring the same answer.
        val unreadable = result.apiError is ApiError.Decoding
        mutableState.update {
            it.copy(
                isLoadingMore = false,
                loadMoreError = result.apiError.takeUnless { unreadable },
                nextCursor = if (unreadable) null else it.nextCursor
            )
        }
        result.onSuccess { page ->
            mutableState.update { state ->
                val known = state.posts.map { it.id }.toSet()
                state.copy(posts = state.posts + page.data.filterNot { it.id in known }, nextCursor = page.nextCursor)
            }
        }
    }

    /**
     * A card action. Reactions and bookmarks change at once and roll back if the server refuses; everything else changes
     * the list after the server's confirmation, like the web's re-query. A card takes no second action while one runs.
     */
    suspend fun perform(action: PostAction, post: Post) {
        busy.run(post.id) { run(action, post) }
    }

    private suspend fun run(action: PostAction, post: Post) {
        val undo = action.undo?.takeUnless { action == PostAction.Unbookmark && dropsUnbookmarked }
        val optimistic = undo != null
        if (optimistic) apply(action, post)
        val result = attempt { repository.perform(action, post) }
        result.onSuccess { message -> mutableState.update { it.copy(status = message) } }
        mutableState.update { it.copy(actionError = result.apiError) }
        if (result.isSuccess && !optimistic) apply(action, post)
        if (result.isFailure && undo != null) mutableState.update { it.copy(posts = it.posts.applying(undo, post)) }
    }

    /** A vote for answer [index] of the Tropfen's poll; the answer replaces the Tropfen with its new counts. */
    suspend fun vote(post: Post, index: Int) {
        val poll = post.poll ?: return
        changePoll { repository.votePoll(post.id, poll.choosing(index)) }
    }

    /** „Umfrage beenden“ (the author, after confirming). */
    suspend fun closePoll(post: Post) = changePoll { repository.closePoll(post.id) }

    private suspend fun changePoll(request: suspend () -> Confirmed<Post>) {
        val result = attempt(request)
        result.onSuccess { answer ->
            mutableState.update { state ->
                state.copy(
                    posts = state.posts.map {
                        if (it.id ==
                            answer.data.id
                        ) {
                            answer.data
                        } else {
                            it
                        }
                    },
                    status = answer.message
                )
            }
        }
        mutableState.update { it.copy(actionError = result.apiError) }
    }

    /** „Aus „X“ entfernen“: the card no longer names that collection; a collection's own list drops the Tropfen. */
    suspend fun removeFromCollection(post: Post, collection: Int) {
        val result = attempt { repository.uncollectPost(collection, post.id) }
        result.onSuccess { answer ->
            mutableState.update { state ->
                val changed = state.posts.map { item ->
                    if (item.id == post.id) item.copy(collections = item.collections.filterNot { it.id == collection }) else item
                }
                state.copy(
                    posts = if (collectionId ==
                        collection
                    ) {
                        changed.filterNot { it.id == post.id }
                    } else {
                        changed
                    },
                    status = answer.message
                )
            }
            NetworkSignals.didChangeCollections()
        }
        mutableState.update { it.copy(actionError = result.apiError) }
    }

    /** Put a just-published Tropfen on top. */
    fun insert(post: Post) {
        mutableState.update { state -> state.copy(posts = listOf(post) + state.posts.filterNot { it.id == post.id }) }
    }

    /** The confirmation or the error of an action outside the cards (the Quelle's „Ausblenden“). */
    fun answered(result: Result<String>) =
        mutableState.update { state -> state.copy(status = result.getOrNull() ?: state.status, actionError = result.apiError) }

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
