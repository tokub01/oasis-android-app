package de.velwebsolutions.oasis.chat.core

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A device secret and the credential id of its server row. */
class DeviceSecret(val credentialId: String, val secret: ByteArray)

/**
 * Where the app keeps the 32-byte secret of its biometric copy – the native counterpart of the browser's WebAuthn PRF
 * secret (client/device-unlock.js). The server stores, as for the browser, a copy of the identity locked with that secret
 * (`/chat/device-unlocks`, minimal Argon2id costs); the secret itself never leaves the device. On Android it is
 * encrypted with an Android Keystore key that only a strong biometric unlocks ([KeystoreDeviceSecretStore]).
 */
interface DeviceSecretStore {
    /** Whether this device can protect a secret with a strong biometric (fingerprint or face). */
    fun isAvailable(): Boolean

    /** The credential id of this device's copy for the member, if one was set up (no biometric prompt). */
    suspend fun credentialId(userId: String): String?

    /**
     * Creates and stores a new secret behind biometrics (the member confirms once); returns its credential id and the
     * secret.
     * @throws ChatKeyringException.DeviceUnlockUnsupported when the device cannot, or the prompt was cancelled.
     */
    suspend fun create(userId: String): DeviceSecret

    /** Reads the secret after the biometric prompt; null when cancelled or failed. */
    suspend fun secret(userId: String, reason: String): ByteArray?

    /** Forgets this device's copy (removed, or the keys were replaced). */
    suspend fun delete(userId: String)
}

/** A device's credential ids carry this prefix so the server rows of the app are recognisable (the web lists them too). */
const val APP_CREDENTIAL_PREFIX = "app-"

/** A new credential id: the prefix and 16 random bytes in URL-safe Base64 without padding. */
internal fun newCredentialId(random: SecureRandom = SecureRandom()): String =
    APP_CREDENTIAL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))

/** In-memory store for tests and previews: [available] decides whether the biometric unlock works. */
class MemoryDeviceSecretStore(@Volatile var available: Boolean = true) : DeviceSecretStore {
    private val items = ConcurrentHashMap<String, DeviceSecret>()

    /** Simulates a cancelled biometric prompt. */
    @Volatile var denies: Boolean = false

    override fun isAvailable(): Boolean = available

    override suspend fun credentialId(userId: String): String? = items[userId]?.credentialId

    override suspend fun create(userId: String): DeviceSecret {
        if (!available || denies) throw ChatKeyringException.DeviceUnlockUnsupported
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        return DeviceSecret(newCredentialId(), secret).also { items[userId] = DeviceSecret(it.credentialId, secret.copyOf()) }
    }

    override suspend fun secret(userId: String, reason: String): ByteArray? = if (denies) null else items[userId]?.secret?.copyOf()

    override suspend fun delete(userId: String) {
        items.remove(userId)
    }
}

/** Small per-device choices of the chat (iOS: UserDefaults). */
interface ChatPreferences {
    /** "Nicht jetzt" on the offer to unlock with biometrics next time. */
    suspend fun biometricOfferDeclined(userId: String): Boolean

    suspend fun setBiometricOfferDeclined(userId: String, declined: Boolean)
}

/** [ChatPreferences] in memory, for tests. */
class MemoryChatPreferences : ChatPreferences {
    private val declined = ConcurrentHashMap.newKeySet<String>()

    override suspend fun biometricOfferDeclined(userId: String) = userId in declined

    override suspend fun setBiometricOfferDeclined(userId: String, declined: Boolean) {
        if (declined) this.declined += userId else this.declined -= userId
    }
}
