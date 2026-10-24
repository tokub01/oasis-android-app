package de.velwebsolutions.oasis.realtime

import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Where the Reverb server is (Laravel `GET /api/v1/app-config` → `reverb`): the same public values the web reads from
 * `VITE_REVERB_*`.
 */
@Serializable
data class ReverbSettings(val key: String, val host: String, val port: Int, val scheme: String) {
    /** `ws(s)://host:port/app/{key}?protocol=7…` – the Pusher protocol endpoint of Reverb. */
    val url: String
        get() = "${if (scheme == "https") "wss" else "ws"}://$host:$port/app/$key" +
            "?protocol=7&client=oasis-android&version=1.0&flash=false"

    /** The same server under another host name (the emulator reaches the Mac as `10.0.2.2`, not by its Bonjour name). */
    fun withHost(override: String?): ReverbSettings = if (override.isNullOrBlank()) this else copy(host = override)
}

/**
 * Live updates over Laravel Reverb: a Pusher-protocol (version 7) client on a WebSocket (ReverbRealtime on iOS).
 *
 * - With [connectsWithSubscribers] (the app) the socket opens with the first collector of [subscribe] and closes after
 *   the last one stopped; otherwise [connect] and [disconnect] control it (the tests, like iOS).
 * - Private channels (`private-inbox.{userId}`, `private-conversation.{id}`) are authorised through the API
 *   (`POST /api/v1/broadcasting/auth`, the web's channel rules) with the socket id of the current connection.
 * - Keeps the connection alive with `pusher:ping` after the server's activity timeout and reconnects when no pong
 *   arrives, when the socket fails or the server closes it – with backoff (1, 2, 4, 8, 16, 30 s) – and subscribes
 *   every channel again. Pusher error codes 4000–4099 mean "do not reconnect" (e.g. an unknown app key), 4100–4199
 *   reconnect after the backoff, 4200–4299 at once.
 * - Event names are passed on as broadcast (`broadcastAs()`, e.g. `InboxActivity`, `MessageSent`); the payload, which
 *   Pusher sends as a JSON string, is decoded into a [JsonElement].
 */
class ReverbRealtime(
    /** Reads the Reverb settings; null means realtime is off on this server (screens then poll). */
    private val settings: suspend () -> ReverbSettings?,
    /** Returns the `auth` string for a private channel and socket id. */
    private val authorize: suspend (socketId: String, channel: String) -> String,
    private val makeSocket: WebSocketFactory,
    /** Waits; injected so tests control time. */
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    private val pongTimeout: Duration = 30.seconds,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val connectsWithSubscribers: Boolean = true
) : RealtimeService {
    private val lock = Any()
    private val mutableConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    private val handlers = mutableMapOf<String, MutableMap<Long, (RealtimeEvent) -> Unit>>()
    private val nextHandlerId = AtomicLong()
    private val subscribed = mutableSetOf<String>()
    private var socket: WebSocketConnection? = null

    /** The socket id of the current connection (set by `pusher:connection_established`). */
    private var socketId: String? = null
    private var runJob: Job? = null
    private var pingJob: Job? = null
    private var failures = 0
    private var activityTimeout: Duration = 120.seconds

    @Volatile private var awaitingPong = false

    /** The channels the server confirmed (for tests and diagnostics). */
    val confirmedChannels: Set<String>
        get() = synchronized(lock) { subscribed.toSet() }

    /** Starts connecting (and keeps reconnecting) until [disconnect]. Calling it while running does nothing. */
    fun connect() {
        synchronized(lock) {
            if (runJob != null) return
            failures = 0
            runJob = scope.launch { run() }
        }
    }

    /** Reconnects at once if the connection is down (the app returns to the foreground), skipping the backoff. */
    fun reconnectIfNeeded() {
        val running = synchronized(lock) { runJob != null || (connectsWithSubscribers && handlers.isNotEmpty()) }
        if (!running || mutableConnected.value) return
        disconnect()
        connect()
    }

    /** Closes the connection and stops reconnecting. Subscriptions stay registered and are sent again on [connect]. */
    fun disconnect() {
        synchronized(lock) {
            runJob?.cancel()
            runJob = null
        }
        closeSocket()
    }

    override fun subscribe(channel: String): Flow<RealtimeEvent> = callbackFlow {
        val id = addHandler(channel) { trySend(it) }
        awaitClose { removeHandler(channel, id) }
    }

    // Connection

    private fun closeSocket() {
        val closing = synchronized(lock) {
            pingJob?.cancel()
            pingJob = null
            val current = socket
            socket = null
            socketId = null
            subscribed.clear()
            awaitingPong = false
            current
        }
        mutableConnected.value = false
        closing?.close()
    }

    private suspend fun run() {
        // Ends by returning (no Reverb, fatal error) or by cancellation, which every suspension point checks.
        while (true) {
            var reconnectAtOnce = false
            try {
                val current = settings()
                if (current == null) {
                    synchronized(lock) { runJob = null }
                    return
                }
                currentCoroutineContext().ensureActive()
                val socket = makeSocket(current.url)
                synchronized(lock) { this.socket = socket }
                listen(socket)
            } catch (cancelled: CancellationException) {
                closeSocket()
                throw cancelled
            } catch (error: PusherError) {
                if (error.isFatal) {
                    closeSocket()
                    synchronized(lock) { runJob = null }
                    return
                }
                reconnectAtOnce = error.reconnectsAtOnce
            } catch (_: Exception) {
                // Closed or failed: fall through to the backoff.
            }
            closeSocket()
            currentCoroutineContext().ensureActive()
            if (reconnectAtOnce) continue
            val delay = synchronized(lock) { BACKOFF[minOf(failures, BACKOFF.lastIndex)].also { failures += 1 } }
            sleep(delay)
        }
    }

    /** Reads frames until the socket fails; once connected, a ping loop runs alongside. */
    private suspend fun listen(socket: WebSocketConnection): Nothing {
        try {
            while (true) {
                val text = socket.receive()
                awaitingPong = false
                handle(text, socket)
            }
        } finally {
            synchronized(lock) {
                pingJob?.cancel()
                pingJob = null
            }
        }
    }

    /**
     * After each activity timeout send `pusher:ping`; without any answer within the pong timeout the socket is closed,
     * which ends [listen] and reconnects.
     */
    private suspend fun keepAlive(socket: WebSocketConnection) {
        // Runs as [pingJob], which is cancelled before the socket is replaced or closed.
        while (true) {
            sleep(synchronized(lock) { activityTimeout })
            awaitingPong = true
            runCatching { socket.send(frame("pusher:ping", JsonObject(emptyMap()))) }
            sleep(pongTimeout)
            if (awaitingPong) {
                socket.close()
                return
            }
        }
    }

    private suspend fun handle(text: String, socket: WebSocketConnection) {
        val message = PusherMessage.parse(text) ?: return
        when (message.event) {
            "pusher:connection_established" -> {
                val payload = message.payload as? JsonObject ?: return
                val id = (payload["socket_id"] as? JsonPrimitive)?.contentOrNull ?: return
                val channels = synchronized(lock) {
                    socketId = id
                    (payload["activity_timeout"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let { activityTimeout = it.seconds }
                    failures = 0
                    pingJob?.cancel()
                    pingJob = scope.launch { keepAlive(socket) }
                    handlers.keys.toList()
                }
                mutableConnected.value = true
                channels.forEach { channel -> scope.launch { subscribeOnServer(channel, socket, id) } }
            }

            "pusher:ping" -> socket.send(frame("pusher:pong", JsonObject(emptyMap())))

            "pusher:pong" -> Unit

            "pusher:error" -> {
                val code = ((message.payload as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull ?: 0
                if (code in 4000 until 4300) throw PusherError(code)
            }

            "pusher_internal:subscription_succeeded" -> message.channel?.let { synchronized(lock) { subscribed += it } }

            else -> {
                val channel = message.channel ?: return
                val receivers = synchronized(lock) { handlers[channel].orEmpty().values.toList() }
                val event = RealtimeEvent(message.event, message.payload)
                receivers.forEach { it(event) }
            }
        }
    }

    // Channels

    private fun addHandler(channel: String, handler: (RealtimeEvent) -> Unit): Long {
        val id = nextHandlerId.incrementAndGet()
        val target = synchronized(lock) {
            // A channel stays registered exactly while it has handlers.
            val isNew = channel !in handlers
            handlers.getOrPut(channel) { mutableMapOf() }[id] = handler
            val current = socket
            val currentId = socketId
            // A socket id means the connection is established; until then the connection subscribes everything itself.
            if (isNew && current != null && currentId != null) current to currentId else null
        }
        if (target != null) scope.launch { subscribeOnServer(channel, target.first, target.second) }
        if (connectsWithSubscribers) connect()
        return id
    }

    private fun removeHandler(channel: String, id: Long) {
        val (unsubscribeOn, nobodyLeft) = synchronized(lock) {
            // Every handler id is removed once, so its channel is still registered.
            val channelHandlers = handlers.getValue(channel)
            channelHandlers.remove(id)
            var target: WebSocketConnection? = null
            if (channelHandlers.isEmpty()) {
                handlers.remove(channel)
                subscribed.remove(channel)
                if (mutableConnected.value) target = socket
            }
            target to handlers.isEmpty()
        }
        if (connectsWithSubscribers && nobodyLeft) {
            disconnect()
        } else if (unsubscribeOn != null) {
            val frame = frame("pusher:unsubscribe", JsonObject(mapOf("channel" to JsonPrimitive(channel))))
            scope.launch { runCatching { unsubscribeOn.send(frame) } }
        }
    }

    private suspend fun subscribeOnServer(channel: String, socket: WebSocketConnection, socketId: String) {
        val data = mutableMapOf<String, JsonElement>("channel" to JsonPrimitive(channel))
        if (channel.startsWith("private-") || channel.startsWith("presence-")) {
            val auth = try {
                authorize(socketId, channel)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return
            }
            data["auth"] = JsonPrimitive(auth)
        }
        // The connection may have changed while the auth request ran.
        val current = synchronized(lock) {
            this.socket === socket && this.socketId == socketId && channel in handlers
        }
        if (!current) return
        runCatching { socket.send(frame("pusher:subscribe", JsonObject(data))) }
    }

    companion object {
        /** Waits after the n-th failed attempt in a row (the last value repeats). */
        private val BACKOFF: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds)

        internal fun frame(event: String, data: JsonElement): String =
            JsonObject(mapOf("event" to JsonPrimitive(event), "data" to data)).toString()
    }
}

/** A Pusher protocol error: 4000–4099 must not reconnect, 4100–4199 reconnect after a backoff, 4200–4299 at once. */
internal class PusherError(private val code: Int) : Exception("Pusher error $code") {
    val isFatal: Boolean get() = code in 4000 until 4100
    val reconnectsAtOnce: Boolean get() = code in 4200 until 4300
}

/** One frame from the server. `data` is usually a JSON string (Pusher encodes payloads twice); [payload] decodes it. */
internal data class PusherMessage(val event: String, val channel: String?, val payload: JsonElement) {
    companion object {
        fun parse(text: String): PusherMessage? {
            val root = runCatching { RealtimeJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val event = (root["event"] as? JsonPrimitive)?.contentOrNull ?: return null
            val channel = (root["channel"] as? JsonPrimitive)?.contentOrNull
            val data = root["data"] ?: JsonNull
            val payload = if (data is JsonPrimitive && data.isString) {
                runCatching { RealtimeJson.parseToJsonElement(data.content) }.getOrDefault(data)
            } else {
                data
            }
            return PusherMessage(event, channel, payload)
        }
    }
}

private val RealtimeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

@Serializable
internal data class AppConfigAnswer(val reverb: ReverbSettings? = null)

@Serializable
internal data class BroadcastAuthBody(val socketId: String, val channelName: String)

@Serializable
internal data class BroadcastAuthAnswer(val auth: String)

internal interface ReverbApiService {
    @GET("app-config")
    suspend fun appConfig(): DataEnvelope<AppConfigAnswer>

    @POST("broadcasting/auth")
    suspend fun authorize(@Body body: BroadcastAuthBody): BroadcastAuthAnswer
}

/** The two API calls the realtime client needs (Laravel routes/api/v1/members/live.php). */
class RealtimeApi(client: ApiClient) {
    private val service = client.create<ReverbApiService>()

    /** `GET app-config` → `reverb` (null while the server has no Reverb). */
    suspend fun settings(): ReverbSettings? = service.appConfig().data.reverb

    /** `POST broadcasting/auth` → the `auth` string for `pusher:subscribe`. */
    suspend fun authorize(socketId: String, channel: String): String = service.authorize(BroadcastAuthBody(socketId, channel)).auth
}
