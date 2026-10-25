package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.feeds.SearchViewModel
import de.velwebsolutions.oasis.network.ui.feeds.SourceViewModel
import de.velwebsolutions.oasis.network.ui.feeds.SwipeViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisFormOutcome
import de.velwebsolutions.oasis.network.ui.oases.OasisFormViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisViewModel
import de.velwebsolutions.oasis.network.ui.posts.PostDetailViewModel
import de.velwebsolutions.oasis.network.ui.profiles.ProfileAction
import de.velwebsolutions.oasis.network.ui.profiles.ProfileViewModel
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

/** The screens' view models: the Tropfen page, profiles, oases, search, Durchblättern and the Quelle. */
@RunWith(Parameterized::class)
class ScreenViewModelTest(private val answersAtOnce: Boolean) {
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

    @Test
    fun `leaves the Tropfen page after deleting it and hands the confirmation to the tab bar`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/posts/12", Fixtures.post),
            stub("DELETE", "network/posts/12", """{"message":"Dein Tropfen ist gelöscht."}""")
        )
        val model = models.keep(PostDetailViewModel(12, network.repository))
        model.load()
        val post = model.detail.value.valueOrNull!!.post
        model.perform(PostAction.Delete, post)
        assertTrue(model.leave.value)
        assertNull(model.status.value)
        assertEquals("Dein Tropfen ist gelöscht.", NetworkSignals.confirmation.value)
    }

    @Test
    fun `reacting to a reply on the Tropfen page changes only that reply`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/posts/12", Fixtures.post),
            stub("POST", "network/posts/23/reaction", """{"message":"Reaktion gesendet."}""")
        )
        val model = models.keep(PostDetailViewModel(12, network.repository))
        model.load()
        val reply = model.detail.value.valueOrNull!!.replies.first()
        model.perform(PostAction.React, reply)
        val detail = model.detail.value.valueOrNull!!
        assertEquals(true, detail.replies.first().viewer?.reacted)
        assertEquals(true, detail.post.viewer?.reacted)
        assertEquals("Reaktion gesendet.", model.status.value)
        assertFalse(model.leave.value)
    }

    @Test
    fun `a profile shows the neutral chat hint and its Tropfen`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/profiles/jonas", Fixtures.profile),
            stub("GET", "network/profiles/jonas/posts", pageJson(postJson(10)))
        )
        val model = models.keep(ProfileViewModel("Jonas", network.repository))
        model.load()
        val profile = model.profile.value.valueOrNull!!
        assertEquals("Jonas Weber nimmt gerade nur Nachrichten von bestimmten Personen an.", profile.chatHint)
        assertFalse(profile.canWrite)
        assertEquals(listOf(10), model.posts.posts.map { it.id })
    }

    @Test
    fun `following reloads the profile and blocking leaves it`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/profiles/jonas", Fixtures.profile),
            stub("GET", "network/profiles/jonas/posts", pageJson()),
            stub("DELETE", "network/profiles/jonas/follow", """{"message":"Du folgst Jonas Weber nicht mehr."}"""),
            stub("POST", "network/users/2/block", """{"message":"Jonas Weber ist blockiert."}""")
        )
        val model = models.keep(ProfileViewModel("jonas", network.repository))
        model.load()
        model.perform(ProfileAction.Unfollow)
        assertEquals("Du folgst Jonas Weber nicht mehr.", model.status.value)
        assertEquals(2, network.transport.requests.count { it.path == "network/profiles/jonas" })

        model.perform(ProfileAction.Block)
        assertTrue(model.leave.value)
        assertEquals("Jonas Weber ist blockiert.", NetworkSignals.confirmation.value)
    }

    @Test
    fun `an oasis loads its Tropfen and, for members, its member list`() = runTest {
        val detail = """
            {"data": {"oasis": {"id": 2, "slug": "stadtgarten", "name": "Stadtgarten", "description": "Gärtnern.", "color": "spring",
            "visibility": "public", "banner_url": null, "members_count": 4, "role": "member"}, "url": "http://localhost/oasen/stadtgarten",
            "house_rules": ["Freundlich bleiben."], "hosts": [], "is_member": true, "is_host": false, "can_follow": true, "can_post": true}}
        """.trimIndent()
        val members = """
            {"data": {"members": [{"id": 3, "username": "aylin", "display_name": "Aylin Demir", "initials": "AD", "avatar_color": "night",
            "photo_url": null, "can_write": true}], "hidden_members": 2, "own_list_audience": "contacts"}}
        """.trimIndent()
        val network = StubbedNetwork(
            stub("GET", "network/oases/stadtgarten", detail),
            stub("GET", "network/oases/stadtgarten/posts", pageJson(postJson(4))),
            stub("GET", "network/oases/stadtgarten/members", members),
            stub("DELETE", "network/oases/stadtgarten/follow", """{"message":"Du folgst der Oase Stadtgarten nicht mehr."}""")
        )
        val model = models.keep(OasisViewModel("stadtgarten", network.repository))
        model.load()
        assertEquals("Stadtgarten", model.detail.value.valueOrNull?.oasis?.name)
        assertEquals(listOf(4), model.posts.posts.map { it.id })
        assertEquals(true, model.members.value?.members?.first()?.canWrite)
        assertEquals(2, model.members.value?.hiddenMembers)

        val before = NetworkSignals.oases.value
        model.setFollowing(false)
        assertEquals("Du folgst der Oase Stadtgarten nicht mehr.", model.status.value)
        assertTrue(NetworkSignals.oases.value > before)
    }

    @Test
    fun `founding an oasis opens it`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/oases", Fixtures.oases),
            stub("POST", "network/oases", """{"data":{"slug":"ui-oase"}}""", 201)
        )
        val model = models.keep(OasisFormViewModel(null, network.repository))
        model.load()
        assertEquals(2, model.state.value.options?.visibilities?.size)
        model.updateInput { it.copy(name = "UI Oase", description = "Ein Test.", houseRules = "Freundlich bleiben.") }
        model.save()
        assertEquals(OasisFormOutcome.Open("ui-oase"), model.state.value.outcome)
        assertTrue(network.last("network/oases").body!!.contains(Regex("name=\"house_rules\"[\\s\\S]*?\r\n\r\nFreundlich bleiben.")))
    }

    @Test
    fun `a short search term forgets the results without asking the server`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/search", Fixtures.search))
        val model = models.keep(SearchViewModel("ga", network.repository))
        model.search()
        assertEquals(2, model.list.posts.size)
        assertEquals("ga", network.last("network/search").request.url.queryParameter("q"))

        model.updateQuery("g")
        model.search()
        assertTrue(model.list.posts.isEmpty())
        assertEquals(1, network.transport.requests.size)
        assertEquals("Du hast gerade viel gesucht. Warte bitte eine Minute.", SearchViewModel.message(ApiError.TooManyRequests(60)))
    }

    @Test
    fun `Durchblättern drops the cards of a muted person and keeps the end card`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/swipe", Fixtures.swipe),
            stub("POST", "network/users/2/mute", """{"message":"Die Person ist stummgeschaltet. Sie merkt nichts."}""")
        )
        val model = models.keep(SwipeViewModel(network.repository))
        model.load()
        val chapters = model.deck.value.valueOrNull!!.chapters
        val jonas = chapters.first().posts.first()
        model.perform(PostAction.Mute, jonas)
        val after = model.deck.value.valueOrNull!!
        assertTrue(after.chapters.none { chapter -> chapter.posts.any { it.author.id == 2 } })
        assertEquals("besprochen", after.chapters.first().key)
        assertEquals("Wohin mit einem Überschuss?", after.openVote?.title)
    }

    @Test
    fun `the Quelle puts an own new Tropfen on top but reloads for a reply`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/source", pageJson(postJson(3), context = """{"show_all":false,"follows_anyone":true,"can_post":true}"""))
        )
        val model = models.keep(SourceViewModel(network.repository, network.session))
        model.list.reload()

        NetworkSignals.didPublish(decodePost(postJson(8)), "Dein Tropfen ist da.")
        assertEquals(listOf(8, 3), model.list.posts.map { it.id })
        assertEquals(1, model.scrollToTop.value)
        val loads = network.transport.requests.size

        NetworkSignals.didPublish(decodePost(postJson(9, replyTo = 3)), "Deine Antwort ist da.")
        eventually { network.transport.requests.size == loads + 1 }
    }
}
