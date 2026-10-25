package de.velwebsolutions.oasis.realtime

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The OkHttp connection against a real WebSocket server: frames both ways, the path of Reverb, closing. */
class OkHttpWebSocketConnectionTest {
    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<String>()

    @Volatile private var serverSocket: WebSocket? = null

    @Before
    fun setUp() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket = webSocket
                        webSocket.send("""{"event":"pusher:connection_established","data":"{\"socket_id\":\"1.2\"}"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.put(text)
                    }
                }
            )
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendsAndReceivesTextFramesAndThrowsAfterTheServerCloses() = runBlocking {
        val settings = ReverbSettings("oasis-local-key", server.hostName, server.port, "http")
        val connection = OkHttpWebSocketConnection(OkHttpClient(), settings.url)

        val first = withTimeout(5_000) { connection.receive() }
        assertTrue(first.contains("pusher:connection_established"))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/app/oasis-local-key?protocol=7&client=oasis-android&version=1.0&flash=false", request.path)

        connection.send("""{"event":"pusher:ping","data":{}}""")
        assertEquals("""{"event":"pusher:ping","data":{}}""", received.poll(5, TimeUnit.SECONDS))

        serverSocket!!.close(1000, "bye")
        val failure = runCatching { withTimeout(5_000) { connection.receive() } }
        assertTrue("receive fails once the server closed", failure.exceptionOrNull() is java.io.IOException)
        connection.close()
    }

    @Test
    fun readsBinaryFramesAsTextAndRefusesToSendAfterClosing() = runBlocking {
        val settings = ReverbSettings("oasis-local-key", server.hostName, server.port, "http")
        val connection = OkHttpWebSocketConnection(OkHttpClient(), settings.url)
        withTimeout(5_000) { connection.receive() }
        val peer = serverSocket!!
        peer.send("""{"event":"pusher:pong"}""".encodeUtf8())
        assertEquals("""{"event":"pusher:pong"}""", withTimeout(5_000) { connection.receive() })

        peer.close(1000, "bye")
        runCatching { withTimeout(5_000) { connection.receive() } }
        val refused = runCatching { connection.send("{}") }
        assertTrue("a closed connection sends nothing", refused.exceptionOrNull() is java.io.IOException)
        connection.close()
    }

    @Test
    fun failsTheNextReceiveWhenTheUpgradeIsRefused() = runBlocking {
        val refusing = MockWebServer()
        refusing.enqueue(MockResponse().setResponseCode(503))
        refusing.start()
        try {
            val connection = OkHttpWebSocketConnection(OkHttpClient(), ReverbSettings("k", refusing.hostName, refusing.port, "http").url)
            val failure = runCatching { withTimeout(5_000) { connection.receive() } }
            assertTrue(failure.exceptionOrNull() is java.net.ProtocolException)
        } finally {
            refusing.shutdown()
        }
    }
}
