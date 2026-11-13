package de.velwebsolutions.oasis.chat.ui

import de.velwebsolutions.oasis.chat.core.ChatSpam
import de.velwebsolutions.oasis.core.network.ApiError
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The checks of one chat before a message leaves: sensitive data ([de.velwebsolutions.oasis.chat.core.ChatSafety]),
 * shouting or repetition (docs/oasis-chat-client.md, „Schreien, Wiederholungen, Fluten“; [ChatSpam]) need a second press
 * with the same text, flooding pauses sending. A pause counts down once a second in [scope] and tells [show] the
 * seconds left, then null when it is over.
 */
internal class SendGuard(private val scope: CoroutineScope, private val now: () -> Instant, private val show: (Int?) -> Unit) {
    private var confirmed: String? = null
    private var sensitiveConfirmed: String? = null
    private val flood = ChatSpam.FloodGuard()

    /** „Trotzdem senden“ waits for the text that got the hint. */
    val asksAgain: Boolean get() = confirmed != null

    /** Typing: a changed text asks again. */
    fun edited(text: String) {
        val trimmed = text.trim()
        if (confirmed != trimmed) confirmed = null
        if (sensitiveConfirmed != trimmed) sensitiveConfirmed = null
    }

    /**
     * The hint that holds [text] back on this press: the [sensitive] data it would send first, then shouting or
     * repetition; null: it may leave (no hint, or the second press with the same text).
     */
    fun hold(text: String, sensitive: List<String>): String? {
        if (sensitive.isNotEmpty() && sensitiveConfirmed != text) {
            sensitiveConfirmed = text
            return "Du bist dabei, ${sensitive.joinToString(" und ")} zu verschicken. $SENSITIVE_ADVICE"
        }
        return ChatSpam.draftHint(text)?.takeIf { confirmed != text }?.also { confirmed = text }
    }

    /** Whether [text] may leave now; otherwise the pause begins. */
    fun mayLeave(text: String): Boolean {
        val wait = flood.wait(seconds(), text)
        if (wait > 0) startPause(wait)
        return wait == 0
    }

    /** [text] left: it counts for flooding, and a later hint asks again. */
    fun sent(text: String) {
        forget()
        flood.record(seconds(), text)
    }

    /** The chat closed (or a message left): nothing is confirmed any more. */
    fun forget() {
        confirmed = null
        sensitiveConfirmed = null
    }

    /**
     * Whether [error] is the server's own limit per chat (content-agnostic, 429 with [ChatSpam.FLOOD_PAUSE]); then the
     * same pause as on this device begins, as long as `Retry-After` says (else 30).
     */
    fun limited(error: Exception): Boolean {
        if (error !is ApiError.TooManyRequests || error.serverMessage != ChatSpam.FLOOD_PAUSE) return false
        flood.pause(seconds(), maxOf(1, error.retryAfter ?: SERVER_PAUSE_SECONDS))
        startPause(flood.remaining(seconds()))
        return true
    }

    /**
     * [state] after typing: shouting or repetition before the [tone] hint, „Trotzdem senden“ while it waits for this
     * text; a running pause keeps its own hint and countdown.
     */
    fun typed(state: ConversationState, tone: String?): ConversationState = if (state.paused) {
        state.copy(hint = ChatSpam.FLOOD_PAUSE)
    } else {
        state.copy(
            hint = ChatSpam.draftHint(state.draft)
                ?: tone?.let { "Kurzer Hinweis: „$it“ wirkt schnell herablassend. Bitte achte auf einen freundlichen Ton." },
            sendLabel = ChatSpam.SEND_ANYWAY.takeIf { asksAgain }
        )
    }

    private fun startPause(seconds: Int) {
        // At once, also when the send ended on another thread: the button must not take another press meanwhile.
        show(seconds)
        scope.launch {
            for (left in seconds downTo 1) {
                show(left)
                delay(1_000)
            }
            flood.endPause()
            show(null)
        }
    }

    private fun seconds(): Double = now().toEpochMilli() / 1_000.0

    private companion object {
        /** The pause after the server's 429 without `Retry-After`. */
        const val SERVER_PAUSE_SECONDS = 30

        const val SENSITIVE_ADVICE =
            "Mildcrowd fragt dich nie im Chat danach – sei vorsichtig, besonders bei Menschen, die du noch nicht kennst. Zum Senden tippe noch einmal auf „Senden“."
    }
}

/** The composer during a pause: „Kurz durchatmen …“ with the seconds [left] on the send button; null: the pause is over. */
internal fun ConversationState.pausing(left: Int?): ConversationState = if (left != null) {
    copy(paused = true, hint = ChatSpam.FLOOD_PAUSE, sendLabel = ChatSpam.countdown(left))
} else {
    copy(paused = false, sendLabel = null, hint = null)
}
