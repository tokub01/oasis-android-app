package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.components.CardConfirmation
import de.velwebsolutions.oasis.network.ui.components.answeredLabel
import de.velwebsolutions.oasis.network.ui.feeds.BookmarksViewModel
import de.velwebsolutions.oasis.network.ui.feeds.DiscoverViewModel
import de.velwebsolutions.oasis.network.ui.feeds.SourceViewModel
import de.velwebsolutions.oasis.network.ui.feeds.SwipeViewModel
import de.velwebsolutions.oasis.network.ui.feeds.TagViewModel
import de.velwebsolutions.oasis.network.ui.perform
import de.velwebsolutions.oasis.network.ui.posts.CarryViewModel
import de.velwebsolutions.oasis.network.ui.posts.EditPostViewModel
import de.velwebsolutions.oasis.network.ui.posts.PostDetailViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The Tropfen page, editing and carrying, the feeds' view models and the card actions' endpoints. */
@RunWith(Parameterized::class)
class PostViewModelsTest(private val answersAtOnce: Boolean) {
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

    @get:Rule
    val main = MainDispatcherRule()

    private val models = ViewModelKeeper()

    @After
    fun clear() {
        models.clear()
        NetworkSignals.confirmation.value = null
    }

    private val ok = """{"message":"Erledigt."}"""

    @Test
    fun `every card action goes to its endpoint`() = runTest {
        val network = StubbedNetwork(
            stub("POST", "network/posts/7/reaction", ok),
            stub("DELETE", "network/posts/7/reaction", ok),
            stub("POST", "network/posts/7/bookmark", ok),
            stub("DELETE", "network/posts/7/bookmark", ok),
            stub("DELETE", "network/posts/7", ok),
            stub("POST", "network/posts/7/remove-from-oasis", ok),
            stub("POST", "network/users/2/mute", ok),
            stub("DELETE", "network/users/2/mute", ok),
            stub("POST", "network/users/2/restrict", ok),
            stub("DELETE", "network/users/2/restrict", ok),
            stub("POST", "network/users/2/block", ok)
        )
        val post = decodePost(postJson(7))
        PostAction.entries.forEach { assertEquals("Erledigt.", network.repository.perform(it, post)) }
        assertEquals(
            listOf(
                "POST network/posts/7/reaction", "DELETE network/posts/7/reaction", "POST network/posts/7/bookmark",
                "DELETE network/posts/7/bookmark", "DELETE network/posts/7", "POST network/posts/7/remove-from-oasis",
                "POST network/users/2/mute", "DELETE network/users/2/mute", "POST network/users/2/restrict",
                "DELETE network/users/2/restrict", "POST network/users/2/block"
            ),
            network.transport.requests.map { "${it.request.method} ${it.path}" }
        )
    }

    @Test
    fun `removing the Tropfen from the oasis or blocking its author leaves the page`() = runTest {
        listOf(PostAction.RemoveFromOasis to "POST network/posts/12/remove-from-oasis", PostAction.Block to "POST network/users/4/block")
            .forEach { (action, call) ->
                val (method, path) = call.split(" ")
                val network = StubbedNetwork(stub("GET", "network/posts/12", Fixtures.post), stub(method, path, ok))
                val model = models.keep(PostDetailViewModel(12, network.repository))
                model.load()
                model.perform(action, model.detail.value.valueOrNull!!.post)
                assertTrue(model.leave.value)
                assertEquals("Erledigt.", NetworkSignals.confirmation.value)
            }
    }

    @Test
    fun `muting the author reloads the page, restricting marks every card of the person`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/posts/12", Fixtures.post),
            stub("POST", "network/users/4/mute", ok),
            stub("POST", "network/users/2/restrict", ok)
        )
        val model = models.keep(PostDetailViewModel(12, network.repository))
        model.load()
        val detail = model.detail.value.valueOrNull!!
        model.perform(PostAction.Mute, detail.post)
        assertFalse(model.leave.value)
        assertEquals(2, network.transport.requests.count { it.path == "network/posts/12" })

        val reply = detail.replies.first()
        model.perform(PostAction.Restrict, reply)
        assertEquals(true, model.detail.value.valueOrNull!!.replies.first().viewer?.hasRestrictedAuthor)
    }

    @Test
    fun `a refused reaction on the page rolls back, a refused delete stays`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/posts/12", Fixtures.post),
            stub("POST", "network/posts/12/bookmark", """{"message":"Kaputt."}""", 500),
            stub("DELETE", "network/posts/12", """{"message":"Kein Zugriff."}""", 403)
        )
        val model = models.keep(PostDetailViewModel(12, network.repository))
        model.load()
        val post = model.detail.value.valueOrNull!!.post
        model.perform(PostAction.Bookmark, post)
        assertEquals(post.viewer?.bookmarked, model.detail.value.valueOrNull!!.post.viewer?.bookmarked)
        model.perform(PostAction.Delete, post)
        assertEquals("Kein Zugriff.", model.error.value?.userMessage)
        assertFalse(model.leave.value)

        model.perform(PostAction.Bookmark, decodePost(postJson(99)))
        network.add("GET", "network/posts/12", 500, """{"message":"Kaputt."}""")
        NetworkSignals.didPublish(null, "Gespeichert.")
        eventually { model.error.value?.userMessage == "Kaputt." }
        assertTrue(model.detail.value.valueOrNull != null)
    }

    @Test
    fun `editing loads the text, notices changes and saves`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/posts/12/edit", """{"data":{"body":"Hallo","until":"2026-10-24T12:00:00.000000Z"}}"""),
            stub("PUT", "network/posts/12", """{"message":"Deine Änderung ist gespeichert."}""")
        )
        val model = models.keep(EditPostViewModel(12, network.repository))
        assertFalse(model.isChanged)
        model.load()
        assertFalse(model.isChanged)
        model.updateText("Hallo Welt")
        assertTrue(model.isChanged)

        val before = NetworkSignals.published.value
        model.save()
        assertTrue(model.saved.value)
        assertTrue(NetworkSignals.published.value > before)
        assertNull(NetworkSignals.lastPublished)
        assertEquals("Deine Änderung ist gespeichert.", NetworkSignals.confirmation.value)
        assertTrue(network.last("network/posts/12").body!!.contains("Hallo Welt"))
    }

    @Test
    fun `editing shows why it cannot load or save`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/posts/12/edit", """{"message":"Die Zeit ist um."}""", 403),
            stub("PUT", "network/posts/12", """{"message":"Zu lang.","errors":{"body":["Zu lang."]}}""", 422)
        )
        val model = models.keep(EditPostViewModel(12, network.repository))
        model.load()
        assertTrue(model.form.value is Loadable.Failed)
        model.save()
        assertEquals("Zu lang.", model.error.value?.message("body"))
        assertFalse(model.saved.value)
    }

    @Test
    fun `carrying loads the original or its error`() = runTest {
        val network = StubbedNetwork(
            stub(
                "GET",
                "network/posts/12/carry",
                """{"data":{"id":12,"author":{"id":4,"username":"lukas","display_name":"Lukas Brandt","initials":"LB","avatar_color":"dune",
                |"photo_url":null},"body":"Samstag Werkstatt.","segments":[],"links":[{"url":"https://example.org","host":"example.org"}]}}
                """.trimMargin()
            )
        )
        val model = models.keep(CarryViewModel(12, network.repository))
        model.load()
        assertEquals("example.org", model.source.value.valueOrNull?.links?.single()?.host)

        network.add("GET", "network/posts/12/carry", 404, """{"message":"Nicht gefunden."}""")
        model.load()
        assertTrue(model.source.value is Loadable.Failed)
    }

    @Test
    fun `Entdecken, Gemerkt and a topic load their lists and reload after a new Tropfen`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/discover", pageJson(postJson(3), context = """{"oases":[]}""")),
            stub("GET", "network/bookmarks", pageJson(postJson(2))),
            stub("GET", "network/tags/garten", pageJson(postJson(1), context = """{"tag":"garten","can_post":true}"""))
        )
        val discover = models.keep(DiscoverViewModel(network.repository))
        val bookmarks = models.keep(BookmarksViewModel(network.repository))
        val tag = models.keep(TagViewModel("garten", network.repository))
        discover.list.reload()
        bookmarks.list.reload()
        tag.list.reload()
        assertEquals(listOf(3), discover.list.posts.map { it.id })
        assertEquals(listOf(2), bookmarks.list.posts.map { it.id })
        assertEquals(true, tag.list.state.value.context?.canPost)

        NetworkSignals.didPublish(decodePost(postJson(8)), "Dein Tropfen ist da.")
        eventually { network.transport.requests.size == 6 }
        assertEquals(0, discover.scrollToTop.value)
    }

    @Test
    fun `the Quelle switches its view and keeps a not public Tropfen out of Ganz Oasis`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/source", pageJson(postJson(3), context = """{"show_all":true,"follows_anyone":true,"can_post":true}"""))
        )
        val model = models.keep(SourceViewModel(network.repository, network.session))
        assertFalse(model.askedWelcome)
        model.selectView("alle")
        eventually { model.list.posts.isNotEmpty() }
        assertEquals("alle", network.last("network/source").request.url.queryParameter("view"))

        NetworkSignals.didPublish(decodePost(postJson(8, visibility = "friends")), "Dein Tropfen ist da.")
        eventually { network.transport.requests.size == 2 && !model.list.state.value.isReloading }
        assertEquals(listOf(3), model.list.posts.map { it.id })
        NetworkSignals.didPublish(decodePost(postJson(9)), "Dein Tropfen ist da.")
        assertEquals(listOf(9, 3), model.list.posts.map { it.id })
    }

    @Test
    fun `Durchblättern loads once and rolls a refused reaction back`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/swipe", Fixtures.swipe))
        val model = models.keep(SwipeViewModel(network.repository))
        model.perform(PostAction.React, decodePost(postJson(1)))
        model.loadIfNeeded()
        model.loadIfNeeded()
        assertEquals(1, network.transport.requests.count { it.path == "network/swipe" })

        val card = model.deck.value.valueOrNull!!.chapters.first().posts.first()
        val reacted = card.viewer?.reacted == true
        val action = if (reacted) PostAction.Unreact else PostAction.React
        model.perform(action, card)
        val after = model.deck.value.valueOrNull!!.chapters.first().posts.first()
        assertEquals(reacted, after.viewer?.reacted)
        assertTrue(model.error.value != null)

        network.add("POST", "network/posts/${card.id}/bookmark", json = ok)
        network.add("DELETE", "network/posts/${card.id}/bookmark", json = ok)
        val bookmark = if (card.viewer?.bookmarked == true) PostAction.Unbookmark else PostAction.Bookmark
        model.perform(bookmark, card)
        assertEquals("Erledigt.", model.status.value)
        assertNull(model.error.value)
    }

    @Test
    fun `the destructive card actions ask with the web's words`() {
        assertEquals(
            listOf(PostAction.Delete, PostAction.Block, PostAction.RemoveFromOasis),
            CardConfirmation.entries.map { it.action }
        )
        assertNull(CardConfirmation.Delete.message)
        assertEquals("Blockieren bestätigen", CardConfirmation.Block.button)
        assertEquals("Wirklich aus der Oase entfernen?", CardConfirmation.RemoveFromOasis.title)
        assertEquals("Noch hat niemand geantwortet", answeredLabel(0))
        assertEquals("1 Mensch hat geantwortet", answeredLabel(1))
        assertEquals("3 Menschen haben geantwortet", answeredLabel(3))
    }
}
