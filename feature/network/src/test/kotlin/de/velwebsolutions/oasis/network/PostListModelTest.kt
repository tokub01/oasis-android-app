package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.api.DiscoverContext
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.PostListModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Feeds and card actions (iOS `FeedTests`). */
@RunWith(Parameterized::class)
class PostListModelTest(private val answersAtOnce: Boolean) {
    companion object {
        /** Each test runs with answers that suspend and with answers on the calling thread. */
        @JvmStatic
        @Parameterized.Parameters(name = "immediate={0}")
        fun modes() = listOf(false, true)
    }

    @Before
    fun answerMode() {
        immediate = answersAtOnce
    }

    @After
    fun suspendingAgain() {
        immediate = false
    }

    private fun bookmarks(network: StubbedNetwork, drops: Boolean = false) =
        PostListModel<NoContext>(network.repository, dropsUnbookmarked = drops) { _, cursor -> bookmarks(cursor) }

    @Test
    fun `pages with the cursor and reaches the end`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/bookmarks", pageJson(postJson(3), postJson(2), next = "abc")))
        val model = bookmarks(network)
        model.reload()
        assertEquals(listOf(3, 2), model.posts.map { it.id })
        assertFalse(model.state.value.reachedEnd)

        network.add("GET", "network/bookmarks", json = pageJson(postJson(1)))
        model.loadMore()
        assertEquals(listOf(3, 2, 1), model.posts.map { it.id })
        assertTrue(model.state.value.reachedEnd)
        assertEquals("abc", network.last("network/bookmarks").request.url.queryParameter("cursor"))
    }

    @Test
    fun `reacts and hides a muted person everywhere`() = runTest {
        val network = StubbedNetwork(
            stub(
                "GET",
                "network/discover",
                pageJson(postJson(3, author = 5), postJson(2, author = 6), postJson(1, author = 5), context = """{"oases":[]}""")
            ),
            stub(
                "POST",
                "network/posts/2/reaction",
                """{"message":"Reaktion gesendet. Nur die Person sieht, wie viele reagiert haben."}"""
            ),
            stub("POST", "network/users/5/mute", """{"message":"Die Person ist stummgeschaltet. Sie merkt nichts."}""")
        )
        val model = PostListModel<DiscoverContext>(network.repository) { _, cursor -> discover(cursor) }
        model.reload()

        model.perform(PostAction.React, model.posts[1])
        assertEquals(true, model.posts[1].viewer?.reacted)
        assertEquals("Reaktion gesendet. Nur die Person sieht, wie viele reagiert haben.", model.state.value.status)

        model.perform(PostAction.Mute, model.posts[0])
        assertEquals(listOf(2), model.posts.map { it.id })
        assertTrue(network.transport.requests.any { it.request.method == "POST" && it.path == "network/users/5/mute" })
    }

    @Test
    fun `shows the server error when an action is refused and rolls the reaction back`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/bookmarks", pageJson(postJson(1))),
            stub("POST", "network/posts/1/reaction", """{"message":"This action is unauthorized."}""", 403)
        )
        val model = bookmarks(network)
        model.reload()
        model.perform(PostAction.React, model.posts[0])
        assertEquals(ApiError.Forbidden("This action is unauthorized."), model.state.value.actionError)
        assertEquals(false, model.posts[0].viewer?.reacted)
    }

    @Test
    fun `the Merkliste drops a Tropfen only once the server confirmed`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/bookmarks", pageJson(postJson(2), postJson(1))),
            stub("DELETE", "network/posts/2/bookmark", """{"message":"Kaputt."}""", 500)
        )
        val model = bookmarks(network, drops = true)
        model.reload()
        model.perform(PostAction.Unbookmark, model.posts[0])
        assertEquals(listOf(2, 1), model.posts.map { it.id })
        assertNotNull(model.state.value.actionError)

        network.add("DELETE", "network/posts/2/bookmark", json = """{"message":"Aus deiner Merkliste entfernt."}""")
        model.perform(PostAction.Unbookmark, model.posts[0])
        assertEquals(listOf(1), model.posts.map { it.id })
    }

    @Test
    fun `a new filter replaces the list and a short term forgets it`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/source", pageJson(postJson(3), context = """{"show_all":false,"follows_anyone":true,"can_post":true}"""))
        )
        val model = PostListModel<SourceContext>(network.repository) { view, cursor -> source(view, cursor) }
        model.reload()
        assertEquals(listOf(3), model.posts.map { it.id })

        network.add(
            "GET",
            "network/source",
            json = pageJson(postJson(5), postJson(4), context = """{"show_all":true,"follows_anyone":true,"can_post":true}""")
        )
        model.filter = "alle"
        model.reload()
        assertEquals(listOf(5, 4), model.posts.map { it.id })
        assertEquals(true, model.state.value.context?.showAll)
        assertFalse(model.state.value.isReloading)
        assertEquals("alle", network.last("network/source").request.url.queryParameter("view"))

        model.reset()
        assertTrue(model.posts.isEmpty())
        assertNull(model.state.value.context)
    }

    @Test
    fun `a failed refresh keeps the list and shows the error`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/bookmarks", pageJson(postJson(1))))
        val model = bookmarks(network)
        model.reload()
        network.add("GET", "network/bookmarks", status = 500, json = """{"message":"Kaputt."}""")
        model.reload()
        assertEquals(listOf(1), model.posts.map { it.id })
        assertEquals("Kaputt.", model.state.value.actionError?.userMessage)
    }

    @Test
    fun `puts a published Tropfen on top once`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/discover", pageJson(postJson(2), postJson(1), context = """{"oases":[]}""")))
        val model = PostListModel<DiscoverContext>(network.repository) { _, cursor -> discover(cursor) }
        model.reload()
        val published = decodePost(postJson(7))
        model.insert(published)
        model.insert(published)
        assertEquals(listOf(7, 2, 1), model.posts.map { it.id })
    }

    @Test
    fun `a card takes no second action while its first one runs`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/bookmarks", pageJson(postJson(2), postJson(1))),
            stub("POST", "network/posts/2/reaction", """{"message":"Reaktion gesendet."}"""),
            stub("POST", "network/posts/1/reaction", """{"message":"Reaktion gesendet."}""")
        )
        val gate = CompletableDeferred<Unit>()
        val slowReactions = object : NetworkRepository by network.repository {
            override suspend fun react(id: Int, on: Boolean): String {
                gate.await()
                return network.repository.react(id, on)
            }
        }
        val model = PostListModel<NoContext>(slowReactions) { _, cursor -> bookmarks(cursor) }
        model.reload()
        val first = model.posts[0]

        val running = launch { model.perform(PostAction.React, first) }
        runCurrent()
        // A second tap on the same card (now "Reagiert") is ignored; another card still works.
        model.perform(PostAction.Unreact, model.posts[0])
        val other = launch { model.perform(PostAction.React, model.posts[1]) }
        runCurrent()
        gate.complete(Unit)
        running.join()
        other.join()

        assertEquals(true, model.posts[0].viewer?.reacted)
        assertEquals(true, model.posts[1].viewer?.reacted)
        val reactions = network.transport.requests.filter { it.path.endsWith("/reaction") }
        assertEquals(listOf("network/posts/1/reaction", "network/posts/2/reaction"), reactions.map { it.path }.sorted())
        assertTrue(reactions.all { it.request.method == "POST" })
    }

    @Test
    fun `older Tropfen load only when there are more and show a failure to retry`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/bookmarks", pageJson(postJson(3), next = "abc")))
        val model = bookmarks(network)
        model.loadMore()
        assertTrue(network.transport.requests.isEmpty())

        model.loadIfNeeded()
        model.loadIfNeeded()
        assertEquals(1, network.transport.requests.size)
        network.add("GET", "network/bookmarks", status = 500, json = """{"message":"Kaputt."}""")
        model.loadMore()
        assertEquals("Kaputt.", model.state.value.loadMoreError?.userMessage)
        assertFalse(model.state.value.isLoadingMore)

        network.add("GET", "network/bookmarks", json = pageJson(postJson(3), postJson(2)))
        model.loadMore()
        assertEquals(listOf(3, 2), model.posts.map { it.id })
        assertNull(model.state.value.loadMoreError)
        model.loadMore()
        assertEquals(3, network.transport.requests.size)
    }

    @Test
    fun `a first page that fails shows the error page`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/bookmarks", status = 500, json = """{"message":"Kaputt."}"""))
        val model = bookmarks(network)
        model.reload()
        assertEquals("Kaputt.", model.state.value.state.errorOrNull?.userMessage)
        assertFalse(model.state.value.reachedEnd)
    }

    @Test
    fun `a list forgotten while loading ignores the late answer`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val network = StubbedNetwork(stub("GET", "network/bookmarks", pageJson(postJson(3), next = "abc")))
        val slow = object : NetworkRepository by network.repository {
            override suspend fun bookmarks(cursor: String?) = network.repository.bookmarks(cursor).also { gate.await() }
        }
        val model = PostListModel<NoContext>(slow) { _, cursor -> bookmarks(cursor) }
        val first = launch { model.reload() }
        runCurrent()
        model.reset()
        gate.complete(Unit)
        first.join()
        assertTrue(model.posts.isEmpty())
        assertFalse(model.state.value.isReloading)
    }

    @Test
    fun `a page forgotten while older Tropfen load ignores them, and status and error can be dismissed`() = runTest {
        var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        val network = StubbedNetwork(
            stub("GET", "network/bookmarks", pageJson(postJson(3), next = "abc")),
            stub("POST", "network/posts/3/bookmark", """{"message":"Gemerkt."}""")
        )
        val slow = object : NetworkRepository by network.repository {
            override suspend fun bookmarks(cursor: String?) = network.repository.bookmarks(cursor).also { gate.await() }
        }
        val model = PostListModel<NoContext>(slow) { _, cursor -> bookmarks(cursor) }
        model.reload()
        gate = CompletableDeferred()
        val more = launch { model.loadMore() }
        runCurrent()
        model.reset()
        gate.complete(Unit)
        more.join()
        assertTrue(model.posts.isEmpty())

        model.reload()
        model.perform(PostAction.Bookmark, model.posts[0])
        assertEquals("Gemerkt.", model.state.value.status)
        model.dismissStatus()
        assertNull(model.state.value.status)
        model.dismissError()
        assertNull(model.state.value.actionError)
    }
}
