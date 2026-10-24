package de.velwebsolutions.oasis.chat.api

import de.velwebsolutions.oasis.chatcrypto.FlexibleIdSerializer
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
    @SerialName("user_id") @Serializable(with = FlexibleIdSerializer::class) val userId: String,
    @SerialName("locked_identity") val lockedIdentity: LockedIdentity? = null,
    @SerialName("public_keys") val publicKeys: PublicKeyBundle
)

/** Someone in the chat, shown by their display name or, without one, as "@username". */
interface ChatPerson {
    val username: String
    val displayName: String?

    /** "Mira Sol" or "@mira" (`memberName` in ui/dom.js). */
    val name: String
        get() = displayName?.takeIf { it.isNotEmpty() } ?: "@$username"
}

/** A member with their published keys (`PublicUser`). */
@Serializable
data class PublicUser(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    override val username: String,
    @SerialName("display_name") override val displayName: String? = null,
    @SerialName("public_keys") val publicKeys: PublicKeyBundle? = null,
    @SerialName("key_changed_at") val keyChangedAt: String? = null
) : ChatPerson

/** An active member of a conversation. */
@Serializable
data class ConversationMember(
    @SerialName("user_id") @Serializable(with = FlexibleIdSerializer::class) val userId: String,
    override val username: String,
    @SerialName("display_name") override val displayName: String? = null,
    val role: String = "member"
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
    @SerialName("muted_until") val mutedUntil: String? = null
) {
    val isGroup: Boolean get() = type == "group"

    /** The group title, or the other people's names in a 1:1 chat (`conversationTitle` in conversation-list.js). */
    fun displayTitle(myId: String): String {
        if (!title.isNullOrEmpty()) return title
        val others = members.filter { it.userId != myId }
        if (others.isEmpty()) return "Nur du"
        return others.joinToString(", ") { it.name }
    }
}

/** Laravel's page-number paginator (`GET /chat/conversations`, 30 per page). */
@Serializable
data class ConversationPage(
    val data: List<ChatConversation>,
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1
)

/** One stored message: the signed envelope as sent, plus server metadata. */
@Serializable
data class MessageRecord(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    val envelope: MessageEnvelope,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String
)

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
    @SerialName("prf_salt") val prfSalt: String,
    val label: String,
    @SerialName("locked_identity") val lockedIdentity: LockedIdentity,
    @SerialName("created_at") val createdAt: String
)

/** An open request of another device to be let in. */
@Serializable
data class DeviceLinkRequest(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    @SerialName("public_key") val publicKey: String,
    val label: String,
    @SerialName("created_at") val createdAt: String? = null
)

/** The answer to creating a link request. */
@Serializable
data class CreatedDeviceLink(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    @SerialName("expires_at") val expiresAt: String
)

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
            .sortedWith(compareBy({ ORDER.indexOf(it.key).let { index -> if (index < 0) 99 else index } }, { it.key }))
            .map { it.key to it.value }

    private companion object {
        val ORDER = listOf("everyone", "members", "contacts", "following", "nobody")
    }
}

/** Word lists of the on-device safety checks (`GET /chat/safety`). */
@Serializable
data class SafetyLists(
    val lists: Map<String, List<String>> = emptyMap(),
    val tone: Tone = Tone()
) {
    @Serializable
    data class Tone(val always: List<String> = emptyList(), val address: List<String> = emptyList())

    companion object {
        val Empty = SafetyLists()
    }
}

/** `POST …/read`: the unread count afterwards. */
@Serializable
data class UnreadCount(val count: Int = 0)
