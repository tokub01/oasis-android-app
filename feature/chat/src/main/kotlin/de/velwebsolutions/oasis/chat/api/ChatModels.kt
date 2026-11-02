package de.velwebsolutions.oasis.chat.api

import de.velwebsolutions.oasis.chatcrypto.FlexibleIdSerializer
import de.velwebsolutions.oasis.chatcrypto.LinkApproval
import de.velwebsolutions.oasis.chatcrypto.LockedIdentity
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.PublicKeyBundle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The chat API's models. Keys are declared as they are on the wire (KeyCoding.Verbatim): the crypto objects are signed
// byte for byte and partly camelCase (`publicKeys`), so every model names its keys itself.

/**
 * The member's own stored identity (`GET /chat/identity`): the passphrase-locked keys (null for a chat set up with the
 * device only) and the published bundle.
 */
@Serializable
data class StoredIdentity(
    @SerialName("locked_identity") val lockedIdentity: LockedIdentity?,
    @SerialName("public_keys") val publicKeys: PublicKeyBundle
)

/**
 * The avatar of someone in the chat for the signed-in member (`ChatAvatar` on the server): the profile photo where they
 * may see it (token route below /api, loaded with the app's image loader), otherwise the initials in the chosen colour.
 */
@Serializable
data class ChatAvatar(
    val initials: String = "?",
    @SerialName("avatar_color") val avatarColor: String = "dune",
    @SerialName("photo_url") val photoUrl: String? = null
) {
    companion object {
        /** Someone the app knows no avatar of (e.g. a former member), like the web's `UNKNOWN`. */
        val Unknown = ChatAvatar()

        /**
         * The initials of a name, as `User::initials()` (first and last word's capital), in dune: for answers of an
         * older server without `avatar`.
         */
        fun initialsOf(name: String): ChatAvatar {
            val letters = name.removePrefix("@").split(' ', '-', '_', '.').filter { it.isNotBlank() }.map { it.first().uppercaseChar() }
            val initials = when {
                letters.isEmpty() -> "?"
                letters.size == 1 -> letters.first().toString()
                else -> "${letters.first()}${letters.last()}"
            }
            return ChatAvatar(initials = initials)
        }
    }
}

/** Someone in the chat, shown by their display name or, without one, as "@username". */
interface ChatPerson {
    val username: String
    val displayName: String?

    /** The avatar the server sent; null in answers of an older server. */
    val avatar: ChatAvatar?

    /** The avatar to show: the server's, or the initials of the name in dune. */
    val shownAvatar: ChatAvatar
        get() = avatar ?: ChatAvatar.initialsOf(name)

    /** "Mira Sol" or "@mira" (`memberName` in ui/dom.js). */
    val name: String
        get() {
            val shown = displayName
            return if (shown.isNullOrEmpty()) "@$username" else shown
        }
}

/** A member with their published keys (`PublicUser`). */
@Serializable
data class PublicUser(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    override val username: String,
    @SerialName("display_name") override val displayName: String? = null,
    @SerialName("public_keys") val publicKeys: PublicKeyBundle? = null,
    @SerialName("key_changed_at") val keyChangedAt: String? = null,
    override val avatar: ChatAvatar? = null
) : ChatPerson

/** An active member of a conversation. */
@Serializable
data class ConversationMember(
    @SerialName("user_id") @Serializable(with = FlexibleIdSerializer::class) val userId: String,
    override val username: String,
    @SerialName("display_name") override val displayName: String? = null,
    val role: String = "member",
    override val avatar: ChatAvatar? = null,
    /** The status line of their profile; null when empty, held or hidden from the signed-in member. */
    val status: String? = null
) : ChatPerson

/** A conversation (`Conversation::apiData()` plus `unread` / `request` where the endpoint adds them). */
@Serializable
data class ChatConversation(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    val type: String,
    val title: String? = null,
    @SerialName("created_by") @Serializable(with = FlexibleIdSerializer::class) val createdBy: String,
    @SerialName("lifetime_days") val lifetimeDays: Int,
    @SerialName("key_version") val keyVersion: Int,
    @SerialName("rotation_required") val rotationRequired: Boolean,
    val members: List<ConversationMember>,
    @SerialName("server_time") val serverTime: String,
    val unread: Boolean? = null,
    val request: Boolean? = null,
    /** Muted for the signed-in member: no push or e-mail about new messages (`PUT conversations/{id}/mute`). */
    val muted: Boolean? = null,
    /** End of the mute (ISO 8601); null while muted means "Dauerhaft". */
    @SerialName("muted_until") val mutedUntil: String? = null,
    /**
     * A direct chat one of the two left (or deleted their account): readable, no composer, no new key
     * (`Conversation::isEnded`).
     */
    val ended: Boolean = false
) {
    val isGroup: Boolean get() = type == "group"

    /** The group title, or the other people's names in a 1:1 chat (`conversationTitle` in conversation-list.js). */
    fun displayTitle(myId: String): String {
        if (!title.isNullOrEmpty()) return title
        val others = members.filter { it.userId != myId }
        if (others.isEmpty()) return "Nur du"
        return others.joinToString(", ") { it.name }
    }

    /** The other members, whose avatars stand for the chat (`conversationAvatar` in ui/avatar.js). */
    fun others(myId: String): List<ConversationMember> = members.filter { it.userId != myId }

    /** The other person's status line in a 1:1 chat, shown under the name; null in groups or without one. */
    fun directStatus(myId: String): String? = others(myId).singleOrNull()?.status?.takeIf { !isGroup && it.isNotEmpty() }

    /** The avatar of a member, null for someone no longer in the chat. */
    fun avatarOf(userId: String): ChatAvatar? = members.firstOrNull { it.userId == userId }?.shownAvatar
}

/** Laravel's page-number paginator (`GET /chat/conversations`, 30 per page). */
@Serializable
data class ConversationPage(
    val data: List<ChatConversation>,
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1
)

/**
 * One stored message: the signed envelope as sent and the caller's view of its attachments (the rest of the server's
 * metadata is not needed).
 */
@Serializable
data class MessageRecord(val envelope: MessageEnvelope, val attachments: List<AttachmentState> = emptyList())

/**
 * The caller's view of one attachment (`ManageAttachments::statusFor`): `sent` (own), `pending`, `accepted`, `declined`
 * or `viewed`; [available] false once the server deleted the files (view once, everyone declined).
 */
@Serializable
data class AttachmentState(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    @SerialName("view_once") val viewOnce: Boolean = false,
    val status: String = PENDING,
    val available: Boolean = true
) {
    companion object {
        const val SENT = "sent"
        const val PENDING = "pending"
        const val ACCEPTED = "accepted"
        const val DECLINED = "declined"
        const val VIEWED = "viewed"
    }
}

/** `GET …/messages`: 50 newest first and the cursor for older ones. */
@Serializable
data class MessagePage(
    val data: List<MessageRecord>,
    @SerialName("next_before_id") val nextBeforeId: String? = null
)

/** A biometric copy of the identity (`ChatDeviceUnlock::apiData()`), from the app or a browser passkey. */
@Serializable
data class DeviceUnlockRow(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    @SerialName("credential_id") val credentialId: String,
    val label: String,
    @SerialName("locked_identity") val lockedIdentity: LockedIdentity,
    @SerialName("created_at") val createdAt: String
)

/** An open request of another device to be let in. */
@Serializable
data class DeviceLinkRequest(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    @SerialName("public_key") val publicKey: String,
    val label: String
)

/** The answer to creating a link request. */
@Serializable
data class CreatedDeviceLink(@Serializable(with = FlexibleIdSerializer::class) val id: String)

/** "Wer darf dir schreiben?" and friend requests, with the choices this member may pick. */
@Serializable
data class ChatSettings(
    @SerialName("chat_invites_audience") val chatInvitesAudience: String,
    @SerialName("friend_requests") val friendRequests: Boolean,
    /** value → label. */
    @SerialName("choices") val choiceMap: Map<String, String> = emptyMap()
) {
    /** The choices in the Audience enum's order (members, contacts, following, nobody), whatever the JSON order. */
    val choices: List<Pair<String, String>>
        get() = choiceMap.entries
            .sortedWith(compareBy({ AUDIENCE_ORDER.indexOf(it.key).let { index -> if (index < 0) 99 else index } }, { it.key }))
            .map { it.key to it.value }
}

/** The Audience enum's order of the "Wer darf dir schreiben?" choices. */
private val AUDIENCE_ORDER = listOf("everyone", "members", "contacts", "following", "nobody")

/** Word lists of the on-device safety checks (`GET /chat/safety`). */
@Serializable
data class SafetyLists(
    val lists: Map<String, List<String>> = emptyMap(),
    val tone: Tone = Tone()
) {
    /** The tone hint's word lists: slang flagged anywhere, ordinary words only as an address. */
    @Serializable
    data class Tone(val always: List<String> = emptyList(), val address: List<String> = emptyList())

    companion object {
        val Empty = SafetyLists()
    }
}

/** `POST …/attachments`: the reserved upload, its number of parts and the size of every part but the last. */
@Serializable
data class AttachmentUpload(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    val parts: Int,
    @SerialName("part_bytes") val partBytes: Int
)

/** `PUT …/parts/{index}`: how many parts arrived so far. */
@Serializable
data class ReceivedParts(@SerialName("received_parts") val receivedParts: Int)

/** `POST …/complete`: the id to send with the message. */
@Serializable
data class CompletedAttachment(@Serializable(with = FlexibleIdSerializer::class) val id: String)

/** `POST …/read`: the unread count afterwards. */
@Serializable
data class UnreadCount(val count: Int = 0)

/**
 * `{ "data": … | null }` of the identity and the device-link pickup. Own classes, because Retrofit hands the converter a
 * Java type, which cannot say that `data` may be null.
 */
@Serializable
data class OptionalIdentity(val data: StoredIdentity? = null)

/** `{ "data": … | null }` of the device-link pickup: null until another device approved. */
@Serializable
data class OptionalLinkApproval(val data: LinkApproval? = null)
