package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chat.api.SafetyLists
import de.velwebsolutions.oasis.chat.api.StoredIdentity
import de.velwebsolutions.oasis.chatcrypto.ChatEncoding
import de.velwebsolutions.oasis.chatcrypto.ChatIdentity
import de.velwebsolutions.oasis.chatcrypto.VerifiedPublicKeys
import de.velwebsolutions.oasis.core.network.OasisJson
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The in-memory vault of the chat (resources/js/chat/client/session.js): the unlocked identity, the conversation keys
 * per (conversation, key version) and the verified public keys of other members. Nothing secret is ever written to disk;
 * [lock] wipes the private keys and every conversation key. One vault per app process, reset when another member signs
 * in. Used from the main thread only (like iOS' `@MainActor`); the crypto runs elsewhere and hands results back.
 *
 * Locking: "Sperren", the app going to the background (the web locks on `pagehide`), 30 minutes without interaction, and
 * sign-out.
 */
class ChatVault(
    private val scope: CoroutineScope,
    /** Time without interaction after which the vault locks (the web's `IDLE_LOCK_MS`). */
    var idleLock: Duration = 30.minutes,
    val clock: ServerClock = ServerClock()
) {
    /** The stored identity as far as it is known: [isKnown] false until `GET /identity` answered. */
    data class Stored(val isKnown: Boolean, val identity: StoredIdentity?)

    /** The signed-in account as a string, as in all crypto objects. */
    var userId: String = ""
        private set

    var identity: ChatIdentity? = null
        private set

    private val unlockedState = MutableStateFlow(false)

    /** Whether the keys are open in memory. */
    val unlockedFlow: StateFlow<Boolean> = unlockedState.asStateFlow()

    val unlocked: Boolean get() = identity != null

    /** `GET /identity`: unknown until loaded; known with null when the chat is not set up yet. */
    var stored: Stored = Stored(false, null)
        private set

    /** The stored identity, if known and set up. */
    val storedIdentity: StoredIdentity? get() = stored.identity

    fun setStoredIdentity(value: StoredIdentity?) {
        stored = Stored(true, value)
    }

    /** Increases on every lock; async work may only keep keys if it did not change meanwhile. */
    var generation = 0
        private set

    /**
     * The result of an action that changes the screen ("Nachrichten gesperrt …"), shown as a toast by the chat gate above
     * whatever chat screen comes next.
     */
    val announcement = MutableStateFlow<String?>(null)

    var safety: SafetyLists = SafetyLists.Empty

    private val conversationKeys = mutableMapOf<String, ByteArray>()
    private val publicKeys = mutableMapOf<String, VerifiedPublicKeys>()
    private var idleTimer: Job? = null

    /** Bind the vault to the signed-in member; another member wipes everything first. */
    fun prepare(userId: String) {
        if (userId == this.userId) return
        lock(announce = false)
        this.userId = userId
        stored = Stored(false, null)
        publicKeys.clear()
        safety = SafetyLists.Empty
    }

    /** Adopt an unlocked identity, unless a lock happened since [captured] was taken (then it is wiped). */
    fun unlock(newIdentity: ChatIdentity, captured: Int): Boolean {
        if (captured != generation) {
            newIdentity.wipe()
            return false
        }
        lock(announce = false)
        identity = newIdentity
        unlockedState.value = true
        touch()
        return true
    }

    /** Wipe the identity and all conversation keys. Verified public keys are public and stay. */
    fun lock(announce: Boolean = true) {
        generation += 1
        idleTimer?.cancel()
        idleTimer = null
        val wasUnlocked = identity != null
        identity?.wipe()
        identity = null
        unlockedState.value = false
        conversationKeys.values.forEach(ChatEncoding::wipe)
        conversationKeys.clear()
        if (announce && wasUnlocked) {
            announcement.value = "Nachrichten gesperrt. Deine Schlüssel sind aus dem Speicher gelöscht."
        }
    }

    /** The app went to the background. */
    fun lockForBackground() {
        if (unlocked) lock()
    }

    /**
     * Call on interaction (taps on a chat screen, typing): restarts the idle timer, which locks the vault after
     * [idleLock] without interaction – like the web's timer reset on `pointerdown` / `keydown`.
     */
    fun touch() {
        idleTimer?.cancel()
        if (!unlocked) return
        val captured = generation
        idleTimer = scope.launch {
            delay(idleLock)
            if (unlocked && captured == generation) lock()
        }
    }

    fun conversationKey(conversationId: String, keyVersion: Int): ByteArray? = conversationKeys["$conversationId:$keyVersion"]

    /**
     * Keep a key unless the vault was locked since [captured] was taken (then the key is wiped). A key it replaces in the
     * same slot is wiped.
     */
    fun rememberConversationKey(conversationId: String, keyVersion: Int, key: ByteArray, captured: Int): Boolean {
        if (!unlocked || captured != generation) {
            ChatEncoding.wipe(key)
            return false
        }
        val slot = "$conversationId:$keyVersion"
        conversationKeys[slot]?.let(ChatEncoding::wipe)
        conversationKeys[slot] = key
        return true
    }

    /** Remember verified keys; true if a different signing key was known in this app session (warn). */
    fun rememberPublicKeys(userId: String, keys: VerifiedPublicKeys): Boolean {
        val previous = publicKeys[userId]
        publicKeys[userId] = keys
        return previous != null && !previous.signing.contentEquals(keys.signing)
    }

    fun publicKeys(userId: String): VerifiedPublicKeys? = publicKeys[userId]
}

/** The server clock (client/clock.js): offset from `server_time`; signed expiry = server now + lifetime. */
class ServerClock(private val localNow: () -> Instant = Instant::now) {
    @Volatile private var offsetMillis = 0L

    fun sync(serverTime: String) {
        val date = OasisJson.parseInstant(serverTime) ?: return
        offsetMillis = date.toEpochMilli() - localNow().toEpochMilli()
    }

    fun now(): Instant = localNow().plusMillis(offsetMillis)

    /** Expiry of a message sent now, rounded down to whole seconds. */
    fun expiry(lifetimeDays: Int): Instant = Instant.ofEpochSecond(Math.floorDiv(now().toEpochMilli(), 1000L) + lifetimeDays * 86_400L)
}
