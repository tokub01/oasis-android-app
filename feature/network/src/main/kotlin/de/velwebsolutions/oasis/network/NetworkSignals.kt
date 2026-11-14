package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.network.api.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Signals of network actions whose screen has closed (a published Post, a closed crowd), shown by the tab shell, and
 * the changes that make other screens load again: a Post was published or edited (the web's `post-published`), an
 * crowd was joined, left, founded, edited or closed, or the own profile changed.
 */
object NetworkSignals {
    /** The confirmation shown as a toast on the tab in front, then cleared. */
    val confirmation = MutableStateFlow<String?>(null)

    private val publishedCount = MutableStateFlow(0)
    private val oasesCount = MutableStateFlow(0)
    private val collectionsCount = MutableStateFlow(0)
    private val profileCount = MutableStateFlow(0)

    /** Counts publishes and edits; screens reload (or put [lastPublished] on top) when it changes. */
    val published: StateFlow<Int> = publishedCount.asStateFlow()

    /** Counts changes to crowds. */
    val oases: StateFlow<Int> = oasesCount.asStateFlow()

    /** Changes whenever a Post went into or out of a Sammlung. */
    val collections: StateFlow<Int> = collectionsCount.asStateFlow()

    /** Counts changes to the own profile. */
    val profile: StateFlow<Int> = profileCount.asStateFlow()

    /** The Post just written, for the Start to put on top (null after an edit). */
    @Volatile
    var lastPublished: Post? = null
        private set

    /** A Post whose page opens with the reply field focused ("Antworten" in a card); the page clears it. */
    internal val replyFocus = MutableStateFlow<Int?>(null)

    fun didPublish(post: Post?, confirmation: String) {
        lastPublished = post
        this.confirmation.value = confirmation
        publishedCount.update { it + 1 }
    }

    /** A Post went into or out of a Sammlung: crowd pages show their collections and cards again. */
    fun didChangeCollections() {
        collectionsCount.update { it + 1 }
    }

    fun didChangeOases() {
        oasesCount.update { it + 1 }
    }

    fun didChangeProfile() {
        profileCount.update { it + 1 }
    }
}
