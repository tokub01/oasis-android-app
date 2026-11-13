package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.PostCard
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.metaParts
import de.velwebsolutions.oasis.network.ui.posts.CarryScreen
import de.velwebsolutions.oasis.network.ui.posts.CarryViewModel
import de.velwebsolutions.oasis.network.ui.posts.PLAIN_CARRY_LEAD
import de.velwebsolutions.oasis.network.ui.posts.QUOTE_CARRY_LEAD
import de.velwebsolutions.oasis.network.ui.posts.plainCarryNote
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round N on the cards: „Antworten“ only where the viewer may answer (else the server's hint), Weitertragen without own
 * words, the plain carry's own head and one meta line pattern in every list.
 */
@RunWith(RobolectricTestRunner::class)
class ReplyRightsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val navigator = Navigator()
    private val models = ViewModelKeeper()
    private val client = StubbedNetwork().transport.client
    private var screen by mutableStateOf<@Composable () -> Unit>({})

    @After
    fun clear() = models.clear()

    private fun show(content: @Composable () -> Unit) {
        screen = content
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(LocalNavigator provides navigator, LocalApiClient provides client) { screen() }
            }
        }
    }

    private val base = decodePost(postJson(7))
    private val viewer = base.viewer!!

    @Test
    fun `without the right to answer the card shows the server's hint instead of Antworten`() {
        val hint = "Nur Menschen, denen U 2 folgt, können antworten"
        val post = base.copy(viewer = viewer.copy(canReply = false, canQuote = false, replyHint = hint))
        show { NetworkPage { block { PostCard(post, onAction = { _, _ -> }) } } }
        compose.onNodeWithText(hint).assertExists()
        compose.onNodeWithTag("post.action.Antworten").assertDoesNotExist()
        compose.onNodeWithTag("post.action.Weitertragen").performClick()
        assertEquals(NetworkRoute.CarryPost(7, quote = false), navigator.stack(MainTab.Source).last())
    }

    @Test
    fun `with the right to answer there is no hint`() {
        show {
            NetworkPage {
                block { PostCard(base.copy(viewer = viewer.copy(replyHint = "Antworten sind ausgeschaltet")), onAction = { _, _ -> }) }
            }
        }
        compose.onNodeWithTag("post.action.Antworten").assertExists()
        compose.onNodeWithTag("post.replyHint").assertDoesNotExist()
    }

    @Test
    fun `a plain carry shows who carried it and the carried Tropfen as the card`() {
        val original = Post.Carried.Original(3, base.author.copy(id = 3, username = "lea", displayName = "Lea"), "Das Beet blüht.")
        val carry = base.copy(body = "", segments = emptyList(), carried = Post.Carried(Post.Carried.State.Visible, original))
        assertTrue(carry.isPlainCarry)
        show { NetworkPage { block { PostCard(carry, onAction = { _, _ -> }) } } }
        compose.onNodeWithText("U 2 hat das weitergetragen").assertExists()
        compose.onNodeWithTag("post.open").assertDoesNotExist()
        compose.onNodeWithContentDescription("Weitere Aktionen", substring = true).assertExists()
        compose.onNodeWithTag("post.carrier").performClick()
        assertEquals(NetworkRoute.Profile("u2"), navigator.stack(MainTab.Source).last())
    }

    @Test
    fun `the meta line has dots only between the parts that are there`() {
        val oasis = Post.OasisRef("garten", "Stadtgarten", "spring")
        val full = base.copy(oasis = oasis, editedAt = Instant.parse("2026-10-22T11:00:00Z"), visibility = "members")
        val opened = mutableListOf<NetworkRoute>()
        val parts = metaParts(full, isDetail = false) { opened += it }
        assertEquals(listOf("@u2", "Stadtgarten", parts[2].text, "bearbeitet", "nur Mitglieder"), parts.map { it.text })
        assertEquals(
            listOf(null, "in Stadtgarten", "Zum Post, ${parts[2].text}", null, "nur für Mitglieder"),
            parts.map {
                it.description
            }
        )
        parts.mapNotNull { it.onClick }.forEach { it() }
        assertEquals(listOf(NetworkRoute.Oasis("garten"), NetworkRoute.Post(7)), opened)
        assertEquals(listOf("@u2", "22. Oktober 2026, 12:00"), metaParts(base, isDetail = true) {}.map { it.text })

        show { NetworkPage { block { PostCard(full, onAction = { _, _ -> }) } } }
        // The dots are drawn but screen readers skip them (the parts read as a sentence with commas).
        assertEquals(0, compose.onAllNodesWithText("·").fetchSemanticsNodes().size)
        compose.onNodeWithText("@u2 ·", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Stadtgarten").performClick()
        assertEquals(NetworkRoute.Oasis("garten"), navigator.stack(MainTab.Source).last())
    }

    @Test
    fun `Weitertragen without the right to answer offers no text field`() {
        val carrySource = """{"data":{"id":12,"author":{"id":4,"username":"lukas","display_name":"Lukas Brandt","initials":"LB",
            |"avatar_color":"dune","photo_url":null},"body":"Samstag Werkstatt.","segments":[],"links":[],"can_quote":false}}
        """.trimMargin()
        val network = StubbedNetwork(stub("GET", "network/composer", Fixtures.composer), stub("GET", "network/posts/12/carry", carrySource))
        val composer = models.keep(network.composer(carried = 12))
        val model = models.keep(CarryViewModel(12, network.repository))
        show { CarryScreen(12, quote = true, viewModel = model, composer = composer) }
        compose.settle { compose.onAllNodes(hasText(PLAIN_CARRY_LEAD)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(plainCarryNote("Lukas Brandt")).assertExists()
        compose.onNodeWithTag("compose.body").assertDoesNotExist()
        assertTrue(composer.state.value.plainCarry)
    }

    @Test
    fun `Weitertragen with the right to answer keeps the optional words`() {
        val carrySource = """{"data":{"id":12,"author":{"id":4,"username":"lukas","display_name":"Lukas Brandt","initials":"LB",
            |"avatar_color":"dune","photo_url":null},"body":"Samstag Werkstatt.","segments":[],"links":[]}}
        """.trimMargin()
        val network = StubbedNetwork(stub("GET", "network/composer", Fixtures.composer), stub("GET", "network/posts/12/carry", carrySource))
        val composer = models.keep(network.composer(carried = 12))
        val model = models.keep(CarryViewModel(12, network.repository))
        show { CarryScreen(12, viewModel = model, composer = composer) }
        compose.settle { compose.onAllNodes(hasText(QUOTE_CARRY_LEAD)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("compose.body").assertExists()
    }
}
