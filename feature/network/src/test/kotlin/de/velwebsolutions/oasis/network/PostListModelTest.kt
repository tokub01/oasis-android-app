package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.api.DiscoverContext
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.PostListModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feeds and card actions (iOS `FeedTests`). */
class PostListModelTest {
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
}
