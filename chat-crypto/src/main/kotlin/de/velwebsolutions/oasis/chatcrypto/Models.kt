package de.velwebsolutions.oasis.chatcrypto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The locked identity stored on the server – exactly `lockIdentity`'s output in keys.js (camelCase `publicKeys`).
 * Encode and decode crypto objects with keys as declared (no snake_case conversion).
 */
@Serializable
data class LockedIdentity(
    val version: Int,
    val kdf: Kdf,
    val nonce: String,
    val ciphertext: String,
    val publicKeys: PublicKeys
) {
    @Serializable
    data class Kdf(
        val alg: String,
        val opslimit: Long,
        /** Bytes. */
        val memlimit: Long,
        val salt: String
    )

    @Serializable
    data class PublicKeys(val encryption: String, val signing: String)
}

/** The public-key bundle a member publishes (`exportPublicKeys`): both public keys and the binding signature. */
@Serializable
data class PublicKeyBundle(val encryption: String, val signing: String, val binding: String)

/** A bundle after `verifyPublicKeys`: the raw keys, proven to belong together. */
class VerifiedPublicKeys(
    /** X25519. */
    val encryption: ByteArray,
    /** Ed25519. */
    val signing: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is VerifiedPublicKeys && encryption.contentEquals(other.encryption) && signing.contentEquals(other.signing)

    override fun hashCode(): Int = 31 * encryption.contentHashCode() + signing.contentHashCode()
}

/** A signed, wrapped conversation key for one member (`createKeyGrant` output). */
@Serializable
data class KeyGrant(
    val v: Int,
    @SerialName("conversation_id") @Serializable(with = FlexibleIdSerializer::class) val conversationId: String,
    @SerialName("key_version") val keyVersion: Int,
    @SerialName("recipient_id") @Serializable(with = FlexibleIdSerializer::class) val recipientId: String,
    @SerialName("granter_id") @Serializable(with = FlexibleIdSerializer::class) val granterId: String,
    @SerialName("wrapped_key") val wrappedKey: String,
    val signature: String
)

/** A message envelope – exactly what `encryptMessage` returns, what the server stores and what `MessageSent` carries. */
@Serializable
data class MessageEnvelope(
    val v: Int,
    @SerialName("conversation_id") @Serializable(with = FlexibleIdSerializer::class) val conversationId: String,
    @SerialName("key_version") val keyVersion: Int,
    @SerialName("sender_id") @Serializable(with = FlexibleIdSerializer::class) val senderId: String,
    val nonce: String,
    val ciphertext: String,
    val signature: String,
    /** ISO 8601 UTC, whole seconds (e.g. `2026-10-18T12:00:00Z`); always present for Oasis messages. */
    @SerialName("expires_at") val expiresAt: String? = null
) {
    /** The JSON object as the browser sends it: every key in order, `expires_at` as `null` when absent. */
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("v", v)
        put("conversation_id", conversationId)
        put("key_version", keyVersion)
        put("sender_id", senderId)
        put("nonce", nonce)
        put("ciphertext", ciphertext)
        put("signature", signature)
        put("expires_at", expiresAt?.let(::JsonPrimitive) ?: JsonNull)
    }
}

/**
 * Ids are strings on the wire; numeric ids sign identically (`normaliseId`), so both are accepted. Always written as
 * strings.
 */
object FlexibleIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("oasis.chat.FlexibleId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        val element = decoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: throw SerializationException("Expected an id.")
        if (primitive is JsonNull) throw SerializationException("Expected an id.")
        if (!primitive.isString && primitive.content.toLongOrNull() == null) throw SerializationException("Expected an id.")
        return primitive.content
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
