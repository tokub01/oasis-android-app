package de.velwebsolutions.oasis.chatcrypto

import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.ATTACHMENT_AD_LABEL
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.AEAD_XCHACHA20POLY1305_IETF_KEYBYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES

// Encrypted chat attachments (docs/oasis-chat.md, "Anhänge"; app/Support/Chat/MessagePayload.php on the server): every
// file gets its own random key and nonce, the ciphertext goes to the server, key and nonce travel inside the encrypted
// message ([MessagePayload]).

/** A file after [encryptAttachment]: the ciphertext to upload, and key and nonce for the message. */
class SealedAttachment(val ciphertext: ByteArray, val key: ByteArray, val nonce: ByteArray)

/** Encrypts a file for a conversation with a fresh 32-byte key and a fresh 24-byte nonce. */
fun ChatCrypto.encryptAttachment(file: ByteArray, conversationId: String): SealedAttachment = encryptAttachment(
    file,
    conversationId,
    randomBytes(AEAD_XCHACHA20POLY1305_IETF_KEYBYTES),
    randomBytes(AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES)
)

/**
 * XChaCha20-Poly1305-IETF of [file] with the given key and nonce (the test vectors pass fixed ones; the app always uses
 * the overload with random ones), bound to the conversation by [attachmentAssociatedData].
 */
fun ChatCrypto.encryptAttachment(file: ByteArray, conversationId: String, key: ByteArray, nonce: ByteArray): SealedAttachment {
    if (key.size != AEAD_XCHACHA20POLY1305_IETF_KEYBYTES) throw ChatCryptoException.InvalidInput("key must be a Uint8Array of 32 bytes.")
    if (nonce.size != AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES) {
        throw ChatCryptoException.InvalidInput("nonce must be a Uint8Array of 24 bytes.")
    }
    val associated = attachmentAssociatedData(ChatEncoding.normaliseId(conversationId, "conversationId"))
    return SealedAttachment(sodium.aeadEncrypt(file, associated, nonce, key), key, nonce)
}

/**
 * Decrypts a downloaded ciphertext with the key and nonce from the message.
 * @throws ChatCryptoException.TamperedAttachment when the tag does not verify (other key, nonce, chat or bytes).
 */
fun ChatCrypto.decryptAttachment(ciphertext: ByteArray, conversationId: String, key: ByteArray, nonce: ByteArray): ByteArray =
    aeadDecrypt(ciphertext, attachmentAssociatedData(conversationId), nonce, key) ?: throw ChatCryptoException.TamperedAttachment

/** `"oasis-chat/attachment/v1|" + conversation id` as UTF-8 (`MessagePayload::associatedData`). */
fun attachmentAssociatedData(conversationId: String): ByteArray = (ATTACHMENT_AD_LABEL + conversationId).toByteArray(Charsets.UTF_8)
