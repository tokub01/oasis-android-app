package de.velwebsolutions.oasis.network.ui.feeds

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.network.api.DiscoverContext
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.SearchContext
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.api.SwipeDeck
import de.velwebsolutions.oasis.network.api.TagContext
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.NetworkViewModel
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.PostListModel
import de.velwebsolutions.oasis.network.ui.applying
import de.velwebsolutions.oasis.network.ui.perform
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A list screen around a [PostListModel]: after a Tropfen was written it is put on top where [belongsOnTop] says it
 * belongs (and the page scrolls up, [scrollToTop]), otherwise the list loads again.
 */
abstract class FeedViewModel<C>(val list: PostListModel<C>) : NetworkViewModel() {
    private val mutableScrollToTop = MutableStateFlow(0)

    /** Changes whenever the page should scroll back to the top. */
    val scrollToTop: StateFlow<Int> = mutableScrollToTop.asStateFlow()

    init {
        observe(NetworkSignals.published) {
            val post = NetworkSignals.lastPublished
            if (post != null && belongsOnTop(post)) {
                list.insert(post)
                mutableScrollToTop.update { it + 1 }
            } else {
                list.reload()
            }
        }
    }

    /** Whether a just-written Tropfen goes on top of this list instead of loading it again. */
    protected open fun belongsOnTop(post: Post): Boolean = false
}

/** Quelle (/app): own Tropfen and those of friends, followed people and oases – or "Ganz Oasis". */
@HiltViewModel
class SourceViewModel @Inject constructor(repository: NetworkRepository, session: Session) :
    FeedViewModel<SourceContext>(PostListModel(repository) { view, cursor -> source(view, cursor) }) {
    val session: StateFlow<SessionState> = session.state

    /** The member already saw (or was sent to) the welcome tour in this session. */
    var askedWelcome = false

    /** Switches the view ("gefolgt" / "alle") and loads it, keeping the cards on screen meanwhile. */
    fun selectView(view: String) {
        list.filter = view
        launch { list.reload() }
    }

    /** An own new Tropfen goes on top, unless it is a reply (bundled under its Tropfen) or not public in "Ganz Oasis". */
    override fun belongsOnTop(post: Post): Boolean {
        val showAll = list.state.value.context?.showAll ?: false
        return post.replyTo == null && (!showAll || post.visibility == "public")
    }
}

/** Entdecken (/app/entdecken): public Tropfen of public profiles, newest first, and the largest oases. */
@HiltViewModel
class DiscoverViewModel @Inject constructor(repository: NetworkRepository) :
    FeedViewModel<DiscoverContext>(PostListModel(repository) { _, cursor -> discover(cursor) })

/** Gemerkt (/app/gemerkt): the private bookmark list; a Tropfen leaves it once it is no longer bookmarked. */
@HiltViewModel
class BookmarksViewModel @Inject constructor(repository: NetworkRepository) :
    FeedViewModel<NoContext>(PostListModel(repository, dropsUnbookmarked = true) { _, cursor -> bookmarks(cursor) })

/** A topic page (/themen/{tag}). */
@HiltViewModel(assistedFactory = TagViewModel.Factory::class)
class TagViewModel @AssistedInject constructor(@Assisted tag: String, repository: NetworkRepository) :
    FeedViewModel<TagContext>(PostListModel(repository) { _, cursor -> this.tag(tag, cursor) }) {
    @AssistedFactory
    interface Factory {
        fun create(tag: String): TagViewModel
    }
}

/**
 * The member search (/app/suche): people, oases and Tropfen, updated while typing; the results stay on screen while the
 * next ones load. Every request counts against the search budget of 30 per minute.
 */
@HiltViewModel(assistedFactory = SearchViewModel.Factory::class)
class SearchViewModel @AssistedInject constructor(@Assisted query: String, repository: NetworkRepository) :
    FeedViewModel<SearchContext>(PostListModel(repository, filter = query) { term, cursor -> search(term.orEmpty(), cursor) }) {
    @AssistedFactory
    interface Factory {
        fun create(query: String): SearchViewModel
    }

    private val mutableQuery = MutableStateFlow(query)
    val query: StateFlow<String> = mutableQuery.asStateFlow()

    fun updateQuery(text: String) {
        mutableQuery.value = text
    }

    /** Searches for the current term (called 300 ms after typing stopped); a short term forgets the results. */
    suspend fun search() {
        val text = query.value
        if (text.length > MAX_LENGTH) return
        if (text.trim().length < 2) {
            list.reset()
            return
        }
        list.filter = text
        list.reload()
    }

    companion object {
        const val MAX_LENGTH = 100

        /** The message for a failed search; the budget gets its own words. */
        fun message(error: ApiError): String =
            if (error is ApiError.TooManyRequests) "Du hast gerade viel gesucht. Warte bitte eine Minute." else error.userMessage
    }
}

/**
 * Durchblättern (/app/durchblaettern): the deck's chapters and the end card. Card actions change the cards' state; the
 * deck is not queried again.
 */
@HiltViewModel
class SwipeViewModel @Inject constructor(private val repository: NetworkRepository) : NetworkViewModel() {
    private val mutableDeck = MutableStateFlow<Loadable<SwipeDeck>>(Loadable.Idle)
    val deck: StateFlow<Loadable<SwipeDeck>> = mutableDeck.asStateFlow()

    suspend fun load() {
        mutableDeck.load { repository.swipe() }
    }

    suspend fun loadIfNeeded() {
        if (deck.value.valueOrNull == null) load()
    }

    /** Reactions and bookmarks change at once and roll back if the server refuses. */
    suspend fun perform(action: PostAction, post: Post) {
        val undo = action.undo
        if (undo != null) apply(action, post)
        try {
            mutableStatus.value = repository.perform(action, post)
            mutableError.value = null
            if (undo == null) apply(action, post)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (undo != null) apply(undo, post)
            mutableError.value = ApiError.from(error)
        }
    }

    private fun apply(action: PostAction, post: Post) {
        val current = deck.value.valueOrNull ?: return
        val chapters = current.chapters.map { it.copy(posts = it.posts.applying(action, post)) }.filter { it.posts.isNotEmpty() }
        mutableDeck.value = Loadable.Loaded(current.copy(chapters = chapters))
    }
}
