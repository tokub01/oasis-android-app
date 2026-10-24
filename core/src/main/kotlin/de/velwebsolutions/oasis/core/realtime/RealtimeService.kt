package de.velwebsolutions.oasis.core.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One event received on a realtime channel (Laravel Reverb, Pusher protocol). */
data class RealtimeEvent(
    /** The broadcast name, e.g. `message.sent` (an event's `broadcastAs()`) or the class name. */
    val name: String,
    /** The event payload (`broadcastWith()`). */
    val data: JsonElement = JsonNull
) {
    /** A string field of the payload (numbers as text). */
    fun string(key: String): String? = ((data as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

/**
 * Live updates over Reverb. Private channels are the same as on the web (routes/channels.php): `private-inbox.{userId}`
 * and `private-conversation.{conversationId}`; authorisation uses the API token. Implemented in `:realtime`;
 * [NoRealtime] is used in tests and when Reverb is unreachable (screens then poll).
 */
interface RealtimeService {
    /** Whether the socket is currently connected (screens may poll more often while false). */
    val isConnected: StateFlow<Boolean>

    /** The events of a channel while collected; the channel is left when the last collector stops. */
    fun subscribe(channel: String): Flow<RealtimeEvent>
}

/** A realtime service that never delivers events. */
class NoRealtime : RealtimeService {
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)

    override fun subscribe(channel: String): Flow<RealtimeEvent> = emptyFlow()
}
