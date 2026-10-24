package de.velwebsolutions.oasis.testing

import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiConfig
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.core.network.TokenProvider
import kotlinx.serialization.json.JsonElement
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers API calls from canned responses – for unit tests of repositories and view models. Each route is matched by
 * method and path (relative to `/api/v1/`, query ignored); requests are recorded. Later stubs for the same route win.
 */
class StubTransport(vararg stubs: Stub) : Interceptor {
    data class Stub(
        val method: String,
        val path: String,
        val status: Int = 200,
        val json: String = "{}",
        val headers: Map<String, String> = emptyMap(),
    )

    /** A request as it was sent, with its body as text. */
    data class Recorded(val request: Request, val body: String?) {
        val path: String get() = apiPath(request)
    }

    private val stubs = CopyOnWriteArrayList(stubs.toList())
    private val recorded = CopyOnWriteArrayList<Recorded>()
    var unauthenticatedCount = 0
        private set

    /** A client of `http://localhost` that sends every request through this transport, signed in with "token". */
    val client: ApiClient by lazy {
        val tokens = object : TokenProvider {
            override fun currentToken() = "token"

            override fun onUnauthenticated() {
                unauthenticatedCount++
            }
        }
        ApiClient(ApiConfig("http://localhost", "OasisApp/1.0 (Android; Android 16)"), tokens, OkHttpClient.Builder().addInterceptor(this).build())
    }

    fun add(stub: Stub) {
        stubs += stub
    }

    fun add(method: String, path: String, status: Int = 200, json: String = "{}") = add(Stub(method, path, status, json))

    /** All requests sent so far. */
    val requests: List<Recorded> get() = recorded.toList()

    /** The decoded JSON body of the last request to `path`. */
    fun lastBody(path: String): JsonElement? =
        recorded.lastOrNull { it.path == path }?.body?.let { runCatching { OasisJson.verbatim.parseToJsonElement(it) }.getOrNull() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }
        recorded += Recorded(request, body)
        val path = apiPath(request)
        val stub = stubs.lastOrNull { it.method == request.method && it.path == path }
            ?: throw IOException("Kein Stub für ${request.method} $path")
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(stub.status)
            .message("stub")
            .apply { stub.headers.forEach { (name, value) -> header(name, value) } }
            .body(stub.json.toResponseBody("application/json".toMediaType()))
            .build()
    }

    companion object {
        fun apiPath(request: Request): String = request.url.encodedPath.substringAfter("/api/v1/")
    }
}

/** A session-free token store for tests. */
fun testTokenStore(token: String? = "token") = InMemoryTokenStore(token)
