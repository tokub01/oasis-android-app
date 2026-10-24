package de.velwebsolutions.oasis.realtime

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * One WebSocket connection as the Reverb client needs it: text frames in and out, and closing. Abstracted so unit tests
 * can play the server (`FakeWebSocket` in the tests).
 */
interface WebSocketConnection {
    /** Sends one text frame; throws when the connection is closed. */
    suspend fun send(text: String)

    /** Waits for the next text frame; throws when the connection is closed or fails. */
    suspend fun receive(): String

    /** Closes the connection; a pending [receive] then throws. */
    fun close()
}

/** Creates a connection to a WebSocket URL (`ws://…` or `wss://…`). */
typealias WebSocketFactory = (url: String) -> WebSocketConnection

/** The real connection over OkHttp's WebSocket; frames are buffered until [receive] takes them. */
class OkHttpWebSocketConnection(client: OkHttpClient, url: String) : WebSocketConnection {
    private val incoming = Channel<String>(Channel.UNLIMITED)

    private val socket: WebSocket = client.newWebSocket(
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                incoming.trySend(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Answer with a normal close: OkHttp refuses to echo reserved codes such as 1005 ("no status").
                webSocket.close(NORMAL_CLOSURE, null)
                incoming.close(IOException("Closed by the server ($code)"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close(IOException("Closed ($code)"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.close(t)
            }
        }
    )

    override suspend fun send(text: String) {
        if (!socket.send(text)) throw IOException("The connection is closed")
    }

    override suspend fun receive(): String = incoming.receive()

    override fun close() {
        socket.close(NORMAL_CLOSURE, null)
        incoming.close(IOException("Closed"))
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
