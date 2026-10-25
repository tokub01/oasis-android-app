package de.velwebsolutions.oasis.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiConfig
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.core.network.RemoteCommonApi
import de.velwebsolutions.oasis.core.network.TokenProvider
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.data.DefaultNetworkRepository
import de.velwebsolutions.oasis.testing.StubTransport
import de.velwebsolutions.oasis.testing.testTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue

/** A repository over canned answers, with the transport to add answers and read the requests. */
class StubbedNetwork(vararg stubs: StubTransport.Stub) {
    val transport = StubTransport(*stubs)

    /**
     * The client of [transport]; with [immediate] OkHttp answers on the calling thread, so suspending calls return
     * without suspending (the other half of every suspension point).
     */
    private val client: ApiClient = if (immediate) {
        val tokens = object : TokenProvider {
            override fun currentToken() = "token"

            override fun onUnauthenticated() = Unit
        }
        val direct = Dispatcher(DirectExecutor)
        ApiClient(
            ApiConfig("http://localhost", "OasisApp/1.0 (Android; Android 16)"),
            tokens,
            OkHttpClient.Builder().dispatcher(direct).addInterceptor(transport).build()
        )
    } else {
        transport.client
    }
    val repository = DefaultNetworkRepository(client)
    val common = RemoteCommonApi(client)

    /** A signed-out session: enough for the composer, which refreshes `me` only after a held Tropfen. */
    val session = Session(testTokenStore(null), "Pixel Test", CoroutineScope(Dispatchers.Unconfined))

    fun add(method: String, path: String, status: Int = 200, json: String = "{}") = transport.add(method, path, status, json)

    /** The last request to [path], with its body as text. */
    fun last(path: String): StubTransport.Recorded = transport.requests.last { it.path == path }
}

/** Whether [StubbedNetwork]s answer on the calling thread (set by tests that run in both modes). */
@Volatile
var immediate = false

/** Runs tasks on the calling thread. */
private object DirectExecutor : java.util.concurrent.AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() = Unit
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
}

fun stub(method: String, path: String, json: String = "{}", status: Int = 200) = StubTransport.Stub(method, path, status, json)

/** A Tropfen card as the API sends it (the iOS tests' `FeedTests.post`). */
fun postJson(id: Int, author: Int = 2, reacted: Boolean = false, replyTo: Int? = null, visibility: String = "public"): String {
    val reply = replyTo?.let { """{"id":$it,"username":"u1"}""" } ?: "null"
    return """
        {"id":$id,"url":"http://localhost/tropfen/$id","body":"Tropfen $id","segments":[{"type":"text","text":"Tropfen $id"}],"kind":"post",
        "visibility":"$visibility","created_at":"2026-10-22T10:00:00.000000Z","edited_at":null,
        "author":{"id":$author,"username":"u$author","display_name":"U $author","initials":"U","avatar_color":"sun","photo_url":null},
        "oasis":null,"reply_to":$reply,"reply_unavailable":false,"images_locked":false,"images":[],"carried":null,"removed_from_oasis":false,
        "held":null,"stats":null,"conversation":null,"viewer":{"is_author":false,"reacted":$reacted,"bookmarked":false,"can_interact":true,
        "can_reply":true,"can_carry":true,"can_write_reply":true,"can_quick_reply":false,"can_edit":false,"can_delete":false,
        "can_remove_from_oasis":false,"has_muted_author":false,"has_restricted_author":false}}
    """.trimIndent()
}

/** A page of Tropfen with an optional cursor and context. */
fun pageJson(vararg posts: String, next: String? = null, context: String? = null): String {
    val cursor = next?.let { "\"$it\"" } ?: "null"
    val side = context?.let { ""","context":$it""" }.orEmpty()
    return """{"data":[${posts.joinToString(",")}],"meta":{"next_cursor":$cursor,"per_page":20}$side}"""
}

/** Decodes a Tropfen card like the API client does. */
fun decodePost(json: String): Post = OasisJson.snake.decodeFromString(Post.serializer(), json)

/**
 * Keeps view models in a store so a test can clear them afterwards: their scopes stop, and no view model of an earlier
 * test reacts to [NetworkSignals] of a later one.
 */
class ViewModelKeeper {
    private val store = ViewModelStore()
    private var count = 0

    fun <T : ViewModel> keep(viewModel: T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = viewModel as V
        }
        return ViewModelProvider(store, factory)["model${count++}", viewModel.javaClass]
    }

    fun clear() = store.clear()
}

/** Waits (in real time) until work started on OkHttp's threads has arrived. */
fun eventually(timeoutMillis: Long = 3_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMillis
    while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
    assertTrue("condition not met within $timeoutMillis ms", condition())
}

/**
 * Waits until [condition] holds. Answers come back from OkHttp's threads and Dispatchers.IO through the main looper, which
 * Robolectric only runs when the test idles it; `waitUntil` alone does not always get there.
 */
fun androidx.compose.ui.test.junit4.ComposeTestRule.settle(timeoutMillis: Long = 15_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < end) { "condition not met within $timeoutMillis ms" }
        Thread.sleep(20)
        mainClock.advanceTimeBy(50)
        waitForIdle()
    }
}
