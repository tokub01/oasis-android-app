package de.velwebsolutions.oasis.chat.core

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

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
internal fun newCredentialId(): String =
    APP_CREDENTIAL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))

/** Small per-device choices of the chat (iOS: UserDefaults). */
interface ChatPreferences {
    /** "Nicht jetzt" on the offer to unlock with biometrics next time. */
    suspend fun biometricOfferDeclined(userId: String): Boolean

    /** Remembers the answer to that offer. */
    suspend fun setBiometricOfferDeclined(userId: String, declined: Boolean)

    /** „Später“ on the chat list's backup row: until when it stays hidden (null: not hidden). */
    suspend fun backupRowSnoozedUntil(userId: String): Instant?

    /** Hides the backup row until [until]. */
    suspend fun snoozeBackupRow(userId: String, until: Instant)

    /** How far the swipe hint got on this device (docs/mildcrowd-chat-client.md, „C8 Swipe hint once“). */
    suspend fun swipeHint(): SwipeHintProgress

    /** Keeps the swipe hint's progress. */
    suspend fun setSwipeHint(progress: SwipeHintProgress)
}

/** The swipe hint: [done] for good, or the chats it was [shown] in (it stops after three). */
data class SwipeHintProgress(val done: Boolean = false, val shown: Set<String> = emptySet()) {
    /** Whether the hint shows in the chat [conversationId]: not done, and one of the first three chats. */
    fun showsIn(conversationId: String): Boolean = !done && (conversationId in shown || shown.size < MAX_CHATS)

    companion object {
        /** After three chats the hint is gone. */
        const val MAX_CHATS = 3
    }
}
