package de.velwebsolutions.oasis.chat.core

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** In-memory store for the tests: [available] decides whether the biometric unlock works. */
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

/** [ChatPreferences] in memory, for tests. */
class MemoryChatPreferences : ChatPreferences {
    private val declined = ConcurrentHashMap.newKeySet<String>()

    override suspend fun biometricOfferDeclined(userId: String) = userId in declined

    override suspend fun setBiometricOfferDeclined(userId: String, declined: Boolean) {
        if (declined) this.declined += userId else this.declined -= userId
    }
}
