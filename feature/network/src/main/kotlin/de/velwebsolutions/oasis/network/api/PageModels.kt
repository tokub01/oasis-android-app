package de.velwebsolutions.oasis.network.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import de.velwebsolutions.oasis.core.network.Page
import kotlinx.serialization.Serializable

/**
 * A cursor page of Tropfen with the page's side information in `context` (first page only for most lists):
 * `{ "data": [...], "meta": { "next_cursor": … }, "context": { … } }`.
 */
@Serializable
data class FeedPage<C>(val data: List<Post> = emptyList(), val meta: Page.Meta? = null, val context: C? = null) {
    val nextCursor: String? get() = meta?.nextCursor
}

/** For lists without side information. */
@Serializable
class NoContext {
    override fun equals(other: Any?): Boolean = other is NoContext

    override fun hashCode(): Int = 0
}

/** An oasis in lists (`OasisSummaryResource`). */
@Serializable
data class OasisSummary(
    val id: Int,
    val slug: String,
    val name: String,
    val description: String = "",
    val color: String = "spring",
    val visibility: String = "public",
    val bannerUrl: String? = null,
    val membersCount: Int? = null,
    /** The viewer's (or the profile owner's) role: "host" or "member". */
    val role: String? = null
)

/** The Quelle's side information. */
@Serializable
data class SourceContext(
    val showAll: Boolean = false,
    val followsAnyone: Boolean = true,
    val canPost: Boolean = true,
    val week: Week? = null,
    val oases: List<OasisSummary>? = null,
    val openVote: Vote? = null
) {
    @Serializable
    data class Week(val followers: Int = 0, val reactions: Int = 0, val karawane: Int = 0)

    @Serializable
    data class Vote(val id: Int, val title: String, val endsAt: OasisInstant)
}

/** Entdecken's side information. */
@Serializable
data class DiscoverContext(val oases: List<OasisSummary>? = null)

/** The search's side information; `topic` when the term is exactly one #Thema. */
@Serializable
data class SearchContext(
    val term: String? = null,
    val searching: Boolean = false,
    val topic: String? = null,
    val people: List<Member>? = null,
    val oases: List<OasisSummary>? = null
)

/** A topic page's context. */
@Serializable
data class TagContext(val tag: String = "", val canPost: Boolean = false)

/** Durchblättern: chapters and the end card. */
@Serializable
data class SwipeDeck(
    val chapters: List<Chapter> = emptyList(),
    val unreadChats: Int = 0,
    val unreadHints: Int = 0,
    val friendRequests: Int = 0,
    val openVote: Vote? = null
) {
    @Serializable
    data class Chapter(val key: String, val title: String, val reason: String = "", val posts: List<Post> = emptyList())

    @Serializable
    data class Vote(val id: Int, val title: String)
}

/** "Deine Zahlen". */
@Serializable
data class MemberStatistics(val totals: Totals, val weekly: List<Week> = emptyList(), val topPosts: List<TopPost> = emptyList()) {
    @Serializable
    data class Totals(
        val posts: Int = 0,
        val reactions: Int = 0,
        val replies: Int = 0,
        val carries: Int = 0,
        val followers: Int = 0,
        val following: Int = 0
    )

    @Serializable
    data class Week(val week: OasisInstant, val followers: Int = 0, val posts: Int = 0, val reactions: Int = 0)

    @Serializable
    data class TopPost(val id: Int, val body: String, val reactions: Int = 0, val replies: Int = 0, val createdAt: OasisInstant)
}
