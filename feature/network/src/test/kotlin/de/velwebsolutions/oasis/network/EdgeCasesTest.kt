package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.model.ToneHint
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.CommonApi
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.PostDraft
import de.velwebsolutions.oasis.network.api.PublishResult
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.PostListModel
import de.velwebsolutions.oasis.network.ui.compose.ComposeUiState
import de.velwebsolutions.oasis.network.ui.compose.ComposeViewModel
import de.velwebsolutions.oasis.network.ui.feeds.SearchViewModel
import de.velwebsolutions.oasis.network.ui.feeds.SourceViewModel
import de.velwebsolutions.oasis.network.ui.posts.PostDetailViewModel
import de.velwebsolutions.oasis.network.ui.profiles.FriendsViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The rarer paths of the view models: answers that arrive late, actions before a page loaded, limits and races. */
@RunWith(Parameterized::class)
class EdgeCasesTest(private val answersAtOnce: Boolean) {
    companion object {
        /** Each test runs with answers that suspend and with answers on the calling thread. */
        @JvmStatic
        @Parameterized.Parameters(name = "immediate={0}")
        fun modes() = listOf(false, true)
    }

    @get:Rule
    val main = MainDispatcherRule()

    private val models = ViewModelKeeper()

    @Before
    fun answerMode() {
        immediate = answersAtOnce
    }

    @After
    fun clear() {
        immediate = false
        models.clear()
        NetworkSignals.confirmation.value = null
    }

    private val ok = """{"message":"Erledigt."}"""
    private val jpeg = ImageUpload(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "bild.jpg", "image/jpeg")

    @Test
    fun `the list takes one page of older Tropfen at a time and drops or keeps cards by its kind`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/bookmarks", pageJson(postJson(3), postJson(2), next = "abc")),
            stub("POST", "network/posts/3/reaction", ok),
            stub("DELETE", "network/posts/3", ok),
            stub("DELETE", "network/posts/2/bookmark", ok)
        )
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val slow = object : NetworkRepository by network.repository {
            override suspend fun bookmarks(cursor: String?): FeedPage<NoContext> {
                calls++
                if (cursor != null) gate.await()
                return network.repository.bookmarks(cursor)
            }
        }
        val drops = PostListModel<NoContext>(slow, dropsUnbookmarked = true) { _, cursor -> bookmarks(cursor) }
        assertNull(drops.filter)
        drops.reload()
        val first = launch { drops.loadMore() }
        runCurrent()
        drops.loadMore()
        gate.complete(Unit)
        first.join()
        assertEquals(2, calls)

        drops.perform(PostAction.React, drops.posts[0])
        assertEquals(true, drops.posts[0].viewer?.reacted)
        drops.perform(PostAction.Delete, drops.posts[0])
        assertEquals(listOf(2), drops.posts.map { it.id })

        val keeps = PostListModel<NoContext>(network.repository) { _, cursor -> bookmarks(cursor) }
        keeps.reload()
        val marked = keeps.posts[1].copy(viewer = keeps.posts[1].viewer?.copy(bookmarked = true))
        keeps.perform(PostAction.Unbookmark, marked)
        assertEquals(listOf(3, 2), keeps.posts.map { it.id })
    }

    @Test
    fun `the composer's limits, empty choices and stale answers`() = runTest {
        assertEquals(4, ComposeUiState().maxImages)
        assertEquals("Kaputt.", ComposeUiState(error = ApiError.Validation("Kaputt.", emptyMap())).generalError)

        val open = """{"data": {"default_visibility": "friends", "visibilities": [], "kinds": [], "links": [], "max_length": 500,
            |"max_images": 4}}
        """.trimMargin()
        val gate = CompletableDeferred<Unit>()
        val network = StubbedNetwork(stub("GET", "network/composer", open))
        val slowTone = object : CommonApi by network.common {
            override suspend fun toneHint(text: String, field: String): ToneHint {
                gate.await()
                return ToneHint("alt", nudge = true)
            }
        }
        val model = models.keep(ComposeViewModel("grenzen", 12, null, network.repository, slowTone, network.session))
        model.load()
        assertEquals("friends", model.state.value.visibility)

        model.updateBody("Erste Fassung")
        val check = launch { model.checkTone() }
        runCurrent()
        model.updateBody("Zweite Fassung")
        gate.complete(Unit)
        check.join()
        assertNull(model.state.value.toneHint)

        model.add(null)
        model.add(jpeg)
        model.add(jpeg)
        model.updateAlt(1, "Zweites")
        model.updateAlt(-1, "nichts")
        model.removeImage(-1)
        assertEquals(listOf("", "Zweites"), model.state.value.images.map { it.alt })
        model.discard()
    }

    @Test
    fun `a reply is always a plain Tropfen, a second tap while publishing sends nothing, and a tone question stays`() = runTest {
        val question = "Nochmal lesen? Deine Antwort klingt verletzend."
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"$question","errors":{"body":["$question"]}}""", 422)
        )
        val gate = CompletableDeferred<Unit>()
        var sent: PostDraft? = null
        val slow = object : NetworkRepository by network.repository {
            override suspend fun publish(draft: PostDraft): PublishResult {
                sent = draft
                gate.await()
                return network.repository.publish(draft)
            }
        }
        val model = models.keep(ComposeViewModel(null, 12, null, slow, network.common, network.session))
        model.load()
        model.setKind("question")
        model.updateBody("Du Idiot")
        val first = launch { model.publish() }
        runCurrent()
        assertNull(model.publish())
        gate.complete(Unit)
        first.join()
        assertEquals("post", sent?.kind)
        assertTrue(model.state.value.toneNudge)

        network.add("POST", "network/posts", 500, """{"message":"Kaputt."}""")
        assertNull(model.publish())
        assertTrue(model.state.value.toneNudge)
    }

    @Test
    fun `the Quelle reloads for an edit and before its first page, the search guards its term`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/source", pageJson(postJson(3), context = """{"show_all":false,"follows_anyone":true,"can_post":true}""")),
            stub("GET", "network/search", """{"message":"Kaputt."}""", 500)
        )
        val source = models.keep(SourceViewModel(network.repository, network.session))
        NetworkSignals.didPublish(decodePost(postJson(8)), "Dein Tropfen ist da.")
        assertEquals(listOf(8), source.list.posts.map { it.id })
        NetworkSignals.didPublish(null, "Gespeichert.")
        eventually { network.transport.requests.size == 1 && source.list.state.value.context != null }

        val search = models.keep(SearchViewModel("garten", network.repository))
        search.updateQuery("g".repeat(SearchViewModel.MAX_LENGTH + 1))
        search.search()
        assertEquals(1, network.transport.requests.size)
        search.list.filter = null
        search.list.reload()
        assertEquals("", network.last("network/search").request.url.queryParameter("q"))
        assertEquals("Kaputt.", SearchViewModel.message(ApiError.Server(500, "Kaputt.")))
        assertNotNull(source.list.state.value.context)
    }

    @Test
    fun `the Tropfen page before it loaded, a restriction of its author and a muted reply by the same person`() = runTest {
        val detail = """{"data":{"post":${postJson(12, author = 4)},"replies":[${postJson(23, author = 4, replyTo = 12)},
            |${postJson(24, author = 6, replyTo = 12)}]}}
        """.trimMargin()
        val network = StubbedNetwork(
            stub("GET", "network/posts/12", detail),
            stub("POST", "network/posts/12/reaction", ok),
            stub("DELETE", "network/posts/12", ok),
            stub("POST", "network/users/4/restrict", ok),
            stub("POST", "network/users/4/mute", ok)
        )
        val model = models.keep(PostDetailViewModel(12, network.repository))
        val early = decodePost(postJson(12, author = 4))
        model.perform(PostAction.React, early)
        model.perform(PostAction.Delete, early)
        assertFalse(model.leave.value)

        model.load()
        val post = model.detail.value.valueOrNull!!.post
        model.perform(PostAction.Restrict, post)
        assertEquals(true, model.detail.value.valueOrNull!!.post.viewer?.hasRestrictedAuthor)
        assertEquals(true, model.detail.value.valueOrNull!!.replies[0].viewer?.hasRestrictedAuthor)

        model.perform(PostAction.Mute, model.detail.value.valueOrNull!!.replies[0])
        val after = model.detail.value.valueOrNull!!
        assertEquals(12, after.post.id)
        assertEquals(listOf(24), after.replies.map { it.id })
    }

    @Test
    fun `a refused friendship answer keeps the lists`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/friends", Fixtures.friends),
            stub("POST", "network/friends/3/accept", """{"message":"Nicht mehr offen."}""", 422)
        )
        val model = models.keep(FriendsViewModel(network.repository))
        model.load()
        model.accept(3)
        assertEquals("Nicht mehr offen.", model.error.value?.userMessage)
        assertEquals(1, network.transport.requests.count { it.path == "network/friends" })
    }

    @Test
    fun `tone answers with and without the question, a read link and an oasis founded with its banner`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/profile", Fixtures.ownProfile),
            stub("POST", "network/tone", """{"data":{"hint":null,"nudge":false}}"""),
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"Da.","suspended":false,"data":${postJson(50)}}""", 201),
            stub("POST", "network/oases", """{"data":{"slug":"mit-banner"}}""", 201)
        )
        val profile = models.keep(de.velwebsolutions.oasis.network.ui.profiles.EditProfileViewModel(network.repository, network.common))
        profile.load()
        profile.checkTone()
        assertFalse(profile.form.value.toneNudge)

        network.add("POST", "network/tone", json = """{"data":{"hint":"Idiot","nudge":true}}""")
        val composer = models.keep(ComposeViewModel("gelesen", null, null, network.repository, network.common, network.session))
        composer.load()
        composer.updateBody("Du Idiot, lies das")
        composer.checkTone()
        assertTrue(composer.state.value.toneNudge)
        composer.setLinkRead(true)
        composer.setToneConfirmed(true)
        assertNotNull(composer.publish())
        assertTrue(network.last("network/posts").body!!.contains(Regex("name=\"link_read\"[\\s\\S]*?\r\n\r\n1\r\n")))

        val form = models.keep(de.velwebsolutions.oasis.network.ui.oases.OasisFormViewModel(null, network.repository))
        form.pickBanner(null)
        assertNull(form.state.value.banner)
        form.pickBanner(jpeg)
        form.save()
        assertTrue(network.last("network/oases").body!!.contains("filename=\"bild.jpg\""))
    }
}
