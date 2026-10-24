package de.velwebsolutions.oasis.chat.core

import com.goterl.lazysodium.Sodium as NativeSodium
import com.sun.jna.Memory
import com.sun.jna.NativeLong
import de.velwebsolutions.oasis.chatcrypto.Sodium
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.AEAD_XCHACHA20POLY1305_IETF_ABYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.BASE64_VARIANT_URLSAFE_NO_PADDING
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.BOX_PUBLICKEYBYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.BOX_SEALBYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.BOX_SECRETKEYBYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.PWHASH_ALG_ARGON2ID13
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.SIGN_BYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.SIGN_PUBLICKEYBYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumConstants.SIGN_SECRETKEYBYTES
import de.velwebsolutions.oasis.chatcrypto.SodiumKeyPair

/**
 * [Sodium] in the app through lazysodium-android (libsodium for arm64-v8a, armeabi-v7a, x86 and x86_64, loaded with
 * JNA): [native] is `SodiumAndroid` in the app. The unit tests pass lazysodium-java's `SodiumJava`, which has the same
 * class and the same calls, so this binding runs on the JVM too.
 */
class AndroidSodium(private val native: NativeSodium) : Sodium {
    init {
        // 0 on the first call, 1 when already initialised; lazysodium has loaded the library at this point.
        native.sodium_init()
    }

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also { native.randombytes_buf(it, count) }

    override fun memcmp(a: ByteArray, b: ByteArray): Boolean = native.sodium_memcmp(a, b, a.size) == 0

    override fun toBase64(bytes: ByteArray): String {
        val length = native.sodium_base64_encoded_len(bytes.size, BASE64_VARIANT_URLSAFE_NO_PADDING)
        val output = ByteArray(length)
        native.sodium_bin2base64(output, length, bytes, bytes.size, BASE64_VARIANT_URLSAFE_NO_PADDING)
        // The encoded length counts libsodium's terminating NUL.
        return String(output, 0, length - 1, Charsets.US_ASCII)
    }

    override fun fromBase64(text: String): ByteArray? {
        val input = text.toByteArray(Charsets.UTF_8)
        val output = ByteArray(input.size * 3 / 4 + 3)
        // size_t has 4 or 8 bytes; on the little-endian platforms the binding ships for, 8 zeroed bytes read as a long
        // give the written length either way.
        val written = Memory(Long.SIZE_BYTES.toLong()).apply { clear() }
        // No `b64_end`: libsodium then rejects trailing unparsed characters, like libsodium.js.
        val result = native.sodium_base642bin(
            output,
            output.size,
            input,
            input.size,
            null,
            written,
            null,
            BASE64_VARIANT_URLSAFE_NO_PADDING
        )
        if (result != 0) return null
        return output.copyOf(written.getLong(0).toInt())
    }

    override fun aeadEncrypt(message: ByteArray, associatedData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val ciphertext = ByteArray(message.size + AEAD_XCHACHA20POLY1305_IETF_ABYTES)
        val length = LongArray(1)
        native.crypto_aead_xchacha20poly1305_ietf_encrypt(
            ciphertext, length, message, message.size.toLong(), associatedData, associatedData.size.toLong(), null, nonce, key
        )
        return ciphertext.copyOf(length[0].toInt())
    }

    override fun aeadDecrypt(ciphertext: ByteArray, associatedData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (ciphertext.size < AEAD_XCHACHA20POLY1305_IETF_ABYTES) return null
        val message = ByteArray(ciphertext.size)
        val length = LongArray(1)
        val result = native.crypto_aead_xchacha20poly1305_ietf_decrypt(
            message, length, null, ciphertext, ciphertext.size.toLong(), associatedData, associatedData.size.toLong(), nonce, key
        )
        if (result != 0) {
            message.fill(0)
            return null
        }
        return message.copyOf(length[0].toInt()).also { message.fill(0) }
    }

    override fun boxKeyPair(): SodiumKeyPair {
        val publicKey = ByteArray(BOX_PUBLICKEYBYTES)
        val secretKey = ByteArray(BOX_SECRETKEYBYTES)
        native.crypto_box_keypair(publicKey, secretKey)
        return SodiumKeyPair(publicKey, secretKey)
    }

    override fun boxSeal(message: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        val sealed = ByteArray(BOX_SEALBYTES + message.size)
        native.crypto_box_seal(sealed, message, message.size.toLong(), recipientPublicKey)
        return sealed
    }

    override fun boxSealOpen(sealed: ByteArray, publicKey: ByteArray, secretKey: ByteArray): ByteArray? {
        if (sealed.size < BOX_SEALBYTES) return null
        val message = ByteArray(sealed.size - BOX_SEALBYTES)
        return if (native.crypto_box_seal_open(message, sealed, sealed.size.toLong(), publicKey, secretKey) == 0) message else null
    }

    override fun signKeyPair(): SodiumKeyPair {
        val publicKey = ByteArray(SIGN_PUBLICKEYBYTES)
        val secretKey = ByteArray(SIGN_SECRETKEYBYTES)
        native.crypto_sign_keypair(publicKey, secretKey)
        return SodiumKeyPair(publicKey, secretKey)
    }

    override fun signDetached(message: ByteArray, secretKey: ByteArray): ByteArray {
        val signature = ByteArray(SIGN_BYTES)
        native.crypto_sign_detached(signature, null, message, message.size.toLong(), secretKey)
        return signature
    }

    override fun signVerifyDetached(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean =
        native.crypto_sign_verify_detached(signature, message, message.size.toLong(), publicKey) == 0

    override fun signEd25519SecretKeyToPublicKey(secretKey: ByteArray): ByteArray =
        ByteArray(SIGN_PUBLICKEYBYTES).also { native.crypto_sign_ed25519_sk_to_pk(it, secretKey) }

    override fun scalarMultBase(secretKey: ByteArray): ByteArray = ByteArray(32).also { native.crypto_scalarmult_base(it, secretKey) }

    override fun genericHash(message: ByteArray, length: Int): ByteArray =
        ByteArray(length).also { native.crypto_generichash(it, length, message, message.size.toLong(), null, 0) }

    override fun pwhashArgon2id(length: Int, password: ByteArray, salt: ByteArray, opslimit: Long, memlimit: Long): ByteArray? {
        val key = ByteArray(length)
        val result = native.crypto_pwhash(
            key,
            length.toLong(),
            password,
            password.size.toLong(),
            salt,
            opslimit,
            NativeLong(memlimit),
            PWHASH_ALG_ARGON2ID13
        )
        return if (result == 0) key else null
    }
}
