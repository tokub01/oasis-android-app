package de.velwebsolutions.oasis.chatcrypto

/**
 * The few libsodium primitives the chat needs, so the crypto core runs without Android: the app passes a
 * lazysodium-android binding, the JVM tests a lazysodium-java one. Every function works on plain byte arrays and maps
 * one libsodium call; failures of the "open" functions return null instead of throwing.
 */
interface Sodium {
    /** `randombytes_buf`. */
    fun randomBytes(count: Int): ByteArray

    /** `sodium_memcmp` of two equal-length arrays (constant time); the caller compares the lengths first. */
    fun memcmp(a: ByteArray, b: ByteArray): Boolean

    /** `sodium_bin2base64` with `sodium_base64_VARIANT_URLSAFE_NO_PADDING`. */
    fun toBase64(bytes: ByteArray): String

    /**
     * `sodium_base642bin` with `URLSAFE_NO_PADDING`, no ignored characters and no `b64_end`, so trailing or invalid
     * characters fail like libsodium.js `from_base64`. Null when the text is not valid.
     */
    fun fromBase64(text: String): ByteArray?

    /** `crypto_aead_xchacha20poly1305_ietf_encrypt` (combined mode, ciphertext || tag). */
    fun aeadEncrypt(message: ByteArray, associatedData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray

    /** `crypto_aead_xchacha20poly1305_ietf_decrypt`; null when the tag does not verify. */
    fun aeadDecrypt(ciphertext: ByteArray, associatedData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?

    /** `crypto_box_keypair` (X25519). */
    fun boxKeyPair(): SodiumKeyPair

    /** `crypto_box_seal`. */
    fun boxSeal(message: ByteArray, recipientPublicKey: ByteArray): ByteArray

    /** `crypto_box_seal_open`; null when it cannot be opened with this key pair. */
    fun boxSealOpen(sealed: ByteArray, publicKey: ByteArray, secretKey: ByteArray): ByteArray?

    /** `crypto_sign_keypair` (Ed25519; the secret key is libsodium's 64-byte seed || public key). */
    fun signKeyPair(): SodiumKeyPair

    /** `crypto_sign_detached`. */
    fun signDetached(message: ByteArray, secretKey: ByteArray): ByteArray

    /** `crypto_sign_verify_detached`. */
    fun signVerifyDetached(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean

    /** `crypto_sign_ed25519_sk_to_pk`. */
    fun signEd25519SecretKeyToPublicKey(secretKey: ByteArray): ByteArray

    /** `crypto_scalarmult_base` (the X25519 public key of a secret key). */
    fun scalarMultBase(secretKey: ByteArray): ByteArray

    /** `crypto_generichash` (BLAKE2b) without a key. */
    fun genericHash(message: ByteArray, length: Int): ByteArray

    /** `crypto_pwhash` with `crypto_pwhash_ALG_ARGON2ID13`; null when libsodium refuses (e.g. out of memory). */
    fun pwhashArgon2id(length: Int, password: ByteArray, salt: ByteArray, opslimit: Long, memlimit: Long): ByteArray?
}

/** A libsodium key pair. */
class SodiumKeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

/** libsodium's fixed sizes and limits (the same on every platform), named as in the C API. */
object SodiumConstants {
    const val AEAD_XCHACHA20POLY1305_IETF_KEYBYTES = 32
    const val AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES = 24
    const val AEAD_XCHACHA20POLY1305_IETF_ABYTES = 16
    const val BOX_PUBLICKEYBYTES = 32
    const val BOX_SECRETKEYBYTES = 32
    const val BOX_SEALBYTES = 48
    const val SIGN_PUBLICKEYBYTES = 32
    const val SIGN_SECRETKEYBYTES = 64
    const val SIGN_BYTES = 64
    const val PWHASH_SALTBYTES = 16
    const val PWHASH_ALG_ARGON2ID13 = 2
    const val PWHASH_OPSLIMIT_MIN = 1L
    const val PWHASH_OPSLIMIT_MODERATE = 3L
    const val PWHASH_MEMLIMIT_MIN = 8192L
    const val PWHASH_MEMLIMIT_MODERATE = 268_435_456L
    const val BASE64_VARIANT_URLSAFE_NO_PADDING = 7
}
