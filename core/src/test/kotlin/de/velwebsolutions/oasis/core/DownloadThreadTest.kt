package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiConfig
import de.velwebsolutions.oasis.core.network.TokenProvider
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Downloads (invoices, the data export, evidence) are called from the main thread. Their body is still on the socket
 * when the call answers, so it must be read elsewhere: Android refuses network reads on the main thread.
 */
class DownloadThreadTest {
    @Test
    fun `the body of a download is read off the calling thread`() {
        val readOn = AtomicReference<Thread>()
        val socket = object : ForwardingSource(Buffer().writeUtf8("PK")) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readOn.compareAndSet(null, Thread.currentThread())
                return super.read(sink, byteCount)
            }
        }
        val server = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(socket.buffer().asResponseBody("application/zip".toMediaType()))
                .build()
        }.build()
        val tokens = object : TokenProvider {
            override fun currentToken(): String? = null

            override fun onUnauthenticated() = Unit
        }
        val client = ApiClient(ApiConfig("http://localhost", "OasisApp/1.0"), tokens, server)
        val screen = Executors.newSingleThreadExecutor { Thread(it, "screen") }
        try {
            val bytes = runBlocking(screen.asCoroutineDispatcher()) { client.download("/api/v1/account/export") }
            assertArrayEquals("PK".toByteArray(), bytes)
        } finally {
            screen.shutdown()
        }
        assertNotEquals("the calling thread never touches the socket", "screen", readOn.get().name)
    }
}
