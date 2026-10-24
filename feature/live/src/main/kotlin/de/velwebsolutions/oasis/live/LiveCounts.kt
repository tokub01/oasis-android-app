package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import de.velwebsolutions.oasis.live.api.LiveCountsData
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The in-app notice about a new chat message; [id] tells two notices with the same text apart. */
data class ChatToast(val text: String, val conversationId: Int?, val id: Long = nextToastId.incrementAndGet())

private val nextToastId = AtomicLong()

/**
 * The numbers of the app shell – unread Hinweise at the bell, chats with unread messages at "Chats" – kept fresh like
 * the web's `inbox-live` module: live on the member's channel `private-inbox.{id}` (InboxActivity), when the app comes
 * to the foreground, after reading hints, and every 30 seconds as a fallback. A new chat message also shows a short
 * in-app notice with the sender's name ("Neue Nachricht von …"), except while the chats are open.
 */
class LiveCounts(
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 30.seconds,
    private val toastDuration: Duration = 15.seconds
) {
    private val mutableHints = MutableStateFlow(0)
    private val mutableChats = MutableStateFlow(0)
    private val mutableToast = MutableStateFlow<ChatToast?>(null)

    val hints: StateFlow<Int> = mutableHints.asStateFlow()
    val chats: StateFlow<Int> = mutableChats.asStateFlow()

    /** The in-app notice about a new chat message; cleared after 15 seconds or by the member. */
    val toast: StateFlow<ChatToast?> = mutableToast.asStateFlow()

    /** Whether the chat list is on screen (no notice then, like the web on the chat page). */
    @Volatile var isShowingChats = false

    @Volatile private var load: (suspend () -> LiveCountsData)? = null
    private var subscription: Job? = null
    private var pollJob: Job? = null
    private var toastJob: Job? = null

    /** After sign-in: load the numbers, listen on the member's inbox channel and poll as a fallback. */
    fun start(load: suspend () -> LiveCountsData, realtime: RealtimeService, userId: Int) {
        stop()
        this.load = load
        subscription = scope.launch { realtime.subscribe("private-inbox.$userId").collect(::handle) }
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(pollInterval)
            }
        }
    }

    /** On sign-out: forget everything. */
    fun stop() {
        subscription?.cancel()
        subscription = null
        pollJob?.cancel()
        pollJob = null
        toastJob?.cancel()
        toastJob = null
        load = null
        mutableHints.value = 0
        mutableChats.value = 0
        mutableToast.value = null
    }

    /** Ask the server again (foreground, after reading hints, after an InboxActivity). */
    suspend fun refresh() {
        val current = load ?: return
        val counts = try {
            current()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        if (load != null) apply(counts)
    }

    internal fun apply(counts: LiveCountsData) {
        mutableHints.value = counts.hints
        mutableChats.value = counts.chats
    }

    /** InboxActivity `{kind: chat|hint, conversation_id, from}`: refresh, and for a chat message show the notice. */
    fun handle(event: RealtimeEvent) {
        if (event.name != "InboxActivity") return
        if (event.string("kind") == "chat" && !isShowingChats) {
            val from = event.string("from")
            showToast(
                ChatToast(
                    from?.let {
                        "Neue Nachricht von $it"
                    } ?: "Neue Chat-Nachricht",
                    event.string("conversation_id")?.toIntOrNull()
                )
            )
        }
        scope.launch { refresh() }
    }

    /** The member closed the notice or opened the chat. */
    fun dismissToast() {
        toastJob?.cancel()
        mutableToast.value = null
    }

    internal fun showToast(toast: ChatToast) {
        mutableToast.value = toast
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(toastDuration)
            if (mutableToast.value?.id == toast.id) mutableToast.value = null
        }
    }
}
