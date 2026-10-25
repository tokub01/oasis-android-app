package de.velwebsolutions.oasis.chatcrypto

import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.KEY_GRANT_LABEL
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.KEY_GRANT_VERSION
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.MAX_MESSAGE_BYTES
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.MAX_MESSAGE_LENGTH
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.MESSAGE_AD_LABEL
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.MESSAGE_SIGNATURE_LABEL
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.MESSAGE_VERSION
import de.velwebsolutions.oasis.chatcrypto.ChatEncoding.canonicalBytes
import de.velwebsolutions.oasis.chatcrypto.ChatEncoding.fields
import de.velwebsolutions.oasis.chatcrypto.ChatEncoding.normaliseId
import de.velwebsolutions.oasis.chatcrypto.ChatEncoding.normaliseKeyVersion
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.BOX_SEALBYTES
import java.time.Instant

/** A grant after [openKeyGrant]: the conversation key plus the verified ids for the caller's policy checks. */
class OpenedKeyGrant(
    val conversationKey: ByteArray,
    val conversationId: String,
    val keyVersion: Int,
    val granterId: String,
    val recipientId: String
)

/** A verified, decrypted message. */
data class DecryptedMessage(
    val plaintext: String,
    val conversationId: String,
    val keyVersion: Int,
    val senderId: String,
    val expiresAt: String?
)

// Conversation keys, key grants and message envelopes (resources/js/chat/conversation.js).

/** A new random 32-byte conversation key. */
fun ChatCrypto.createConversationKey(): ByteArray = randomBytes(32)

/** Opens a sealed conversation key with the caller's identity. */
fun ChatCrypto.unwrapKey(wrapped: String, identity: ChatIdentity): ByteArray {
    identity.assertUsable()
    val sealed = try {
        fromBase64(wrapped, BOX_SEALBYTES + 32)
    } catch (_: ChatCryptoException) {
        throw ChatCryptoException.InvalidKeyGrant
    }
    val opened = identity.withPrivateKeys { encryption, _ -> sealOpen(sealed, identity.encryptionPublicKey, encryption) }
    if (opened == null || opened.size != 32) throw ChatCryptoException.InvalidKeyGrant
    return opened
}

/** Wraps a conversation key for one member and signs the grant with the granter's identity. */
fun ChatCrypto.createKeyGrant(
    conversationKey: ByteArray,
    conversationId: String,
    keyVersion: Int,
    recipientId: String,
    recipientEncryptionPublicKey: ByteArray,
    granterId: String,
    identity: ChatIdentity
): KeyGrant {
    identity.assertUsable()
    val conversation = normaliseId(conversationId, "conversationId")
    val version = normaliseKeyVersion(keyVersion)
    val recipient = normaliseId(recipientId, "recipientId")
    val granter = normaliseId(granterId, "granterId")
    val sealed = seal(conversationKey, recipientEncryptionPublicKey)
    val signed = keyGrantSignedBytes(KEY_GRANT_VERSION, conversation, version, recipient, granter, recipientEncryptionPublicKey, sealed)
    val signature = identity.withPrivateKeys { _, signing -> sodium.signDetached(signed, signing) }
    return KeyGrant(KEY_GRANT_VERSION, conversation, version, recipient, granter, toBase64(sealed), toBase64(signature))
}

/** Verifies a grant addressed to the caller (signature by [granterSigningPublicKey]) and unwraps the key. */
fun ChatCrypto.openKeyGrant(grant: KeyGrant, identity: ChatIdentity, granterSigningPublicKey: ByteArray): OpenedKeyGrant {
    identity.assertUsable()
    val conversation: String
    val version: Int
    val recipient: String
    val granter: String
    try {
        if (granterSigningPublicKey.size != 32 || grant.v != KEY_GRANT_VERSION) throw ChatCryptoException.InvalidKeyGrant
        conversation = normaliseId(grant.conversationId, "conversation_id")
        version = normaliseKeyVersion(grant.keyVersion)
        recipient = normaliseId(grant.recipientId, "recipient_id")
        granter = normaliseId(grant.granterId, "granter_id")
        val sealed = fromBase64(grant.wrappedKey, BOX_SEALBYTES + 32)
        val signature = fromBase64(grant.signature, 64)
        val signed = keyGrantSignedBytes(grant.v, conversation, version, recipient, granter, identity.encryptionPublicKey, sealed)
        if (!verifyDetached(signature, signed, granterSigningPublicKey)) throw ChatCryptoException.InvalidKeyGrant
    } catch (_: ChatCryptoException) {
        throw ChatCryptoException.InvalidKeyGrant
    }
    val key = unwrapKey(grant.wrappedKey, identity)
    return OpenedKeyGrant(key, conversation, version, granter, recipient)
}

/** Encrypts and signs a text message. [expiresAt] is rounded down to whole seconds and must lie after [now]. */
fun ChatCrypto.encryptMessage(
    conversationKey: ByteArray,
    conversationId: String,
    keyVersion: Int,
    senderId: String,
    identity: ChatIdentity,
    plaintext: String,
    expiresAt: Instant,
    now: Instant = Instant.now()
): MessageEnvelope {
    if (conversationKey.size != 32) throw ChatCryptoException.InvalidInput("conversationKey must be a Uint8Array of 32 bytes.")
    identity.assertUsable()
    val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
    if (plaintext.codePointCount(0, plaintext.length) > MAX_MESSAGE_LENGTH || plaintextBytes.size > MAX_MESSAGE_BYTES) {
        ChatEncoding.wipe(plaintextBytes)
        throw ChatCryptoException.MessageTooLong(MAX_MESSAGE_LENGTH)
    }
    try {
        val expirySeconds = Math.floorDiv(expiresAt.toEpochMilli(), 1000L)
        if (expirySeconds * 1000 <= now.toEpochMilli()) throw ChatCryptoException.InvalidInput("expiresAt must lie in the future.")
        val conversation = normaliseId(conversationId, "conversationId")
        val version = normaliseKeyVersion(keyVersion)
        val sender = normaliseId(senderId, "senderId")
        val associated = messageAssociatedData(MESSAGE_VERSION, conversation, version, sender, expirySeconds)
        val nonce = randomBytes(24)
        val ciphertext = sodium.aeadEncrypt(plaintextBytes, associated, nonce, conversationKey)
        val signed = canonicalBytes(MESSAGE_SIGNATURE_LABEL, fields(nonce, ciphertext, associated))
        val signature = identity.withPrivateKeys { _, signing -> sodium.signDetached(signed, signing) }
        return MessageEnvelope(
            v = MESSAGE_VERSION,
            conversationId = conversation,
            keyVersion = version,
            senderId = sender,
            nonce = toBase64(nonce),
            ciphertext = toBase64(ciphertext),
            signature = toBase64(signature),
            expiresAt = ChatEncoding.formatExpiry(expirySeconds)
        )
    } finally {
        ChatEncoding.wipe(plaintextBytes)
    }
}

/**
 * Verifies and decrypts an envelope: decode → signature → (expected conversation) → expiry → AEAD → strict UTF-8.
 * @throws ChatCryptoException.TamperedMessage for every verification failure, [ChatCryptoException.ExpiredMessage] for an
 *   authentic expired one.
 */
fun ChatCrypto.decryptMessage(
    envelope: MessageEnvelope,
    conversationKey: ByteArray,
    senderSigningPublicKey: ByteArray,
    now: Instant = Instant.now(),
    expectedConversationId: String? = null
): DecryptedMessage {
    if (conversationKey.size != 32) throw ChatCryptoException.InvalidInput("conversationKey must be a Uint8Array of 32 bytes.")
    if (senderSigningPublicKey.size !=
        32
    ) {
        throw ChatCryptoException.InvalidInput("senderSigningPublicKey must be a Uint8Array of 32 bytes.")
    }
    val nonce: ByteArray
    val ciphertext: ByteArray
    val signature: ByteArray
    val associated: ByteArray
    val expirySeconds: Long?
    try {
        if (envelope.v != MESSAGE_VERSION) throw ChatCryptoException.TamperedMessage
        expirySeconds = ChatEncoding.parseEnvelopeExpiry(envelope.expiresAt)
        associated = messageAssociatedData(
            envelope.v,
            normaliseId(envelope.conversationId, "conversation_id"),
            normaliseKeyVersion(envelope.keyVersion),
            normaliseId(envelope.senderId, "sender_id"),
            expirySeconds
        )
        nonce = fromBase64(envelope.nonce, 24)
        ciphertext = fromBase64(envelope.ciphertext)
        signature = fromBase64(envelope.signature, 64)
    } catch (_: ChatCryptoException) {
        throw ChatCryptoException.TamperedMessage
    }
    val signed = canonicalBytes(MESSAGE_SIGNATURE_LABEL, fields(nonce, ciphertext, associated))
    if (!verifyDetached(signature, signed, senderSigningPublicKey)) throw ChatCryptoException.TamperedMessage
    if (expectedConversationId != null && envelope.conversationId != expectedConversationId) throw ChatCryptoException.TamperedMessage
    if (expirySeconds != null && expirySeconds * 1000 <= now.toEpochMilli()) throw ChatCryptoException.ExpiredMessage
    val plaintextBytes = aeadDecrypt(ciphertext, associated, nonce, conversationKey) ?: throw ChatCryptoException.TamperedMessage
    try {
        val plaintext = ChatEncoding.strictUtf8(plaintextBytes) ?: throw ChatCryptoException.TamperedMessage
        return DecryptedMessage(plaintext, envelope.conversationId, envelope.keyVersion, envelope.senderId, envelope.expiresAt)
    } finally {
        ChatEncoding.wipe(plaintextBytes)
    }
}

internal fun messageAssociatedData(v: Int, conversationId: String, keyVersion: Int, senderId: String, expirySeconds: Long?): ByteArray =
    canonicalBytes(
        MESSAGE_AD_LABEL,
        fields(
            v.toString(),
            conversationId,
            keyVersion.toString(),
            senderId,
            if (expirySeconds ==
                null
            ) {
                ""
            } else {
                expirySeconds.toString()
            }
        )
    )

internal fun keyGrantSignedBytes(
    v: Int,
    conversationId: String,
    keyVersion: Int,
    recipientId: String,
    granterId: String,
    recipientEncryptionPublicKey: ByteArray,
    sealedKey: ByteArray
): ByteArray = canonicalBytes(
    KEY_GRANT_LABEL,
    fields(v.toString(), conversationId, keyVersion.toString(), recipientId, granterId, recipientEncryptionPublicKey, sealedKey)
)

/** `crypto_box_seal` of a 32-byte key for an X25519 public key. */
internal fun ChatCrypto.seal(key: ByteArray, recipientEncryptionPublicKey: ByteArray): ByteArray {
    if (key.size != 32) throw ChatCryptoException.InvalidInput("conversationKey must be a Uint8Array of 32 bytes.")
    if (recipientEncryptionPublicKey.size != 32) {
        throw ChatCryptoException.InvalidInput("recipientEncryptionPublicKey must be a Uint8Array of 32 bytes.")
    }
    return sodium.boxSeal(key, recipientEncryptionPublicKey)
}

internal fun ChatCrypto.sealOpen(sealed: ByteArray, publicKey: ByteArray, secretKey: ByteArray): ByteArray? {
    if (sealed.size < BOX_SEALBYTES) return null
    return sodium.boxSealOpen(sealed, publicKey, secretKey)
}
