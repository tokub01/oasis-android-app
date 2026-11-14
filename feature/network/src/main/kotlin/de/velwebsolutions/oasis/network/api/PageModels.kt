package de.velwebsolutions.oasis.network.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import de.velwebsolutions.oasis.core.network.Page
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject

/**
 * A cursor page of Posts with the page's side information in `context` (first page only for most lists):
 * `{ "data": [...], "meta": { "next_cursor": … }, "context": { … } }`. Later pages may carry PHP's empty array as
 * `"context": []` (Entdecken); that is no side information, not an unexpected answer ([FeedPageSerializer]).
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = FeedPageSerializer::class)
data class FeedPage<C>(val data: List<Post> = emptyList(), val meta: Page.Meta? = null, val context: C? = null) {
    /** The next cursor, or the next page number of a page-numbered list (a Sammlung). */
    val nextCursor: String? get() = meta?.next
}

/** Reads a `context` that is not an object (`[]`, a number …) as no context, so the end of a list decodes. */
class FeedPageSerializer<C>(context: KSerializer<C>) :
    JsonTransformingSerializer<FeedPage<C>>(FeedPage.generatedSerializer(context)) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val page = element.jsonObject
        return if (page["context"] is JsonObject) page else JsonObject(page - "context")
    }
}

/** For lists without side information. */
@Serializable
class NoContext {
    override fun equals(other: Any?): Boolean = other is NoContext

    override fun hashCode(): Int = 0
}

/** A crowd in lists (`OasisSummaryResource`). */
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
    val role: String? = null,
    /** The Bundesland for the Karte der Crowds; null without. */
    val region: RegionRef? = null,
    /** A city or area, only with a region. */
    val city: String? = null,
    /**
     * Name, description and house rules are held for human review (docs/mildcrowd-auto-moderation.md): the server then
     * sends everyone but the founder, the hosts and staff the placeholders ([HELD_NAME], a placeholder description, no
     * house rules). False from servers before the crowd check.
     */
    val held: Boolean = false
) {
    /** „Bayern · München“; null without a region. */
    val place: String?
        get() = region?.let { listOfNotNull(it.name, city?.takeIf(String::isNotBlank)).joinToString(" · ") }

    /**
     * The viewer reads the real texts of a held crowd (founder, host or staff: the server sent no placeholder), so the
     * page and the form tell them that others see „Crowd in Prüfung“ meanwhile.
     */
    val showsHeldTexts: Boolean get() = held && name != HELD_NAME

    companion object {
        /** The name everyone else reads while the texts are held (`Oasis::HELD_NAME`). */
        const val HELD_NAME = "Crowd in Prüfung"
    }
}

/** A Bundesland of a crowd (`region: {code, name}`). */
@Serializable
data class RegionRef(val code: String, val name: String)

/** The Start's side information. */
@Serializable
data class SourceContext(
    val showAll: Boolean = false,
    val followsAnyone: Boolean = true,
    val canPost: Boolean = true,
    val week: Week? = null,
    val oases: List<OasisSummary>? = null,
    /**
     * The newest Post id of the first page at the last visit (first page only, null on the first visit): the Start
     * shows what is newer, then „Du hast alles Neue gesehen.“ (docs/mildcrowd-network.md, „Alles Neue gesehen“).
     */
    val seenUpTo: String? = null
) {
    @Serializable
    data class Week(val followers: Int = 0, val reactions: Int = 0, val karawane: Int = 0)
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
    val friendRequests: Int = 0
) {
    @Serializable
    data class Chapter(val key: String, val title: String, val reason: String = "", val posts: List<Post> = emptyList())
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

/** One year of „Heute vor einem Jahr“: „Heute vor 1 Jahr“, „Heute vor 2 Jahren“ … with the member's own Posts. */
@Serializable
data class MemoryYear(val yearsAgo: Int, val label: String, val posts: List<Post> = emptyList())
