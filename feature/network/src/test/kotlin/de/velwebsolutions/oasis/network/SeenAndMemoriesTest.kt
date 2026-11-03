package de.velwebsolutions.oasis.network

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.api.MemoryYear
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.ui.PostListState
import de.velwebsolutions.oasis.network.ui.components.FeedHandlers
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.feeds.SeenCut
import de.velwebsolutions.oasis.network.ui.feeds.SourceExtras
import de.velwebsolutions.oasis.network.ui.feeds.SourceViewModel
import de.velwebsolutions.oasis.network.ui.feeds.sourceContent
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * „Alles Neue gesehen“ and „Heute vor einem Jahr“ on the Quelle (docs/oasis-network.md): only what is new until
 * „Ältere Tropfen“, and the member's own memories with „Ausblenden“.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class SeenAndMemoriesTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val compose = createComposeRule()

    private val models = ViewModelKeeper()

    @After
    fun clear() = models.clear()

    private val memoriesJson = """[{"years_ago":1,"label":"Heute vor 1 Jahr","posts":[${postJson(4, author = 1)}]}]"""

    private fun state(vararg ids: Int, seen: String?, next: String? = null) = PostListState(
        posts = ids.map { decodePost(postJson(it)) },
        context = SourceContext(seenUpTo = seen),
        state = Loadable.Loaded(Unit),
        nextCursor = next
    )

    @Test
    fun onlyWhatIsNewUntilAeltereTropfen() {
        // The first visit, everything still new, or after „Ältere Tropfen“: the list as it is.
        assertFalse(SeenCut.of(state(9, 8, seen = null), showOlder = false).seen)
        assertFalse(SeenCut.of(state(9, 8, seen = "7", next = "c"), showOlder = false).seen)
        assertFalse(SeenCut.of(state(9, 5, seen = "7"), showOlder = true).seen)
        assertFalse(SeenCut.of(state(9, 5, seen = "kaputt"), showOlder = false).seen)

        val cut = SeenCut.of(state(9, 7, 5, seen = "7"), showOlder = false)
        assertTrue(cut.seen)
        assertEquals(listOf(9), cut.state.posts.map { it.id })
        assertFalse("no honest end before the older ones", cut.state.reachedEnd)
        assertEquals("c", SeenCut.of(state(9, 5, seen = "7", next = "c"), showOlder = false).state.nextCursor)
    }

    @Test
    fun theQuelleKeepsItsChoicePerViewAndLoadsMemories() {
        val network = StubbedNetwork(
            stub("GET", "network/source", pageJson(postJson(3), context = """{"show_all":false,"seen_up_to":"2"}""")),
            stub("GET", "network/memories", """{"data":$memoriesJson}"""),
            stub("POST", "network/memories/dismiss", """{"message":"Bis morgen ausgeblendet."}""")
        )
        val model = models.keep(SourceViewModel(network.repository, network.session))
        model.showOlderPosts()
        assertTrue(model.showOlder.value)
        model.selectView("alle")
        assertFalse("each view has its own marker", model.showOlder.value)

        runBlocking { model.loadMemories() }
        assertEquals(listOf("Heute vor 1 Jahr"), model.memories.value.map { it.label })
        runBlocking { model.dismissMemories() }
        assertTrue(model.memories.value.isEmpty())
        assertEquals("Bis morgen ausgeblendet.", model.list.state.value.status)
    }

    @Test
    fun aFailedLoadOrDismissKeepsWhatIsThere() {
        val network = StubbedNetwork(
            stub("GET", "network/source", pageJson(postJson(3))),
            stub("GET", "network/memories", """{"message":"kaputt"}""", status = 500),
            stub("POST", "network/memories/dismiss", """{"message":"kaputt"}""", status = 500)
        )
        val model = models.keep(SourceViewModel(network.repository, network.session))
        runBlocking { model.loadMemories() }
        assertTrue(model.memories.value.isEmpty())
        runBlocking { model.dismissMemories() }
        assertTrue(model.list.state.value.actionError != null)
    }

    @Test
    fun theCardsOnTheQuelle() {
        val memories = OasisJson.snake.decodeFromString(ListSerializer(MemoryYear.serializer()), memoriesJson)
        val calls = mutableListOf<String>()
        val handlers = FeedHandlers(perform = { _, _ -> }, loadMore = { calls += "more" }, reload = {})
        val extras = SourceExtras(memories, { calls += "dismiss" }, { calls += "older" })
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(LocalNavigator provides Navigator()) {
                    NetworkPage {
                        sourceContent(SeenCut.of(state(9, 5, seen = "7"), false), "Mira", handlers, onSelectView = {}, extras = extras)
                    }
                }
            }
        }
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("source.memories"))
        compose.onNodeWithText("Erinnerungen").assertExists()
        compose.onNodeWithText("Heute vor 1 Jahr").assertExists()
        capture("memories")
        compose.onNodeWithTag("source.memories.dismiss").performClick()
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("source.seen"))
        compose.onNodeWithText("Du hast alles Neue gesehen.").assertExists()
        capture("seen")
        compose.onNodeWithTag("source.older").performClick()
        compose.waitForIdle()
        assertEquals(listOf("dismiss", "older"), calls.filter { it != "more" })
        assertFalse("older Tropfen wait for the button", "more" in calls)
    }

    @Test
    fun allSeenShowsNoEmptyNote() {
        val handlers = FeedHandlers(perform = { _, _ -> }, loadMore = {}, reload = {})
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(LocalNavigator provides Navigator()) {
                    NetworkPage { sourceContent(SeenCut.of(state(5, seen = "7"), false), "Mira", handlers, onSelectView = {}) }
                }
            }
        }
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("source.seen"))
        compose.onNodeWithText("Noch keine Tropfen.", substring = true).assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/source-$name.png")
    }
}
