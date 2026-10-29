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
 * sign-out. Keys only open while Oasis is in front: an unlock that finishes in the background is refused. A screen of
 * another app that Oasis opens itself ([openExternalScreen]: the file or photo picker, a file in another app) keeps the
 * vault open until Oasis is back, at most [externalHold] – the web's file dialog is no `pagehide`, and iOS shows its
 * pickers inside the app.
 */
class ChatVault(
    private val scope: CoroutineScope,
    /** Time without interaction after which the vault locks (the web's `IDLE_LOCK_MS`). */
    var idleLock: Duration = 30.minutes,
    val clock: ServerClock = ServerClock(),
    /** How long a screen Oasis opened itself keeps the vault open while Oasis is in the background. */
    var externalHold: Duration = 5.minutes
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

    /** The answer of `GET /identity` (null: the chat is not set up yet). */
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

    /** Whether Oasis is in front ([cameToForeground], [lockForBackground]); keys only open then. */
    var inForeground: Boolean = true
        private set

    private val conversationKeys = mutableMapOf<String, ByteArray>()
    private val publicKeys = mutableMapOf<String, VerifiedPublicKeys>()
    private var idleTimer: Job? = null

    /** Oasis opened a screen of another app ([openExternalScreen]): the next trip to the background waits for its return. */
    private var externalScreen = false

    /** Locks after [externalHold] while that screen keeps Oasis in the background. */
    private var holdTimer: Job? = null

    /** Bind the vault to the signed-in member; another member wipes everything first. */
    fun prepare(userId: String) {
        if (userId == this.userId) return
        lock(announce = false)
        this.userId = userId
        stored = Stored(false, null)
        publicKeys.clear()
        safety = SafetyLists.Empty
    }

    /**
     * Adopt an unlocked identity, unless a lock happened since [captured] was taken or Oasis is in the background (then
     * it is wiped: the vault locks in the background, also for an unlock that only finished there).
     */
    fun unlock(newIdentity: ChatIdentity, captured: Int): Boolean {
        if (captured != generation || !inForeground) {
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
        holdTimer?.cancel()
        holdTimer = null
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

    /**
     * The app went to the background: the vault locks – unless the member is in a screen Oasis opened itself
     * ([openExternalScreen]); then it locks only when Oasis is not back within [externalHold].
     */
    fun lockForBackground() {
        inForeground = false
        val held = externalScreen
        externalScreen = false
        if (!unlocked) return
        if (held) {
            // Every lock and the return to the foreground cancel this timer.
            holdTimer = scope.launch {
                delay(externalHold)
                lock()
            }
        } else {
            lock()
        }
    }

    /**
     * Oasis is in front again – back from the background, or the chat's screen resumed after a picker that only covered
     * it: a screen it opened is done, and the next trip to the background locks as usual.
     */
    fun cameToForeground() {
        inForeground = true
        externalScreen = false
        holdTimer?.cancel()
        holdTimer = null
    }

    /**
     * Opens a screen of another app for the member – the system's file or photo picker, a file in another app – with
     * [start], which says whether it opened. Until Oasis is in front again (or the member touches Oasis) the trip to the
     * background it causes does not lock at once, only after [externalHold]; a screen that did not open changes nothing.
     */
    fun openExternalScreen(start: () -> Boolean): Boolean {
        externalScreen = true
        var opened = false
        try {
            opened = start()
        } finally {
            if (!opened) externalScreen = false
        }
        return opened
    }

    /**
     * Call on interaction (taps on a chat screen, typing): restarts the idle timer, which locks the vault after
     * [idleLock] without interaction – like the web's timer reset on `pointerdown` / `keydown`. The member is in Oasis
     * then, so a screen Oasis opened earlier (a picker shown above it and closed again) no longer keeps the vault open.
     */
    fun touch() {
        externalScreen = false
        idleTimer?.cancel()
        if (!unlocked) return
        // Every lock and every touch cancels this timer, so when it fires the same session is still open.
        idleTimer = scope.launch {
            delay(idleLock)
            lock()
        }
    }

    /** The key of one conversation version, while the vault is open. */
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

    /** The verified public keys of a member, as far as known in this app session. */
    fun publicKeys(userId: String): VerifiedPublicKeys? = publicKeys[userId]
}

/** The server clock (client/clock.js): offset from `server_time`; signed expiry = server now + lifetime. */
class ServerClock(private val localNow: () -> Instant = Instant::now) {
    @Volatile private var offsetMillis = 0L

    /** Takes the offset to the server's clock from a `server_time` (ignored when unreadable). */
    fun sync(serverTime: String) {
        val date = OasisJson.parseInstant(serverTime) ?: return
        offsetMillis = date.toEpochMilli() - localNow().toEpochMilli()
    }

    /** The server's time now. */
    fun now(): Instant = localNow().plusMillis(offsetMillis)

    /** Expiry of a message sent now, rounded down to whole seconds. */
    fun expiry(lifetimeDays: Int): Instant = Instant.ofEpochSecond(Math.floorDiv(now().toEpochMilli(), 1000L) + lifetimeDays * 86_400L)
}
