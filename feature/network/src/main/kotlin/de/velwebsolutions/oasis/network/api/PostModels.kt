package de.velwebsolutions.oasis.network.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A member as shown next to a Tropfen and in lists (`MemberResource`): name, handle and avatar (initials in the chosen
 * colour, or the photo where the viewer may see it).
 */
@Serializable
data class Member(
    val id: Int,
    val username: String,
    val displayName: String,
    val initials: String,
    val avatarColor: String = "sun",
    val photoUrl: String? = null,
)

/**
 * One piece of a Tropfen's text (`App\Support\Network\RichText`): plain text, an external link (shown by host), a
 * linkable @mention or a #topic.
 */
@Serializable
data class TextSegment(
    val type: Kind = Kind.Text,
    val text: String,
    val url: String? = null,
    val username: String? = null,
    val tag: String? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("text")
        Text,

        @SerialName("link")
        Link,

        @SerialName("mention")
        Mention,

        @SerialName("tag")
        Tag,
    }
}

/** A Tropfen card (`PostResource`) – the same information and decisions as the web's `x-network.post-card`. */
@Serializable
data class Post(
    val id: Int,
    /** The web address, for sharing and reporting. */
    val url: String,
    val body: String,
    val segments: List<TextSegment> = emptyList(),
    val kind: String = "post",
    val visibility: String = "public",
    val createdAt: OasisInstant,
    val editedAt: OasisInstant? = null,
    val author: Member,
    val oasis: OasisRef? = null,
    val replyTo: ReplyRef? = null,
    val replyUnavailable: Boolean = false,
    val imagesLocked: Boolean = false,
    val images: List<Image> = emptyList(),
    val carried: Carried? = null,
    val removedFromOasis: Boolean = false,
    val held: Held? = null,
    val stats: Stats? = null,
    val conversation: Conversation? = null,
    val viewer: Viewer? = null,
) {
    @Serializable
    data class OasisRef(val slug: String, val name: String, val color: String)

    @Serializable
    data class ReplyRef(val id: Int, val username: String)

    @Serializable
    data class Image(
        val id: Int,
        val url: String,
        val alt: String = "",
        val width: Int = 1,
        val height: Int = 1,
        /** The viewer sees images of this person only after tapping ("Wessen Bilder siehst du sofort?"). */
        val covered: Boolean = false,
    ) {
        val aspectRatio: Float get() = maxOf(width, 1).toFloat() / maxOf(height, 1).toFloat()
    }

    @Serializable
    data class Carried(val state: State = State.Unavailable, val post: Original? = null) {
        @Serializable
        enum class State {
            @SerialName("visible")
            Visible,

            @SerialName("muted")
            Muted,

            @SerialName("unavailable")
            Unavailable,
        }

        @Serializable
        data class Original(val id: Int, val author: Member, val body: String, val segments: List<TextSegment> = emptyList())
    }

    @Serializable
    data class Held(val reason: String, val checkReason: String? = null, val reviewHours: Int = 24)

    @Serializable
    data class Stats(val reactions: Int = 0, val replies: Int = 0)

    @Serializable
    data class Conversation(val title: String, val hasMore: Boolean = false, val replies: List<Reply> = emptyList()) {
        @Serializable
        data class Reply(val id: Int, val author: Member, val body: String, val segments: List<TextSegment> = emptyList())
    }

    /** What the signed-in viewer may do – the checks that decide which buttons the web renders. */
    @Serializable
    data class Viewer(
        val isAuthor: Boolean = false,
        val reacted: Boolean = false,
        val bookmarked: Boolean = false,
        val canInteract: Boolean = false,
        val canReply: Boolean = false,
        val canCarry: Boolean = false,
        val canWriteReply: Boolean = false,
        val canQuickReply: Boolean = false,
        val canEdit: Boolean = false,
        val canDelete: Boolean = false,
        val canRemoveFromOasis: Boolean = false,
        val hasMutedAuthor: Boolean = false,
        val hasRestrictedAuthor: Boolean = false,
    )

    val isQuestion: Boolean get() = kind == "question"
    val isMembersOnly: Boolean get() = visibility == "members"
}

/** An earlier text of an edited Tropfen. */
@Serializable
data class PostRevision(val id: Int, val body: String, val replacedAt: OasisInstant)

/** The Tropfen page: the post, earlier versions, replies and the side cards. */
@Serializable
data class PostDetail(
    val post: Post,
    val revisions: List<PostRevision> = emptyList(),
    val replies: List<Post> = emptyList(),
    val authorBio: String? = null,
    val oasisDescription: String? = null,
)

/** The edit form of a Tropfen. */
@Serializable
data class PostEditForm(val body: String, val until: OasisInstant)

/** An external link in a Tropfen that needs "Ich habe den Inhalt gelesen" before carrying. */
@Serializable
data class ExternalLink(val url: String, val host: String)

/** The carry page: the original Tropfen and its links. */
@Serializable
data class CarrySource(
    val id: Int,
    val author: Member,
    val body: String,
    val segments: List<TextSegment> = emptyList(),
    val links: List<ExternalLink> = emptyList(),
)

/** A choice with its German label (visibility, kind, colour). */
@Serializable
data class Choice(val value: String, val label: String)

/** What the composer offers in a context. Under 18 the server leaves "Öffentlich" out of [visibilities]. */
@Serializable
data class ComposerContext(
    val defaultVisibility: String,
    val visibilities: List<Choice> = emptyList(),
    val kinds: List<Choice> = emptyList(),
    val oasis: OasisName? = null,
    val links: List<ExternalLink> = emptyList(),
    val maxLength: Int = 500,
    val maxImages: Int = 4,
) {
    @Serializable
    data class OasisName(val slug: String, val name: String)
}

/** The answer after publishing. */
@Serializable
data class PublishResult(val message: String, val suspended: Boolean = false, val data: Post)

/** An image chosen for upload (already converted to JPEG/PNG/GIF/WebP, which the server accepts). */
class ImageUpload(val data: ByteArray, val filename: String, val mimeType: String) {
    override fun equals(other: Any?): Boolean =
        other is ImageUpload && filename == other.filename && mimeType == other.mimeType && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * (31 * data.contentHashCode() + filename.hashCode()) + mimeType.hashCode()
}

/** What the composer sends. */
data class PostDraft(
    val body: String = "",
    val visibility: String = "public",
    val kind: String = "post",
    val oasisSlug: String? = null,
    val replyTo: Int? = null,
    val carried: Int? = null,
    val linkRead: Boolean = false,
    val toneConfirmed: Boolean = false,
    val images: List<ImageUpload> = emptyList(),
    val alts: List<String> = emptyList(),
)
