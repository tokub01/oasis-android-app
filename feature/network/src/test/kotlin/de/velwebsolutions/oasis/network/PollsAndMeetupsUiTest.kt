package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.ui.PostListState
import de.velwebsolutions.oasis.network.ui.components.FeedHandlers
import de.velwebsolutions.oasis.network.ui.components.LocalPollActions
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.PollActions
import de.velwebsolutions.oasis.network.ui.components.PostCard
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.compose.callbacks
import de.velwebsolutions.oasis.network.ui.feeds.SeenCut
import de.velwebsolutions.oasis.network.ui.feeds.SourceExtras
import de.velwebsolutions.oasis.network.ui.feeds.sourceContent
import de.velwebsolutions.oasis.network.ui.oases.OasisDetailScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisViewModel
import de.velwebsolutions.oasis.network.ui.posts.ComposeContent
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The poll card, the composer's poll, the meetups with their form, the pate card and „Nächste Treffen“ at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class PollsAndMeetupsUiTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val compose = createComposeRule()

    private val navigator = Navigator()
    private val models = ViewModelKeeper()

    @After
    fun clear() = models.clear()

    private fun show(content: @Composable () -> Unit) =
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator) { content() } } }

    /** A tap through the semantics: nodes inside one tall list item may lie outside the screen. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/network-$name.png")
    }

    @Test
    fun thePollCardVotesAndEnds() {
        val calls = mutableListOf<String>()
        val polls = PollActions(vote = { post, index -> calls += "vote ${post.id} $index" }, close = { calls += "close ${it.id}" })
        show {
            NetworkPage {
                block("a") {
                    CompositionLocalProvider(LocalPollActions provides polls) {
                        PostCard(decodePost(pollPostJson(3, mine = "[0]", counts = "[3,1]")), onAction = { _, _ -> })
                    }
                }
                block("b") { PostCard(decodePost(pollPostJson(4, closed = true)), onAction = { _, _ -> }) }
            }
        }
        compose.onNodeWithText("3 Stimmen").assertExists()
        capture("poll-card")
        compose.onNodeWithTag("post.poll.3").performScrollTo()
        compose.onAllNodes(hasTestTag("post.poll.answer.1"))[0].performClick()
        compose.onNodeWithTag("post.poll.close").performClick()
        compose.onNodeWithText("Danach kann niemand mehr abstimmen.").assertExists()
        compose.onNodeWithText("Beenden").performClick()
        compose.waitForIdle()
        assertEquals(listOf("vote 3 1", "close 3"), calls)
    }

    @Test
    fun theComposerAddsAPoll() {
        val network = StubbedNetwork(stub("GET", "network/composer", Fixtures.composer))
        val model = models.keep(network.composer("garten"))
        show {
            val state by model.state.collectAsState()
            ComposeContent(state, model.callbacks(), onPublished = {})
        }
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("compose.poll.add"))
        compose.onNodeWithTag("compose.poll.add").performClick()
        fun field(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
        field("compose.poll.question").performScrollTo().performTextInput("Wann wandern wir?")
        field("compose.poll.option.0").performScrollTo().performTextInput("Samstag")
        field("compose.poll.option.1").performScrollTo().performTextInput("Sonntag")
        // Up to six answers, then „Antwort hinzufügen“ goes; „Entfernen“ down to two.
        repeat(4) {
            compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("compose.poll.addOption"))
            compose.onNodeWithTag("compose.poll.addOption").performClick()
        }
        compose.onNodeWithTag("compose.poll.addOption").assertDoesNotExist()
        repeat(4) {
            compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("compose.poll.remove.2"))
            compose.onNodeWithTag("compose.poll.remove.2").performClick()
        }
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("compose.poll.multiple"))
        compose.onNodeWithTag("compose.poll.multiple").performClick()
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("compose.poll.duration"))
        compose.onNodeWithTag("compose.poll.duration").performClick()
        compose.onNodeWithText("3 Tage").performClick()
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("compose.poll"))
        capture("compose-poll")
        val poll = model.state.value.poll
        assertEquals("Wann wandern wir?", poll?.question)
        assertEquals(listOf("Samstag", "Sonntag"), poll?.options)
        assertEquals("3d", poll?.duration)
        assertTrue(poll?.multiple == true)
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("compose.poll.duration"))
        compose.onNodeWithTag("compose.poll.duration").performClick()
        compose.onNodeWithText("ohne Ende").performClick()
        compose.waitForIdle()
        assertEquals(null, model.state.value.poll?.duration)
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("compose.poll.remove"))
        compose.onNodeWithTag("compose.poll.remove").performClick()
        compose.waitForIdle()
        assertEquals(null, model.state.value.poll)
    }

    @Test
    fun theOasisWithItsMeetupsFormPateAndVolunteers() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson(member = true, pate = true)),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("GET", "network/oases/garten/members", """{"data":{"members":[]}}"""),
            stub("GET", "network/oases/garten/meetups", meetupsJson()),
            stub(
                "POST",
                "network/meetups/5/answer",
                """{"message":"Deine Antwort: Vielleicht","data":${meetupJson(5, answer = "maybe")}}"""
            ),
            stub("DELETE", "network/meetups/5", """{"message":"Das Treffen ist abgesagt.","data":${meetupJson(5, cancelled = true)}}"""),
            stub("PUT", "network/oases/garten/meetup-settings", """{"data":{"members_create_meetups":true}}"""),
            stub("PUT", "network/oases/garten/welcome", """{"data":{"volunteer":true}}""")
        )
        val model = models.keep(OasisViewModel("garten", network.repository))
        show { OasisDetailScreen("garten", model, atMeetups = true) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("oasis.meetups")).fetchSemanticsNodes().isNotEmpty() }
        capture("meetups")
        compose.onNodeWithTag("meetup.answer.maybe").tap()
        compose.waitUntil(5_000) { model.meetups.value?.upcoming?.first()?.myAnswer == "maybe" }
        compose.onAllNodes(hasText("Wer geantwortet hat"))[0].tap()
        compose.onNodeWithText("Jonas Weber · Ich komme").assertExists()
        compose.onAllNodes(hasText("Wer geantwortet hat"))[0].tap()
        compose.onNodeWithText("Vergangene Treffen").tap()
        compose.onNodeWithText("Vergangene Treffen").tap()
        compose.onNodeWithTag("meetup.allowMembers").tap()
        compose.waitUntil(5_000) { model.meetups.value?.membersCreateMeetups == true }

        compose.onNodeWithTag("meetup.create").tap()
        compose.onNodeWithText("Neues Treffen").performScrollTo()
        capture("meetup-form")
        compose.onNodeWithTag("meetup.close").tap()
        compose.onAllNodes(hasTestTag("meetup.edit"))[0].tap()
        compose.onNodeWithText("Treffen ändern").assertExists()
        compose.onNodeWithTag("meetup.close").tap()

        compose.onAllNodes(hasTestTag("meetup.cancel"))[0].tap()
        compose.onNodeWithText("Absagen").performClick()
        compose.waitUntil(5_000) { model.meetups.value?.upcoming?.first()?.cancelled == true }

        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("oasis.volunteer"))
        compose.onNodeWithTag("oasis.volunteer").tap()
        compose.waitUntil(5_000) { model.detail.value.valueOrNull?.welcome?.volunteer == true }
        compose.onNodeWithText("Freiwillige").assertExists()

        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("oasis.pate"))
        compose.onNodeWithText("Deine Willkommenspatin/dein Willkommenspate: Aylin Demir").assertExists()
        capture("pate")
        compose.onNodeWithTag("oasis.pate.message").performClick()
        assertEquals(ChatRoute.NewConversationWith("aylin"), navigator.stack(navigator.selectedTab).last())
    }

    @Test
    fun anOasisWithoutMeetupsAndAFormThatFailed() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson(member = false, pate = false)),
            stub("GET", "network/oases/garten/posts", pageJson())
        )
        val model = models.keep(OasisViewModel("garten", network.repository))
        val calls = mutableListOf<String>()
        val actions = de.velwebsolutions.oasis.network.ui.oases.OasisMeetupActions(
            {},
            {},
            {},
            {},
            { _, _ -> },
            {},
            {},
            {}
        )
        show {
            androidx.compose.foundation.layout.Column {
                de.velwebsolutions.oasis.network.ui.oases.MeetupsSection(
                    de.velwebsolutions.oasis.network.api.OasisMeetups(
                        upcoming = listOf(
                            decodeMeetup(meetupJson(7)).copy(people = emptyList(), place = null, link = null, description = null)
                        )
                    ),
                    de.velwebsolutions.oasis.network.ui.oases.MeetupForm(
                        startsAt = java.time.Instant.parse("2099-01-01T17:00:00Z"),
                        error = de.velwebsolutions.oasis.core.network.ApiError.Server(500, null)
                    ),
                    isHost = false,
                    actions = actions
                )
                de.velwebsolutions.oasis.network.ui.oases.MeetupsSection(
                    de.velwebsolutions.oasis.network.api.OasisMeetups(),
                    null,
                    isHost = false,
                    actions = actions
                )
                OasisDetailScreen("garten", model)
            }
        }
        compose.onNodeWithText("Gerade ist kein Treffen geplant.").assertExists()
        compose.onNodeWithText("Treffen ändern").assertDoesNotExist()
        compose.onNodeWithText("Neues Treffen").assertExists()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun nextMeetupsOnTheQuelle() {
        val state = PostListState(posts = emptyList(), context = SourceContext(), state = Loadable.Loaded(Unit))
        val extras = SourceExtras(emptyList(), {}, {}, listOf(decodeMeetup(meetupJson(5, answer = "yes")), decodeMeetup(meetupJson(6))))
        val handlers = FeedHandlers(perform = { _, _ -> }, loadMore = {}, reload = {})
        show { NetworkPage { sourceContent(SeenCut(state, false), "Mira", handlers, onSelectView = {}, extras = extras) } }
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("source.meetups"))
        compose.onNodeWithText("Deine Antwort: Ich komme").assertExists()
        compose.onAllNodes(hasText("Gießen und Kuchen"))[0].performClick()
        assertEquals(NetworkRoute.Oasis("garten", meetups = true), navigator.stack(navigator.selectedTab).last())
    }
}
