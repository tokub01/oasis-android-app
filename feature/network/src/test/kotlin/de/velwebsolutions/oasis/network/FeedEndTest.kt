package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.network.api.DiscoverContext
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.SearchContext
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.ui.PostListModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end of every paginated list with the server's real last pages (taken from the local API, round N): the list ends
 * quietly, without „Die Antwort von Oasis war unerwartet“ and without a spinner.
 */
class FeedEndTest {
    /** GET /network/discover?cursor=… – the last page, with PHP's empty array as `context`. */
    private val discoverLast = """
        {"data":[${postJson(1)}],"links":{"first":null,"last":null,"prev":"http://localhost/api/v1/network/discover?cursor=eyJpZCI6N30",
        "next":null},"meta":{"path":"http://localhost/api/v1/network/discover","per_page":20,"next_cursor":null,
        "prev_cursor":"eyJpZCI6N30"},"context":[]}
    """.trimIndent()

    /** GET /network/source?view=alle&cursor=… – later pages carry only the switches in `context`. */
    private val sourceLast = """
        {"data":[${postJson(2)}],"links":{"first":null,"last":null,"prev":"http://localhost/api/v1/network/source?cursor=eyJpZCI6N30",
        "next":null},"meta":{"path":"http://localhost/api/v1/network/source","per_page":20,"next_cursor":null,
        "prev_cursor":"eyJpZCI6N30"},"context":{"show_all":true,"follows_anyone":true,"can_post":true}}
    """.trimIndent()

    /** An empty last page (profile, oasis, Merkliste: no `context`). */
    private val emptyLast = """
        {"data":[],"links":{"first":null,"last":null,"prev":null,"next":null},"meta":{"path":"http://localhost/api/v1/network/bookmarks",
        "per_page":20,"next_cursor":null,"prev_cursor":"eyJpZCI6N30"}}
    """.trimIndent()

    @Test
    fun `the last page of Entdecken with an empty context ends quietly`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/discover", pageJson(postJson(3), next = "abc", context = """{"oases":[]}""")))
        val model = PostListModel<DiscoverContext>(network.repository) { _, cursor -> discover(cursor) }
        model.reload()
        network.add("GET", "network/discover", json = discoverLast)
        model.loadMore()
        assertNull(model.state.value.loadMoreError)
        assertEquals(listOf(3, 1), model.posts.map { it.id })
        assertTrue(model.state.value.reachedEnd)
        assertEquals("the first page's side information stays", DiscoverContext(emptyList()), model.state.value.context)
    }

    @Test
    fun `every list's real last page decodes`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/source", sourceLast),
            stub(
                "GET",
                "network/search",
                sourceLast.replace(""""show_all":true,"follows_anyone":true,"can_post":true""", """"term":"der","searching":true""")
            ),
            stub("GET", "network/profiles/u2/posts", emptyLast),
            stub("GET", "network/oases/garten/posts", emptyLast),
            stub("GET", "network/bookmarks", emptyLast)
        )
        val repository = network.repository
        val source = repository.source("alle", "eyJpZCI6N30")
        assertEquals(SourceContext(showAll = true), source.context)
        assertNull(source.nextCursor)
        assertEquals(SearchContext(term = "der", searching = true), repository.search("der", "x").context)
        for (page in listOf(repository.profilePosts("u2", "x"), repository.oasisPosts("garten", "x"), repository.bookmarks("x"))) {
            assertTrue(page.data.isEmpty())
            assertNull(page.nextCursor)
        }
    }

    @Test
    fun `a context that is no object is no context`() {
        val serializer = FeedPage.serializer(DiscoverContext.serializer())
        assertNull(OasisJson.snake.decodeFromString(serializer, discoverLast).context)
        assertNull(OasisJson.snake.decodeFromString(serializer, """{"data":[],"context":5}""").context)
        assertNull(OasisJson.snake.decodeFromString(FeedPage.serializer(NoContext.serializer()), emptyLast).context)
        val first = OasisJson.snake.decodeFromString(serializer, """{"data":[],"context":{"oases":[]}}""")
        assertEquals(DiscoverContext(emptyList()), first.context)
        assertEquals(first, OasisJson.snake.decodeFromString(serializer, OasisJson.snake.encodeToString(serializer, first)))
    }

    @Test
    fun `page numbers continue and end like cursors`() {
        assertEquals("2", FeedPage<NoContext>(meta = Page.Meta(currentPage = 1, lastPage = 2)).nextCursor)
        assertNull("a page beyond the end", FeedPage<NoContext>(meta = Page.Meta(currentPage = 5, lastPage = 1, total = 0)).nextCursor)
        assertNull(FeedPage<NoContext>(meta = Page.Meta(currentPage = 1)).nextCursor)
        assertNull(FeedPage<NoContext>(meta = Page.Meta(lastPage = 2)).nextCursor)
        assertNull(FeedPage<NoContext>().nextCursor)
        assertEquals("c", Page(emptyList<Int>(), Page.Meta(nextCursor = "c", currentPage = 1, lastPage = 1)).nextCursor)
    }

    @Test
    fun `an older page the app cannot read ends the list quietly, other failures can be retried`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/bookmarks", pageJson(postJson(3), next = "abc")))
        val model = PostListModel<NoContext>(network.repository) { _, cursor -> bookmarks(cursor) }
        model.reload()
        network.add("GET", "network/bookmarks", 500, """{"message":"Kaputt."}""")
        model.loadMore()
        assertNotNull(model.state.value.loadMoreError)
        assertEquals("abc", model.state.value.nextCursor)
        network.add("GET", "network/bookmarks", json = """{"data":[{"id":"kein Tropfen"}]}""")
        model.loadMore()
        assertNull(model.state.value.loadMoreError)
        assertTrue(model.state.value.reachedEnd)
        assertEquals(listOf(3), model.posts.map { it.id })
    }
}
