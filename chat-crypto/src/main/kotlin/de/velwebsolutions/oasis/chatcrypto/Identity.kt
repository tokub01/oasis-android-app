package de.velwebsolutions.oasis.chatcrypto

import de.velwebsolutions.oasis.chatcrypto.ChatEncoding.canonicalBytes
import de.velwebsolutions.oasis.chatcrypto.ChatEncoding.fields
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.AEAD_XCHACHA20POLY1305_IETF_ABYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.AEAD_XCHACHA20POLY1305_IETF_KEYBYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.PWHASH_MEMLIMIT_MIN
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.PWHASH_MEMLIMIT_MODERATE
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.PWHASH_OPSLIMIT_MIN
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.PWHASH_OPSLIMIT_MODERATE
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.PWHASH_SALTBYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.SIGN_BYTES
import java.text.Normalizer

/**
 * A member's unlocked chat identity: X25519 key pair (receives wrapped conversation keys) and Ed25519 key pair (signs
 * messages, grants and the key binding). Lives in memory only; [wipe] zeroes the private keys. The private keys are only
 * reached through [withPrivateKeys], under the same lock as [wipe], so a lock from another thread waits until a running
 * operation is done and nothing reads half-wiped keys.
 */
class ChatIdentity(
    val encryptionPublicKey: ByteArray,
    private val encryptionPrivateKey: ByteArray,
    val signingPublicKey: ByteArray,
    /** libsodium's 64-byte Ed25519 secret key (seed || public key). */
    private val signingPrivateKey: ByteArray
) {
    private val lock = Any()

    init {
        if (encryptionPublicKey.size != 32 || encryptionPrivateKey.size != 32 || signingPublicKey.size != 32 ||
            signingPrivateKey.size != 64
        ) {
            throw ChatCryptoException.InvalidInput("identity must be an identity object.")
        }
    }

    /** Whether the private keys were wiped. */
    val isWiped: Boolean
        get() = synchronized(lock) { wiped }

    private val wiped: Boolean get() = encryptionPrivateKey.all { it == 0.toByte() }

    /** Runs [body] with the X25519 and the Ed25519 private key while holding the lock; fails for a wiped identity. */
    fun <T> withPrivateKeys(body: (encryption: ByteArray, signing: ByteArray) -> T): T = synchronized(lock) {
        if (wiped) throw ChatCryptoException.InvalidInput("identity must be an identity object.")
        body(encryptionPrivateKey, signingPrivateKey)
    }

    /** Overwrites both private keys with zeros (lock, sign-out, app in background). */
    fun wipe() = synchronized(lock) {
        ChatEncoding.wipe(encryptionPrivateKey)
        ChatEncoding.wipe(signingPrivateKey)
    }

    /** Fails early for a wiped identity, before any other input is looked at (the order of the JS checks). */
    internal fun assertUsable() {
        if (isWiped) throw ChatCryptoException.InvalidInput("identity must be an identity object.")
    }
}

/** Argon2id costs for [ChatCrypto.lockIdentity]. */
data class KdfLimits(val opslimit: Long, val memlimit: Long) {
    companion object {
        /** Production costs (OPSLIMIT_MODERATE = 3, MEMLIMIT_MODERATE = 256 MiB), as in the browser. */
        val Moderate = KdfLimits(PWHASH_OPSLIMIT_MODERATE, PWHASH_MEMLIMIT_MODERATE)

        /**
         * libsodium's minimum (1 pass, 8 KiB): only for copies locked with a random 256-bit key (device unlock, device
         * link) – and for tests.
         */
        val Minimum = KdfLimits(PWHASH_OPSLIMIT_MIN, PWHASH_MEMLIMIT_MIN)
    }
}

/**
 * The chat crypto core, byte-compatible with resources/js/chat (keys.js, conversation.js) and OasisChatCrypto on iOS.
 * See the JS module headers for the security reasoning; the formats, labels and checks here are identical. Conversation
 * keys, grants and messages: Conversation.kt; device copies: DeviceCopies.kt.
 */
class ChatCrypto(val sodium: Sodium) {
    // region Base64 and randomness

    /** URL-safe Base64 without padding. */
    fun toBase64(bytes: ByteArray): String = sodium.toBase64(bytes)

    /**
     * Decodes URL-safe, unpadded Base64 like libsodium.js `from_base64` (padding or other alphabets are rejected).
     * @param expectedLength if given, the decoded length must match exactly.
     */
    fun fromBase64(text: String?, expectedLength: Int? = null): ByteArray {
        if (text.isNullOrEmpty()) throw ChatCryptoException.InvalidInput("Expected a non-empty Base64 string.")
        val bytes = sodium.fromBase64(text) ?: throw ChatCryptoException.InvalidInput("Invalid Base64 encoding.")
        if (expectedLength != null && bytes.size != expectedLength) {
            throw ChatCryptoException.InvalidInput("Decoded value has the wrong length.")
        }
        return bytes
    }

    /** Cryptographically secure random bytes. */
    fun randomBytes(count: Int): ByteArray = sodium.randomBytes(count)

    /** Constant-time comparison of equal-length byte strings. */
    internal fun constantTimeEqual(a: ByteArray, b: ByteArray): Boolean = a.size == b.size && sodium.memcmp(a, b)

    // endregion

    // region Identity

    /** A fresh identity with random keys. */
    fun createIdentity(): ChatIdentity {
        val box = sodium.boxKeyPair()
        val sign = sodium.signKeyPair()
        return ChatIdentity(box.publicKey, box.secretKey, sign.publicKey, sign.secretKey)
    }

    /**
     * Encrypts the identity's private keys with a key derived from the passphrase (Argon2id + XChaCha20-Poly1305).
     * @param limits Argon2id costs; production passphrases must use [KdfLimits.Moderate] (the server only accepts those
     *   for the passphrase copy, and only [KdfLimits.Minimum] for device copies).
     */
    fun lockIdentity(identity: ChatIdentity, passphrase: String, limits: KdfLimits = KdfLimits.Moderate): LockedIdentity {
        identity.assertUsable()
        assertKdfLimits(limits.opslimit, limits.memlimit)
        val salt = randomBytes(PWHASH_SALTBYTES)
        val nonce = randomBytes(AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES)
        val kdf = LockedIdentity.Kdf(KDF_ALGORITHM, limits.opslimit, limits.memlimit, toBase64(salt))
        val key = deriveKey(passphrase, salt, limits.opslimit, limits.memlimit)
        val secret = identity.withPrivateKeys { encryption, signing -> encryption + signing }
        try {
            val associated =
                identityAssociatedData(LOCKED_IDENTITY_VERSION, kdf, salt, identity.encryptionPublicKey, identity.signingPublicKey)
            val ciphertext = sodium.aeadEncrypt(secret, associated, nonce, key)
            return LockedIdentity(
                version = LOCKED_IDENTITY_VERSION,
                kdf = kdf,
                nonce = toBase64(nonce),
                ciphertext = toBase64(ciphertext),
                publicKeys = LockedIdentity.PublicKeys(toBase64(identity.encryptionPublicKey), toBase64(identity.signingPublicKey))
            )
        } finally {
            ChatEncoding.wipe(key)
            ChatEncoding.wipe(secret)
        }
    }

    /**
     * Decrypts a locked identity. Re-derives both public keys from the private keys and compares them with the stored
     * ones. KDF costs outside MIN…MODERATE are refused before any memory is allocated.
     * @throws ChatCryptoException.WrongPassphrase, or [ChatCryptoException.InvalidInput] for a malformed object or
     *   unsupported parameters.
     */
    fun unlockIdentity(locked: LockedIdentity, passphrase: String): ChatIdentity {
        if (locked.version != LOCKED_IDENTITY_VERSION) throw ChatCryptoException.InvalidInput("Unsupported locked identity format.")
        if (locked.kdf.alg != KDF_ALGORITHM) throw ChatCryptoException.InvalidInput("Unsupported key derivation algorithm.")
        assertKdfLimits(locked.kdf.opslimit, locked.kdf.memlimit)
        val salt = fromBase64(locked.kdf.salt, PWHASH_SALTBYTES)
        val nonce = fromBase64(locked.nonce, AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES)
        val ciphertext = fromBase64(locked.ciphertext, SERIALISED_SECRET_BYTES + AEAD_XCHACHA20POLY1305_IETF_ABYTES)
        val encryptionPublicKey = fromBase64(locked.publicKeys.encryption, 32)
        val signingPublicKey = fromBase64(locked.publicKeys.signing, 32)

        val key = deriveKey(passphrase, salt, locked.kdf.opslimit, locked.kdf.memlimit)
        val secret = try {
            val associated = identityAssociatedData(locked.version, locked.kdf, salt, encryptionPublicKey, signingPublicKey)
            aeadDecrypt(ciphertext, associated, nonce, key) ?: throw ChatCryptoException.WrongPassphrase
        } finally {
            ChatEncoding.wipe(key)
        }
        try {
            if (secret.size != SERIALISED_SECRET_BYTES) throw ChatCryptoException.WrongPassphrase
            val encryptionPrivateKey = secret.copyOfRange(0, 32)
            val signingPrivateKey = secret.copyOfRange(32, SERIALISED_SECRET_BYTES)
            val derivedEncryptionPublic = sodium.scalarMultBase(encryptionPrivateKey)
            val derivedSigningPublic = sodium.signEd25519SecretKeyToPublicKey(signingPrivateKey)
            if (!constantTimeEqual(derivedEncryptionPublic, encryptionPublicKey) ||
                !constantTimeEqual(derivedSigningPublic, signingPublicKey)
            ) {
                ChatEncoding.wipe(encryptionPrivateKey)
                ChatEncoding.wipe(signingPrivateKey)
                throw ChatCryptoException.WrongPassphrase
            }
            return ChatIdentity(encryptionPublicKey, encryptionPrivateKey, signingPublicKey, signingPrivateKey)
        } finally {
            ChatEncoding.wipe(secret)
        }
    }

    /** The public-key bundle with the binding signature over both public keys. */
    fun exportPublicKeys(identity: ChatIdentity): PublicKeyBundle {
        identity.assertUsable()
        val message = canonicalBytes(KEY_BINDING_LABEL, fields(identity.encryptionPublicKey, identity.signingPublicKey))
        val binding = identity.withPrivateKeys { _, signing -> sodium.signDetached(message, signing) }
        return PublicKeyBundle(toBase64(identity.encryptionPublicKey), toBase64(identity.signingPublicKey), toBase64(binding))
    }

    /**
     * Verifies a bundle's binding signature and decodes it. Whether the signing key belongs to the person is decided by
     * the safety number, not here.
     */
    fun verifyPublicKeys(bundle: PublicKeyBundle?): VerifiedPublicKeys {
        try {
            val encryption = fromBase64(bundle?.encryption, 32)
            val signing = fromBase64(bundle?.signing, 32)
            val binding = fromBase64(bundle?.binding, SIGN_BYTES)
            val message = canonicalBytes(KEY_BINDING_LABEL, fields(encryption, signing))
            if (!verifyDetached(binding, message, signing)) throw ChatCryptoException.InvalidPublicKeys
            return VerifiedPublicKeys(encryption, signing)
        } catch (_: ChatCryptoException) {
            throw ChatCryptoException.InvalidPublicKeys
        }
    }

    /** The safety number of two signing keys: 12 groups of 5 digits, order-independent. */
    fun fingerprint(publicSigningKeyA: ByteArray, publicSigningKeyB: ByteArray): String {
        if (publicSigningKeyA.size != 32) throw ChatCryptoException.InvalidInput("publicSigningKeyA must be a Uint8Array of 32 bytes.")
        if (publicSigningKeyB.size != 32) throw ChatCryptoException.InvalidInput("publicSigningKeyB must be a Uint8Array of 32 bytes.")
        val (first, second) = if (ChatEncoding.precedesOrEquals(publicSigningKeyA, publicSigningKeyB)) {
            publicSigningKeyA to publicSigningKeyB
        } else {
            publicSigningKeyB to publicSigningKeyA
        }
        val digest = sodium.genericHash(canonicalBytes(SAFETY_NUMBER_LABEL, fields(first, second)), 64)
        return (0 until 12).joinToString(" ") { group ->
            var value = 0L
            for (index in 0 until 5) value = value * 256 + (digest[group * 5 + index].toInt() and 0xFF)
            (value % 100_000).toString().padStart(5, '0')
        }
    }

    // endregion

    // region Internals shared with Conversation.kt and DeviceCopies.kt

    /** Argon2id: MIN…MODERATE costs only (a manipulated server cannot force a huge allocation). */
    internal fun assertKdfLimits(opslimit: Long, memlimit: Long) {
        if (opslimit !in PWHASH_OPSLIMIT_MIN..PWHASH_OPSLIMIT_MODERATE || memlimit !in PWHASH_MEMLIMIT_MIN..PWHASH_MEMLIMIT_MODERATE) {
            throw ChatCryptoException.InvalidInput("Key derivation parameters are out of the accepted range.")
        }
    }

    /** Passphrase (NFC-normalised UTF-8) → 32-byte key with Argon2id13. */
    internal fun deriveKey(passphrase: String, salt: ByteArray, opslimit: Long, memlimit: Long): ByteArray {
        if (passphrase.isEmpty()) throw ChatCryptoException.InvalidInput("The passphrase must be a non-empty string.")
        val passphraseBytes = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        try {
            return sodium.pwhashArgon2id(AEAD_XCHACHA20POLY1305_IETF_KEYBYTES, passphraseBytes, salt, opslimit, memlimit)
                ?: throw ChatCryptoException.InvalidInput("Key derivation failed.")
        } finally {
            ChatEncoding.wipe(passphraseBytes)
        }
    }

    internal fun identityAssociatedData(
        version: Int,
        kdf: LockedIdentity.Kdf,
        salt: ByteArray,
        encryptionPublicKey: ByteArray,
        signingPublicKey: ByteArray
    ): ByteArray = canonicalBytes(
        IDENTITY_AD_LABEL,
        fields(version.toString(), kdf.alg, kdf.opslimit.toString(), kdf.memlimit.toString(), salt, encryptionPublicKey, signingPublicKey)
    )

    internal fun aeadDecrypt(ciphertext: ByteArray, associated: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (ciphertext.size < AEAD_XCHACHA20POLY1305_IETF_ABYTES || nonce.size != 24 || key.size != 32) return null
        return sodium.aeadDecrypt(ciphertext, associated, nonce, key)
    }

    internal fun verifyDetached(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        return sodium.signVerifyDetached(signature, message, publicKey)
    }

    // endregion

    companion object {
        /** Version of the locked-identity format. */
        const val LOCKED_IDENTITY_VERSION = 1

        /** Only Argon2id v1.3 is accepted. */
        const val KDF_ALGORITHM = "argon2id13"

        /** Envelope format version. */
        const val MESSAGE_VERSION = 1

        /** Key-grant format version. */
        const val KEY_GRANT_VERSION = 1

        /** Maximum plaintext length in Unicode code points. */
        const val MAX_MESSAGE_LENGTH = 4000

        /** Maximum plaintext size in UTF-8 bytes (one Reverb event stays below 10 KiB). */
        const val MAX_MESSAGE_BYTES = 5984

        internal const val IDENTITY_AD_LABEL = "oasis-chat/locked-identity/v1"
        internal const val KEY_BINDING_LABEL = "oasis-chat/key-binding/v1"
        internal const val SAFETY_NUMBER_LABEL = "oasis-chat/safety-number/v1"
        internal const val MESSAGE_AD_LABEL = "oasis-chat/message-ad/v1"
        internal const val MESSAGE_SIGNATURE_LABEL = "oasis-chat/message-signature/v1"
        internal const val KEY_GRANT_LABEL = "oasis-chat/key-grant/v1"
        internal const val DEVICE_LINK_LABEL = "oasis-chat/device-link/v1"
        internal const val SERIALISED_SECRET_BYTES = 32 + 64
    }
}
