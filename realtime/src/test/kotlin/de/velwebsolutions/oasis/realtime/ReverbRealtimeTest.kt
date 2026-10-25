package de.velwebsolutions.oasis.realtime

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.testing.StubTransport
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plays the Reverb server: frames the client sends are recorded, frames the test pushes are received by the client. */
class FakeWebSocket(val url: String) : WebSocketConnection {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val sentFrames = CopyOnWriteArrayList<String>()

    @Volatile var isClosed = false
        private set

    val sent: List<String> get() = sentFrames.toList()

    /** Sent frames decoded as JSON. */
    val sentMessages: List<JsonObject> get() = sent.map { Json.parseToJsonElement(it).jsonObject }

    fun sentEvents(event: String): List<JsonObject> = sentMessages.filter { it["event"]?.jsonPrimitive?.content == event }

    /** The server stops taking frames (a half-open connection). */
    @Volatile var refusesFrames = false

    override suspend fun send(text: String) {
        if (isClosed || refusesFrames) throw IOException("closed")
        sentFrames += text
    }

    override suspend fun receive(): String = inbox.receive()

    override fun close() {
        isClosed = true
        inbox.close(IOException("closed"))
    }

    /** The server sends a frame. */
    fun push(text: String) {
        inbox.trySend(text)
    }

    fun established(socketId: String = "123.456", activityTimeout: Int = 30) {
        push("""{"event":"pusher:connection_established","data":"{\"socket_id\":\"$socketId\",\"activity_timeout\":$activityTimeout}"}""")
    }
}

/** Hands out a new FakeWebSocket per connection attempt. */
class SocketFactory {
    val sockets = CopyOnWriteArrayList<FakeWebSocket>()

    fun make(url: String): WebSocketConnection = FakeWebSocket(url).also { sockets += it }
}

/** Time under the test's control: every sleep waits until [advance]. */
class ManualClock {
    private class Waiter(val duration: Duration, val wake: CompletableDeferred<Unit>)

    private val waiters = CopyOnWriteArrayList<Waiter>()
    val requested = CopyOnWriteArrayList<Duration>()
    val pending: Int get() = waiters.size

    suspend fun sleep(duration: Duration) {
        val waiter = Waiter(duration, CompletableDeferred())
        requested += duration
        waiters += waiter
        try {
            waiter.wake.await()
        } finally {
            waiters.remove(waiter)
        }
    }

    /** Wakes every sleeper waiting for [duration] (or all when null). */
    fun advance(duration: Duration? = null) {
        waiters.filter { duration == null || it.duration == duration }.forEach {
            waiters.remove(it)
            it.wake.complete(Unit)
        }
    }
}

/** Waits (up to two seconds) until the condition holds. */
fun eventually(message: String = "condition", condition: () -> Boolean) {
    repeat(400) {
        if (condition()) return
        Thread.sleep(5)
    }
    assertTrue(message, condition())
}

class ReverbRealtimeTest {
    private val settings = ReverbSettings(key = "oasis-local-key", host = "localhost", port = 8080, scheme = "http")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        collectors.cancel()
        scope.cancel()
    }

    private fun client(
        factory: SocketFactory,
        clock: ManualClock,
        settings: ReverbSettings? = this.settings,
        authorize: suspend (String, String) -> String = { socket, channel -> "key:$socket:$channel" },
        connectsWithSubscribers: Boolean = false
    ) = ReverbRealtime(
        settings = { settings },
        authorize = authorize,
        makeSocket = factory::make,
        sleep = clock::sleep,
        pongTimeout = 30.seconds,
        scope = scope,
        connectsWithSubscribers = connectsWithSubscribers
    )

    private fun ReverbRealtime.listen(channel: String, received: MutableList<RealtimeEvent> = CopyOnWriteArrayList()): Job =
        subscribe(channel).onEach { received += it }.launchIn(collectors)

    @Test
    fun buildsThePusherUrlOfReverb() {
        assertEquals("ws://localhost:8080/app/oasis-local-key?protocol=7&client=oasis-android&version=1.0&flash=false", settings.url)
        assertTrue(ReverbSettings("k", "ws.oasis.de", 443, "https").url.startsWith("wss://ws.oasis.de:443/app/k?"))
        assertEquals(
            "ws://10.0.2.2:8080/app/oasis-local-key?protocol=7&client=oasis-android&version=1.0&flash=false",
            settings.withHost("10.0.2.2").url
        )
        assertEquals(settings, settings.withHost(null))
    }

    @Test
    fun subscribesPrivateChannelsWithAuthAndDeliversDecodedEvents() {
        val factory = SocketFactory()
        val realtime = client(factory, ManualClock())
        val received = CopyOnWriteArrayList<RealtimeEvent>()
        val job = realtime.listen("private-inbox.5", received)
        Thread.sleep(20)

        realtime.connect()
        eventually { factory.sockets.size == 1 }
        val socket = factory.sockets[0]
        assertEquals(settings.url, socket.url)
        assertFalse(realtime.isConnected.value)

        socket.established()
        eventually { realtime.isConnected.value }
        eventually { socket.sentEvents("pusher:subscribe").isNotEmpty() }
        assertEquals(
            JsonObject(mapOf("channel" to JsonPrimitive("private-inbox.5"), "auth" to JsonPrimitive("key:123.456:private-inbox.5"))),
            socket.sentEvents("pusher:subscribe").first()["data"]
        )

        socket.push("""{"event":"pusher_internal:subscription_succeeded","channel":"private-inbox.5","data":"{}"}""")
        socket.push(
            """{"event":"InboxActivity","channel":"private-inbox.5","data":"{\"kind\":\"chat\",\"conversation_id\":\"7\",\"from\":\"Jonas\"}"}"""
        )
        socket.push("""{"event":"InboxActivity","channel":"private-inbox.6","data":"{\"kind\":\"hint\"}"}""")
        eventually { received.size == 1 }
        val event = received.first()
        assertEquals("InboxActivity", event.name)
        assertEquals("chat", event.string("kind"))
        assertEquals("7", event.string("conversation_id"))
        assertEquals("Jonas", event.string("from"))
        eventually { realtime.confirmedChannels == setOf("private-inbox.5") }
        Thread.sleep(20)
        assertEquals(1, received.size)
        job.cancel()
        realtime.disconnect()
    }

    @Test
    fun subscribesPublicChannelsWithoutAuthAndChannelsAddedLater() {
        val factory = SocketFactory()
        var authorised = false
        val realtime = client(factory, ManualClock(), authorize = { _, _ ->
            authorised = true
            ""
        })
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        factory.sockets[0].established()
        eventually { realtime.isConnected.value }

        val job = realtime.listen("status")
        eventually { factory.sockets[0].sentEvents("pusher:subscribe").isNotEmpty() }
        assertEquals(
            JsonObject(mapOf("channel" to JsonPrimitive("status"))),
            factory.sockets[0].sentEvents("pusher:subscribe").last()["data"]
        )
        assertFalse("public channels need no auth", authorised)
        job.cancel()
        realtime.disconnect()
    }

    @Test
    fun doesNotSubscribeWhenTheServerRefusesTheChannel() {
        val factory = SocketFactory()
        val realtime = client(factory, ManualClock(), authorize = { _, _ -> throw ApiError.Forbidden("") })
        val job = realtime.listen("private-inbox.9")
        Thread.sleep(20)
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        factory.sockets[0].established()
        eventually { realtime.isConnected.value }
        Thread.sleep(50)
        assertTrue(factory.sockets[0].sentEvents("pusher:subscribe").isEmpty())
        job.cancel()
        realtime.disconnect()
    }

    @Test
    fun answersServerPingsAndPingsAfterTheActivityTimeout() {
        val factory = SocketFactory()
        val clock = ManualClock()
        val realtime = client(factory, clock)
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        val socket = factory.sockets[0]
        socket.established(activityTimeout = 30)
        eventually { realtime.isConnected.value }

        socket.push("""{"event":"pusher:ping","data":"{}"}""")
        eventually { socket.sentEvents("pusher:pong").isNotEmpty() }

        eventually { clock.pending == 1 }
        assertEquals("the server's activity timeout", listOf(30.seconds), clock.requested.toList())
        clock.advance()
        eventually { socket.sentEvents("pusher:ping").isNotEmpty() }
        socket.push("""{"event":"pusher:pong","data":"{}"}""")
        eventually { clock.pending == 1 }
        Thread.sleep(20)
        clock.advance(30.seconds)
        Thread.sleep(30)
        assertFalse("a pong keeps the connection", socket.isClosed)
        realtime.disconnect()
    }

    @Test
    fun reconnectsWithBackoffWhenNoPongArrivesAndSubscribesAgain() {
        val factory = SocketFactory()
        val clock = ManualClock()
        val realtime = client(factory, clock)
        val job = realtime.listen("private-inbox.5")
        Thread.sleep(20)
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        val first = factory.sockets[0]
        first.established()
        eventually { realtime.isConnected.value }

        eventually { clock.pending == 1 }
        clock.advance() // activity timeout → ping
        eventually { first.sentEvents("pusher:ping").isNotEmpty() }
        eventually { clock.pending == 1 }
        clock.advance() // pong timeout without pong → close
        eventually { first.isClosed }
        eventually { !realtime.isConnected.value }

        eventually("first retry after one second") { clock.requested.lastOrNull() == 1.seconds && clock.pending == 1 }
        clock.advance(1.seconds)
        eventually { factory.sockets.size == 2 }
        val second = factory.sockets[1]
        second.established(socketId = "999.1")
        eventually { realtime.isConnected.value }
        eventually { second.sentEvents("pusher:subscribe").isNotEmpty() }
        assertEquals(
            "key:999.1:private-inbox.5",
            second.sentEvents("pusher:subscribe").first()["data"]!!.jsonObject["auth"]!!.jsonPrimitive.content
        )
        job.cancel()
        realtime.disconnect()
    }

    @Test
    fun backsOffLongerAfterRepeatedFailuresAndResetsAfterSuccess() {
        val factory = SocketFactory()
        val clock = ManualClock()
        val realtime = client(factory, clock)
        realtime.connect()
        for (attempt in 1..3) {
            eventually { factory.sockets.size == attempt }
            factory.sockets[attempt - 1].close()
            eventually { clock.pending == 1 }
            clock.advance()
        }
        assertEquals(listOf(1.seconds, 2.seconds, 4.seconds), clock.requested.toList())
        eventually { factory.sockets.size == 4 }
        factory.sockets[3].established()
        eventually { realtime.isConnected.value }
        factory.sockets[3].close()
        eventually("backoff starts again after a connection") { clock.requested.size >= 5 && clock.requested.last() == 1.seconds }
        realtime.disconnect()
    }

    @Test
    fun stopsOnFatalPusherErrorsAndWithoutReverb() {
        val factory = SocketFactory()
        val clock = ManualClock()
        val realtime = client(factory, clock)
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        factory.sockets[0].push("""{"event":"pusher:error","data":{"code":4001,"message":"Application does not exist"}}""")
        eventually { factory.sockets[0].isClosed }
        Thread.sleep(30)
        assertEquals(0, clock.pending)
        assertEquals(1, factory.sockets.size)

        val offline = SocketFactory()
        val withoutReverb = client(offline, clock, settings = null)
        withoutReverb.connect()
        Thread.sleep(30)
        assertTrue(offline.sockets.isEmpty())
        assertFalse(withoutReverb.isConnected.value)
    }

    @Test
    fun reconnectsAtOnceOn42xxAndAfterTheBackoffOn41xx() {
        val factory = SocketFactory()
        val clock = ManualClock()
        val realtime = client(factory, clock)
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        factory.sockets[0].push("""{"event":"pusher:error","data":{"code":4200,"message":"Generic reconnect immediately"}}""")
        eventually("4200–4299: a new socket without waiting") { factory.sockets.size == 2 }
        assertTrue(clock.requested.isEmpty())

        factory.sockets[1].push("""{"event":"pusher:error","data":{"code":4100,"message":"Over capacity"}}""")
        eventually("4100–4199: the backoff first") { clock.pending == 1 }
        clock.advance()
        eventually { factory.sockets.size == 3 }
        assertEquals(listOf(1.seconds), clock.requested.toList())
        realtime.disconnect()
    }

    @Test
    fun unsubscribesWhenTheLastCollectorGoesAndDisconnectStops() {
        val factory = SocketFactory()
        val realtime = client(factory, ManualClock())
        val first = realtime.listen("private-conversation.3")
        val second = realtime.listen("private-conversation.3")
        Thread.sleep(20)
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        val socket = factory.sockets[0]
        socket.established()
        eventually { socket.sentEvents("pusher:subscribe").size == 1 }

        runBlocking { first.cancel() }
        Thread.sleep(20)
        assertTrue(socket.sentEvents("pusher:unsubscribe").isEmpty())
        second.cancel()
        eventually { socket.sentEvents("pusher:unsubscribe").isNotEmpty() }

        realtime.disconnect()
        assertTrue(socket.isClosed)
        assertFalse(realtime.isConnected.value)
        Thread.sleep(20)
        assertEquals(1, factory.sockets.size)
    }

    @Test
    fun connectsWithTheFirstCollectorAndClosesAfterTheLast() {
        val factory = SocketFactory()
        val realtime = client(factory, ManualClock(), connectsWithSubscribers = true)
        val job = realtime.listen("private-inbox.5")
        eventually { factory.sockets.size == 1 }
        val socket = factory.sockets[0]
        socket.established()
        eventually { socket.sentEvents("pusher:subscribe").isNotEmpty() }

        job.cancel()
        eventually("the socket closes without listeners") { socket.isClosed }
        assertFalse(realtime.isConnected.value)

        val again = realtime.listen("private-inbox.5")
        eventually("a new listener opens it again") { factory.sockets.size == 2 }
        again.cancel()
    }

    @Test
    fun reconnectsAtOnceInTheForeground() {
        val factory = SocketFactory()
        val clock = ManualClock()
        val realtime = client(factory, clock, connectsWithSubscribers = true)
        val job = realtime.listen("private-inbox.5")
        eventually { factory.sockets.size == 1 }
        factory.sockets[0].close()
        eventually { clock.pending == 1 }

        realtime.reconnectIfNeeded()
        eventually("no waiting for the backoff") { factory.sockets.size == 2 }
        factory.sockets[1].established()
        eventually { realtime.isConnected.value }
        realtime.reconnectIfNeeded()
        Thread.sleep(20)
        assertEquals("a live connection stays", 2, factory.sockets.size)
        job.cancel()
    }

    @Test
    fun readsSettingsAndAuthorisesThroughTheApi() = runBlocking {
        val transport = StubTransport(
            StubTransport.Stub(
                "GET",
                "app-config",
                json = """{"data":{"reverb":{"key":"oasis-local-key","host":"localhost","port":8080,"scheme":"http"},""" +
                    """"push":{"available":false}}}"""
            ),
            StubTransport.Stub("POST", "broadcasting/auth", json = """{"auth":"oasis-local-key:abc"}""")
        )
        val api = RealtimeApi(transport.client)
        assertEquals(settings, api.settings())
        assertEquals("oasis-local-key:abc", api.authorize("1.2", "private-inbox.5"))
        assertEquals(
            JsonObject(mapOf("socket_id" to JsonPrimitive("1.2"), "channel_name" to JsonPrimitive("private-inbox.5"))),
            transport.lastBody("broadcasting/auth")
        )
    }

    @Test
    fun readsNoSettingsWhileTheServerHasNoReverb() = runBlocking {
        val transport =
            StubTransport(StubTransport.Stub("GET", "app-config", json = """{"data":{"reverb":null,"push":{"available":false}}}"""))
        assertEquals(null, RealtimeApi(transport.client).settings())
    }

    @Test
    fun ignoresFramesItCannotUseAndKeepsTheDefaultActivityTimeout() {
        val factory = SocketFactory()
        val clock = ManualClock()
        val realtime = client(factory, clock)
        val received = CopyOnWriteArrayList<RealtimeEvent>()
        val job = realtime.listen("status", received)
        Thread.sleep(20)
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        val socket = factory.sockets[0]
        listOf(
            "no json",
            """{"event":"pusher:connection_established","data":"[]"}""",
            """{"event":"pusher:connection_established","data":"{}"}""",
            """{"event":"pusher:connection_established","data":"{\"socket_id\":null}"}""",
            """{"event":"pusher:error"}""",
            """{"event":"pusher:error","data":{"code":"x"}}""",
            """{"event":"pusher:error","data":{"code":1006}}""",
            """{"event":"pusher:error","data":{"code":4300}}""",
            """{"event":"pusher_internal:subscription_succeeded","data":"{}"}""",
            """{"event":"InboxActivity","data":"{}"}""",
            """{"event":"InboxActivity","channel":"nobody-listens","data":"{}"}"""
        ).forEach(socket::push)
        Thread.sleep(50)
        assertFalse("nothing established the connection", realtime.isConnected.value)
        assertFalse(socket.isClosed)
        assertTrue(received.isEmpty())

        // An activity timeout that is no positive number keeps the protocol's 120 seconds.
        socket.push("""{"event":"pusher:connection_established","data":"{\"socket_id\":\"1.1\",\"activity_timeout\":\"soon\"}"}""")
        eventually { realtime.isConnected.value }
        eventually { clock.pending == 1 }
        assertEquals(listOf(120.seconds), clock.requested.toList())
        listOf("""\"activity_timeout\":0""", """\"activity_timeout\":{}""", """\"other\":1""").forEachIndexed { index, timeout ->
            socket.push("""{"event":"pusher:connection_established","data":"{\"socket_id\":\"1.$index\",$timeout}"}""")
            eventually { clock.requested.size == index + 2 }
        }
        assertEquals(List(4) { 120.seconds }, clock.requested.toList())
        socket.push("""{"event":"Status","channel":"status","data":"open"}""")
        eventually { received.size == 1 }
        assertEquals("open", (received.first().data as JsonPrimitive).content)
        job.cancel()
        realtime.disconnect()
    }

    @Test
    fun connectsOnceAndStopsCleanlyWithoutAConnection() {
        val factory = SocketFactory()
        val realtime = client(factory, ManualClock())
        realtime.disconnect()
        realtime.reconnectIfNeeded()
        Thread.sleep(20)
        assertTrue("nothing to reconnect before connect()", factory.sockets.isEmpty())
        realtime.connect()
        realtime.connect()
        Thread.sleep(30)
        assertEquals(1, factory.sockets.size)
        realtime.reconnectIfNeeded()
        eventually("a pending connection is started again") { factory.sockets.size == 2 }
        realtime.disconnect()

        val withSubscribers = client(SocketFactory(), ManualClock(), connectsWithSubscribers = true)
        withSubscribers.reconnectIfNeeded()
        Thread.sleep(20)
        assertFalse(withSubscribers.isConnected.value)
    }

    @Test
    fun subscribesPresenceChannelsWithAuthAndWaitsForTheConnection() {
        val factory = SocketFactory()
        val realtime = client(factory, ManualClock())
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        val socket = factory.sockets[0]
        // Open but not established: the subscription goes out with the connection.
        val job = realtime.listen("presence-room.1")
        Thread.sleep(30)
        assertTrue(socket.sentEvents("pusher:subscribe").isEmpty())
        socket.established()
        eventually { socket.sentEvents("pusher:subscribe").size == 1 }
        assertEquals(
            "key:123.456:presence-room.1",
            socket.sentEvents("pusher:subscribe").first()["data"]!!.jsonObject["auth"]!!.jsonPrimitive.content
        )
        job.cancel()
        realtime.disconnect()
    }

    @Test
    fun dropsASubscriptionWhoseAuthArrivesTooLate() {
        val factory = SocketFactory()
        val gate = CompletableDeferred<Unit>()
        val asked = CopyOnWriteArrayList<String>()
        val realtime = client(factory, ManualClock(), authorize = { socket, channel ->
            asked += "$socket:$channel"
            gate.await()
            "late"
        })
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        val socket = factory.sockets[0]
        socket.established()
        eventually { realtime.isConnected.value }

        // The listener leaves while its channel is being authorised.
        val gone = realtime.listen("private-inbox.1")
        eventually { asked.size == 1 }
        gone.cancel()
        Thread.sleep(20)

        // The connection changes while another channel is being authorised.
        val stays = realtime.listen("private-inbox.2")
        eventually { asked.size == 2 }
        socket.established(socketId = "777.1")
        eventually { asked.size == 3 }
        gate.complete(Unit)
        eventually { socket.sentEvents("pusher:subscribe").size == 1 }
        Thread.sleep(30)
        assertEquals("only the current connection's channel is subscribed", 1, socket.sentEvents("pusher:subscribe").size)
        assertEquals(
            JsonPrimitive("private-inbox.2"),
            socket.sentEvents("pusher:subscribe").single()["data"]!!.jsonObject["channel"]
        )
        stays.cancel()
        realtime.disconnect()
    }

    @Test
    fun unsubscribesOneChannelWhileAnotherKeepsTheConnection() {
        val factory = SocketFactory()
        val realtime = client(factory, ManualClock(), connectsWithSubscribers = true)
        val inbox = realtime.listen("private-inbox.5")
        val chat = realtime.listen("private-conversation.3")
        eventually { factory.sockets.size == 1 }
        val socket = factory.sockets[0]
        socket.established()
        eventually { socket.sentEvents("pusher:subscribe").size == 2 }
        chat.cancel()
        eventually { socket.sentEvents("pusher:unsubscribe").size == 1 }
        assertFalse(socket.isClosed)
        inbox.cancel()
        eventually { socket.isClosed }
    }

    @Test
    fun unsubscribesWithoutAFrameWhileNotConnected() {
        val factory = SocketFactory()
        val realtime = client(factory, ManualClock())
        val job = realtime.listen("status")
        Thread.sleep(20)
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        job.cancel()
        Thread.sleep(30)
        assertTrue(factory.sockets[0].sentEvents("pusher:unsubscribe").isEmpty())
        realtime.disconnect()
    }

    @Test
    fun waitsWithTheRealClockByDefault() {
        val factory = SocketFactory()
        var attempts = 0
        val realtime = ReverbRealtime(
            settings = { settings },
            authorize = { _, _ -> "" },
            makeSocket = { url ->
                attempts += 1
                if (attempts == 1) throw IOException("offline")
                factory.make(url)
            },
            scope = scope,
            connectsWithSubscribers = false
        )
        realtime.connect()
        repeat(400) { if (factory.sockets.isEmpty()) Thread.sleep(10) }
        assertEquals("the second attempt after the one-second backoff", 1, factory.sockets.size)
        realtime.disconnect()
    }

    @Test
    fun dropsASubscriptionWhenTheSocketClosedDuringItsAuth() {
        val factory = SocketFactory()
        val clock = ManualClock()
        val gate = CompletableDeferred<Unit>()
        val realtime = client(factory, clock, authorize = { _, _ ->
            gate.await()
            "late"
        })
        val job = realtime.listen("private-inbox.1")
        Thread.sleep(20)
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        val socket = factory.sockets[0]
        socket.established()
        eventually { realtime.isConnected.value }
        socket.close()
        eventually { clock.requested.lastOrNull() == 1.seconds }
        gate.complete(Unit)
        Thread.sleep(30)
        assertTrue(socket.sentEvents("pusher:subscribe").isEmpty())
        job.cancel()
        realtime.disconnect()
    }

    @Test
    fun readsTheWireFormatOfSettingsAndChannelAuth() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(AppConfigAnswer(null), json.decodeFromString<AppConfigAnswer>("{}"))
        assertEquals("{}", json.encodeToString(AppConfigAnswer(null)))
        assertEquals(AppConfigAnswer(null), json.decodeFromString<AppConfigAnswer>("""{"reverb":null}"""))
        assertEquals("""{"reverb":null}""", Json { encodeDefaults = true }.encodeToString(AppConfigAnswer(null)))
        assertEquals(AppConfigAnswer(settings), json.decodeFromString<AppConfigAnswer>(json.encodeToString(AppConfigAnswer(settings))))
        val body = BroadcastAuthBody("1.2", "private-inbox.5")
        assertEquals(body, json.decodeFromString<BroadcastAuthBody>(json.encodeToString(body)))
        assertEquals(BroadcastAuthAnswer("k:1"), json.decodeFromString<BroadcastAuthAnswer>("""{"auth":"k:1"}"""))
        listOf<() -> Any>(
            { json.decodeFromString<ReverbSettings>("""{"key":"k"}""") },
            { json.decodeFromString<BroadcastAuthAnswer>("{}") },
            { json.decodeFromString<BroadcastAuthBody>("{}") }
        ).forEach { decode -> assertTrue(runCatching(decode).exceptionOrNull() is SerializationException) }
    }

    @Test
    fun keepsTheHostForAnEmptyOverride() {
        assertEquals(settings, settings.withHost(""))
        assertEquals(settings, settings.withHost("  "))
        assertEquals(
            listOf("oasis-local-key", "localhost", "8080", "http"),
            listOf(settings.key, settings.host, "${settings.port}", settings.scheme)
        )
        val body = BroadcastAuthBody("1.2", "private-inbox.5")
        assertEquals("1.2" to "private-inbox.5", body.socketId to body.channelName)
    }

    @Test
    fun runsOnItsOwnScopeAndOpensWithTheFirstListenerByDefault() {
        val factory = SocketFactory()
        val realtime = ReverbRealtime(settings = { settings }, authorize = { _, _ -> "" }, makeSocket = factory::make)
        val job = realtime.listen("status")
        eventually { factory.sockets.size == 1 }
        job.cancel()
        eventually { factory.sockets[0].isClosed }
    }

    @Test
    fun reconnectsListenersAfterAFatalErrorInTheForeground() {
        val factory = SocketFactory()
        val realtime = client(factory, ManualClock(), connectsWithSubscribers = true)
        val job = realtime.listen("private-inbox.5")
        eventually { factory.sockets.size == 1 }
        factory.sockets[0].push("""{"event":"pusher:error","data":{"code":4001}}""")
        eventually { factory.sockets[0].isClosed }
        Thread.sleep(20)
        realtime.reconnectIfNeeded()
        eventually("the listener gets a connection again") { factory.sockets.size == 2 }
        job.cancel()
    }

    @Test
    fun survivesFramesItCannotSend() {
        val factory = SocketFactory()
        val clock = ManualClock()
        val gate = CompletableDeferred<Unit>()
        val realtime = client(factory, clock, authorize = { _, _ ->
            gate.await()
            "auth"
        })
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        val socket = factory.sockets[0]
        socket.established()
        eventually { realtime.isConnected.value }
        val status = realtime.listen("status")
        eventually { socket.sentEvents("pusher:subscribe").size == 1 }
        val inbox = realtime.listen("private-inbox.1")
        Thread.sleep(20)
        socket.refusesFrames = true
        gate.complete(Unit)
        status.cancel()
        eventually { clock.pending == 1 }
        clock.advance() // the ping cannot be sent …
        eventually { clock.pending == 1 }
        clock.advance() // … so no pong comes and the socket is closed
        eventually { socket.isClosed }
        inbox.cancel()
        realtime.disconnect()
    }

    @Test
    fun aCancelledAuthorisationSubscribesNothing() {
        val factory = SocketFactory()
        val realtime = client(factory, ManualClock(), authorize = { _, _ -> throw kotlinx.coroutines.CancellationException("left") })
        val job = realtime.listen("private-inbox.1")
        Thread.sleep(20)
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        factory.sockets[0].established()
        eventually { realtime.isConnected.value }
        Thread.sleep(30)
        assertTrue(factory.sockets[0].sentEvents("pusher:subscribe").isEmpty())
        job.cancel()
        realtime.disconnect()
    }

    @Test
    fun dropsTheSubscriptionOfAListenerThatLeftDuringItsAuth() {
        val factory = SocketFactory()
        val gate = CompletableDeferred<Unit>()
        var asked = 0
        val realtime = client(factory, ManualClock(), authorize = { _, _ ->
            asked += 1
            gate.await()
            "late"
        })
        realtime.connect()
        eventually { factory.sockets.size == 1 }
        factory.sockets[0].established()
        eventually { realtime.isConnected.value }
        val job = realtime.listen("private-inbox.1")
        eventually { asked == 1 }
        job.cancel()
        Thread.sleep(20)
        gate.complete(Unit)
        Thread.sleep(30)
        assertTrue(factory.sockets[0].sentEvents("pusher:subscribe").isEmpty())
        realtime.disconnect()
    }
}
