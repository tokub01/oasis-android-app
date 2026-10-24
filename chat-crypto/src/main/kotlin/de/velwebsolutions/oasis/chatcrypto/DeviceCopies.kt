package de.velwebsolutions.oasis.chatcrypto

import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.DEVICE_LINK_LABEL
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A one-time X25519 key pair of a device that asks another device to open the chat ("Mit anderem Gerät freigeben"). */
class LinkKeyPair(
    val publicKey: ByteArray,
    /** The private key, wiped after use. */
    val privateKey: ByteArray,
    private val crypto: ChatCrypto
) {
    /** Base64 (URL-safe, no padding) of the public key, as sent to `POST /device-links`. */
    val publicKeyBase64: String get() = crypto.toBase64(publicKey)

    /** Overwrites the one-time private key with zeros once the approval is opened or the request ends. */
    fun wipe() = ChatEncoding.wipe(privateKey)
}

/**
 * What an approving device sends for a link request (and the asking device picks up): the identity locked with a
 * random key, and that key sealed for the request's one-time public key.
 */
@Serializable
data class LinkApproval(
    @SerialName("locked_identity") val lockedIdentity: LockedIdentity,
    @SerialName("sealed_key") val sealedKey: String
)

// Device copies of the identity (resources/js/chat/client/device-link.js and device-unlock.js): device links and the
// app's biometric unlock. Every copy is a normal LockedIdentity with the minimal KDF costs, because its key is a random
// 256-bit secret, not a passphrase.

/** The 8-digit check number of a one-time public key, e.g. `"4821 9930"` – the same on both devices. */
fun ChatCrypto.checkNumber(publicKey: String): String {
    val hash = sodium.genericHash("$DEVICE_LINK_LABEL:$publicKey".toByteArray(Charsets.UTF_8), 8)
    val value = (
        (hash[0].toLong() and 0xFF) shl 24 or ((hash[1].toLong() and 0xFF) shl 16) or
            ((hash[2].toLong() and 0xFF) shl 8) or (hash[3].toLong() and 0xFF)
        ) % 100_000_000
    val padded = value.toString().padStart(8, '0')
    return "${padded.take(4)} ${padded.takeLast(4)}"
}

/** Asking device: a fresh one-time key pair. */
fun ChatCrypto.createLinkKeyPair(): LinkKeyPair {
    val pair = sodium.boxKeyPair()
    return LinkKeyPair(pair.publicKey, pair.secretKey, this)
}

/** Unlocked device: lock the identity with a random key and seal that key for the request's public key. */
fun ChatCrypto.approveLink(identity: ChatIdentity, requestPublicKey: String): LinkApproval {
    val key = randomBytes(32)
    try {
        val copy = lockIdentity(identity, toBase64(key), KdfLimits.Minimum)
        val sealed = seal(key, fromBase64(requestPublicKey, 32))
        return LinkApproval(copy, toBase64(sealed))
    } finally {
        ChatEncoding.wipe(key)
    }
}

/**
 * Asking device: open the approval with the one-time key pair. The caller must still compare the result with the
 * published keys (as after every unlock). The one-time private key is wiped.
 */
fun ChatCrypto.openLinkApproval(lockedIdentity: LockedIdentity, sealedKey: String, keyPair: LinkKeyPair): ChatIdentity {
    try {
        val sealed = fromBase64(sealedKey, 80)
        val key = sealOpen(sealed, keyPair.publicKey, keyPair.privateKey) ?: throw ChatCryptoException.WrongPassphrase
        try {
            return unlockIdentity(lockedIdentity, toBase64(key))
        } finally {
            ChatEncoding.wipe(key)
        }
    } finally {
        keyPair.wipe()
    }
}

/**
 * The "passphrase" of a device-unlock copy: standard Base64 with padding of the 32-byte device secret, exactly as the
 * browser turns its WebAuthn PRF secret into one (`btoa`).
 */
fun deviceSecretPassphrase(secret: ByteArray): String = Base64.getEncoder().encodeToString(secret)

/** Lock the identity with a device secret (biometric copy). */
fun ChatCrypto.lockForDevice(identity: ChatIdentity, secret: ByteArray): LockedIdentity {
    if (secret.size != 32) throw ChatCryptoException.InvalidInput("The device secret must have 32 bytes.")
    return lockIdentity(identity, deviceSecretPassphrase(secret), KdfLimits.Minimum)
}

/** Open a biometric copy with the device secret. */
fun ChatCrypto.unlockWithDeviceSecret(locked: LockedIdentity, secret: ByteArray): ChatIdentity =
    unlockIdentity(locked, deviceSecretPassphrase(secret))
