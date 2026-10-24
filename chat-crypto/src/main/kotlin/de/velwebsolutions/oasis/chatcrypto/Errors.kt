package de.velwebsolutions.oasis.chatcrypto

/**
 * Typed errors of the chat crypto core (resources/js/chat/errors.js). Messages never contain keys, plaintext or
 * passphrases; the tamper errors deliberately do not say which check failed.
 */
sealed class ChatCryptoException(message: String) : Exception(message) {
    /** Wrong passphrase, or the stored ciphertext / its public metadata were changed (indistinguishable by design). */
    data object WrongPassphrase : ChatCryptoException("Wrong passphrase.")

    /** A message envelope failed verification (signature, sender key, AEAD tag, bound metadata or encoding). */
    data object TamperedMessage : ChatCryptoException("The message failed verification.")

    /** A correctly signed message whose `expires_at` lies in the past; it must be dropped, not shown. */
    data object ExpiredMessage : ChatCryptoException("The message has expired.")

    /** The plaintext exceeds the limit (in Unicode code points) or the byte limit. */
    data class MessageTooLong(val maxLength: Int) : ChatCryptoException("The message is longer than $maxLength characters.")

    /** A key grant failed verification or could not be opened with the caller's identity. */
    data object InvalidKeyGrant : ChatCryptoException("The key grant failed verification.")

    /** A published public-key bundle failed verification. */
    data object InvalidPublicKeys : ChatCryptoException("The public keys failed verification.")

    /** Wrong argument type, length or range (or an obviously malformed object from the server). */
    data class InvalidInput(val reason: String) : ChatCryptoException(reason)
}
