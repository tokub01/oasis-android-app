package de.velwebsolutions.oasis.chat.data

import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ChatService
import de.velwebsolutions.oasis.chat.api.ChatSettings
import de.velwebsolutions.oasis.chat.api.ConversationPage
import de.velwebsolutions.oasis.chat.api.CreatedDeviceLink
import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.chat.api.DeviceUnlockRow
import de.velwebsolutions.oasis.chat.api.MessagePage
import de.velwebsolutions.oasis.chat.api.PublicUser
import de.velwebsolutions.oasis.chat.api.SafetyLists
import de.velwebsolutions.oasis.chat.api.StoredIdentity
import de.velwebsolutions.oasis.chatcrypto.KeyGrant
import de.velwebsolutions.oasis.chatcrypto.LinkApproval
import de.velwebsolutions.oasis.chatcrypto.LockedIdentity
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.PublicKeyBundle
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.KeyCoding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A value of `PATCH /chat/settings`: the audience (text) or the friend-request switch (flag). */
sealed interface SettingValue {
    data class Text(val value: String) : SettingValue

    data class Flag(val value: Boolean) : SettingValue
}

/**
 * One method per chat endpoint (`/api/v1/chat/…`), the twin of iOS `ChatAPI`. No crypto here. Ids are strings, as in
 * every crypto object; the server's integer ids are sent as numbers where it expects them (`user_id`, `member_ids`).
 */
interface ChatRepository {
    suspend fun identity(): StoredIdentity?

    /** Publishes the keys; [locked] null = no passphrase copy (device unlock only). */
    suspend fun saveIdentity(locked: LockedIdentity?, publicKeys: PublicKeyBundle): StoredIdentity

    suspend fun deviceUnlocks(): List<DeviceUnlockRow>

    suspend fun saveDeviceUnlock(credentialId: String, prfSalt: String, label: String, locked: LockedIdentity): DeviceUnlockRow

    suspend fun deleteDeviceUnlock(id: String)

    suspend fun requestDeviceLink(publicKey: String, label: String): CreatedDeviceLink

    /** Null until approved; afterwards the approval exactly once. */
    suspend fun deviceLink(id: String): LinkApproval?

    suspend fun deviceLinks(): List<DeviceLinkRequest>

    suspend fun approveDeviceLink(id: String, locked: LockedIdentity, sealedKey: String)

    suspend fun rejectDeviceLink(id: String)

    /** Exact username; empty for unknown, not chat-ready, blocked or not reachable accounts. */
    suspend fun findUser(username: String): List<PublicUser>

    suspend fun settings(): ChatSettings

    suspend fun updateSetting(key: String, value: SettingValue): ChatSettings

    suspend fun block(userId: String)

    suspend fun safety(): SafetyLists

    suspend fun conversations(page: Int = 1): ConversationPage

    suspend fun createConversation(type: String, title: String?, memberIds: List<String>, lifetimeDays: Int): ChatConversation

    suspend fun conversation(id: String): ChatConversation

    /** Owner only. Pass only what changes; [setTitle] true sends [title] (null clears the group name). */
    suspend fun updateConversation(
        id: String,
        lifetimeDays: Int? = null,
        setTitle: Boolean = false,
        title: String? = null
    ): ChatConversation

    suspend fun publicKeys(conversationId: String): List<PublicUser>

    suspend fun addMember(conversationId: String, userId: String): ChatConversation

    suspend fun removeMember(conversationId: String, userId: String): ChatConversation

    suspend fun keyGrants(conversationId: String): List<KeyGrant>

    suspend fun storeKeyGrants(conversationId: String, keyVersion: Int, grants: List<KeyGrant>)

    suspend fun activateKey(conversationId: String, keyVersion: Int): ChatConversation

    suspend fun messages(conversationId: String, beforeId: String? = null): MessagePage

    suspend fun sendMessage(conversationId: String, envelope: MessageEnvelope)

    /** The chat is open: everything so far counts as seen. Returns the unread count. */
    suspend fun markRead(conversationId: String): Int

    suspend fun accept(conversationId: String)

    /** Mute the chat for oneself: `8h`, `1w` or `always` (the choices of the web's details screen). */
    suspend fun mute(conversationId: String, duration: String): ChatConversation

    suspend fun unmute(conversationId: String): ChatConversation

    suspend fun leave(conversationId: String)

    /** Consent-based report: one decrypted message with its signed envelope. */
    suspend fun report(conversationId: String, envelope: MessageEnvelope, plaintext: String, reason: String)
}

/** [ChatRepository] over the Oasis API client. */
class DefaultChatRepository(client: ApiClient) : ChatRepository {
    private val service = client.create<ChatService>(KeyCoding.Verbatim)

    private inline fun <reified T> T.json(): JsonElement = CryptoJson.encodeToJsonElement(kotlinx.serialization.serializer<T>(), this)

    private fun id(value: String): JsonPrimitive = JsonPrimitive(value.toLongOrNull() ?: 0)

    override suspend fun identity() = service.identity().data

    override suspend fun saveIdentity(locked: LockedIdentity?, publicKeys: PublicKeyBundle) = service.saveIdentity(
        buildJsonObject {
            put("locked_identity", locked?.json() ?: JsonNull)
            put("public_keys", publicKeys.json())
        }
    ).data

    override suspend fun deviceUnlocks() = service.deviceUnlocks().data

    override suspend fun saveDeviceUnlock(credentialId: String, prfSalt: String, label: String, locked: LockedIdentity) =
        service.saveDeviceUnlock(
            buildJsonObject {
                put("credential_id", credentialId)
                put("prf_salt", prfSalt)
                put("label", label)
                put("locked_identity", locked.json())
            }
        ).data

    override suspend fun deleteDeviceUnlock(id: String) = service.deleteDeviceUnlock(id)

    override suspend fun requestDeviceLink(publicKey: String, label: String) = service.requestDeviceLink(
        buildJsonObject {
            put("public_key", publicKey)
            put("label", label)
        }
    ).data

    override suspend fun deviceLink(id: String) = service.deviceLink(id).data

    override suspend fun deviceLinks() = service.deviceLinks().data

    override suspend fun approveDeviceLink(id: String, locked: LockedIdentity, sealedKey: String) = service.approveDeviceLink(
        id,
        buildJsonObject {
            put("locked_identity", locked.json())
            put("sealed_key", sealedKey)
        }
    )

    override suspend fun rejectDeviceLink(id: String) = service.rejectDeviceLink(id)

    override suspend fun findUser(username: String) = service.findUser(username).data

    override suspend fun settings() = service.settings()

    override suspend fun updateSetting(key: String, value: SettingValue) = service.updateSetting(
        buildJsonObject {
            put("key", key)
            when (value) {
                is SettingValue.Text -> put("value", value.value)
                is SettingValue.Flag -> put("value", value.value)
            }
        }
    )

    override suspend fun block(userId: String) = service.block(buildJsonObject { put("user_id", id(userId)) })

    override suspend fun safety() = service.safety().data

    override suspend fun conversations(page: Int) = service.conversations(page)

    override suspend fun createConversation(type: String, title: String?, memberIds: List<String>, lifetimeDays: Int) =
        service.createConversation(
            buildJsonObject {
                put("type", type)
                put("title", title?.let(::JsonPrimitive) ?: JsonNull)
                put("member_ids", JsonArray(memberIds.mapNotNull { it.toLongOrNull()?.let(::JsonPrimitive) }))
                put("lifetime_days", lifetimeDays)
            }
        ).data

    override suspend fun conversation(id: String) = service.conversation(id).data

    override suspend fun updateConversation(id: String, lifetimeDays: Int?, setTitle: Boolean, title: String?) = service.updateConversation(
        id,
        buildJsonObject {
            if (lifetimeDays != null) put("lifetime_days", lifetimeDays)
            if (setTitle) put("title", title?.let(::JsonPrimitive) ?: JsonNull)
        }
    ).data

    override suspend fun publicKeys(conversationId: String) = service.publicKeys(conversationId).data

    override suspend fun addMember(conversationId: String, userId: String) =
        service.addMember(conversationId, buildJsonObject { put("user_id", id(userId)) }).data

    override suspend fun removeMember(conversationId: String, userId: String) = service.removeMember(conversationId, userId).data

    override suspend fun keyGrants(conversationId: String) = service.keyGrants(conversationId).data

    override suspend fun storeKeyGrants(conversationId: String, keyVersion: Int, grants: List<KeyGrant>) = service.storeKeyGrants(
        conversationId,
        buildJsonObject {
            put("key_version", keyVersion)
            put("grants", grants.json())
        }
    )

    override suspend fun activateKey(conversationId: String, keyVersion: Int) =
        service.activateKey(conversationId, buildJsonObject { put("key_version", keyVersion) }).data

    override suspend fun messages(conversationId: String, beforeId: String?) = service.messages(conversationId, beforeId)

    override suspend fun sendMessage(conversationId: String, envelope: MessageEnvelope) =
        service.sendMessage(conversationId, buildJsonObject { put("envelope", envelope.toJsonObject()) })

    override suspend fun markRead(conversationId: String) = service.markRead(conversationId).count

    override suspend fun accept(conversationId: String) = service.accept(conversationId)

    override suspend fun mute(conversationId: String, duration: String) =
        service.mute(conversationId, buildJsonObject { put("duration", duration) }).data

    override suspend fun unmute(conversationId: String) = service.unmute(conversationId).data

    override suspend fun leave(conversationId: String) = service.leave(conversationId)

    override suspend fun report(conversationId: String, envelope: MessageEnvelope, plaintext: String, reason: String) = service.report(
        conversationId,
        buildJsonObject {
            put("envelope", envelope.toJsonObject())
            put("plaintext", plaintext)
            put("reason", reason)
        }
    )

    private companion object {
        /** Crypto objects as declared (camelCase `publicKeys`, nothing renamed). */
        val CryptoJson = Json { encodeDefaults = true }
    }
}
