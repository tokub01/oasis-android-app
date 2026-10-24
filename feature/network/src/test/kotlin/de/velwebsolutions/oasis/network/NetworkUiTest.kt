package de.velwebsolutions.oasis.network

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.ScreenChrome
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.Profile
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.PostListState
import de.velwebsolutions.oasis.network.ui.components.ComposeFab
import de.velwebsolutions.oasis.network.ui.components.FeedHandlers
import de.velwebsolutions.oasis.network.ui.components.LeaveGuard
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.compose.ComposeViewModel
import de.velwebsolutions.oasis.network.ui.compose.callbacks
import de.velwebsolutions.oasis.network.ui.feeds.sourceContent
import de.velwebsolutions.oasis.network.ui.posts.ComposeContent
import de.velwebsolutions.oasis.network.ui.profiles.ProfileCallbacks
import de.velwebsolutions.oasis.network.ui.profiles.ProfileConfirmation
import de.velwebsolutions.oasis.network.ui.profiles.profileContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The core flows on screen (iOS `NetworkUITests`): the Quelle, writing a Tropfen and a profile. */
@RunWith(RobolectricTestRunner::class)
class NetworkUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val navigator = Navigator()
    private val models = ViewModelKeeper()

    @After
    fun clear() {
        models.clear()
        NetworkSignals.confirmation.value = null
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator, content = content) }
        }
    }

    private fun decodeProfile(): Profile =
        OasisJson.snake.decodeFromString(DataEnvelope.serializer(Profile.serializer()), Fixtures.profile).data

    private val noFeed = FeedHandlers(perform = { _, _ -> }, loadMore = {}, reload = {})

    @Test
    fun `the Quelle shows the feed, its end and switches to Ganz Oasis`() {
        val page = OasisJson.snake.decodeFromString(FeedPage.serializer(SourceContext.serializer()), Fixtures.source)
        val state = PostListState(posts = page.data, context = page.context, state = Loadable.Loaded(Unit))
        var view: String? = null
        val actions = mutableListOf<Pair<PostAction, Int>>()
        val handlers = FeedHandlers(perform = { action, post -> actions += action to post.id }, loadMore = {}, reload = {})
        show { NetworkPage { sourceContent(state, "Mira Sol", handlers, onSelectView = { view = it }) } }

        compose.onNodeWithText("Hallo Mira Sol.").assertIsDisplayed()
        compose.onNodeWithTag("source.composeEntry").performClick()
        assertEquals(NetworkRoute.Compose(), navigator.sheet)
        navigator.dismissSheet()

        compose.onNodeWithText("Ganz Oasis").performClick()
        assertEquals("alle", view)

        val react = hasTestTag("post.react") and hasAnyAncestor(hasTestTag("post.card.12"))
        compose.onNodeWithTag("network.page").performScrollToNode(react)
        compose.onNode(react).performClick()
        assertEquals(listOf(PostAction.Unreact to 12), actions)

        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("feed.end"))
        compose.onNodeWithText("Du bist auf dem neuesten Stand.").assertIsDisplayed()
        compose.onNodeWithTag("network.page").performScrollToNode(hasText("Deine Woche"))
    }

    @Test
    fun `writes a Tropfen and closes the composer`() {
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/tone", """{"data":{"hint":null,"nudge":false}}"""),
            stub("POST", "network/posts", """{"message":"Dein Tropfen ist da.","suspended":false,"data":${postJson(77)}}""", 201)
        )
        val model = models.keep(ComposeViewModel("ui-test", null, null, network.repository, network.common, network.session))
        runBlocking { model.load() }
        var published = false
        show {
            val state by model.state.collectAsState()
            ComposeContent(state, model.callbacks(), onPublished = { published = true })
        }

        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("compose.body"))).performTextInput("UI-Test Tropfen")
        compose.onNodeWithText("15/500").assertIsDisplayed()
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("compose.submit"))
        compose.onNodeWithText("Öffentlich heißt: auch ohne Konto lesbar.").assertIsDisplayed()
        compose.onNodeWithTag("compose.submit").performClick()
        compose.waitUntil(5_000) { published }

        assertTrue(network.last("network/posts").body!!.contains("UI-Test Tropfen"))
        assertEquals("Dein Tropfen ist da.", NetworkSignals.confirmation.value)
        assertEquals("", model.state.value.body)
    }

    @Test
    fun `a profile shows the neutral chat hint, follows and asks before unfollowing`() {
        val profile = decodeProfile()
        val posts = PostListState<NoContext>(posts = emptyList<Post>(), state = Loadable.Loaded(Unit))
        val asked = mutableListOf<ProfileConfirmation>()
        show { NetworkPage { profileContent(profile, posts, noFeed, ProfileCallbacks(perform = {}, confirm = { asked += it })) } }

        compose.onNodeWithText("Jonas Weber nimmt gerade nur Nachrichten von bestimmten Personen an.").assertIsDisplayed()
        compose.onNodeWithText("Schreiben").assertDoesNotExist()
        compose.onNodeWithText("Karawane: 3").assertIsDisplayed()
        compose.onNodeWithTag("profile.unfollow").performClick()
        assertEquals(listOf(ProfileConfirmation.Unfollow), asked)
    }

    @Test
    fun `Schreiben on a profile starts a chat with that person`() {
        val profile = decodeProfile().copy(canWrite = true, chatHint = null)
        val posts = PostListState<NoContext>(state = Loadable.Loaded(Unit))
        show { NetworkPage { profileContent(profile, posts, noFeed, ProfileCallbacks(perform = {}, confirm = {})) } }

        compose.onNodeWithTag("profile.chatHint").assertDoesNotExist()
        compose.onNodeWithTag("profile.write").performClick()
        assertEquals(ChatRoute.NewConversationWith("jonas"), navigator.stack(MainTab.Source).last())
    }

    @Test
    fun `the back arrow and back gesture ask before a changed text is lost`() {
        var left = false
        var asked = 0
        var changed by mutableStateOf(true)
        show {
            CompositionLocalProvider(LocalScreenChrome provides ScreenChrome(onBack = { left = true })) {
                LeaveGuard(enabled = changed, onAttempt = { asked++ }) { OasisScreen(title = "Bearbeiten") {} }
            }
        }

        compose.onNodeWithContentDescription("Zurück").performClick()
        assertEquals(1, asked)
        assertFalse(left)

        changed = false
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Zurück").performClick()
        assertTrue(left)
        assertEquals(1, asked)
    }

    @Test
    fun `the floating button of an oasis says where the Tropfen goes`() {
        var opened = false
        show {
            Box(Modifier.fillMaxSize()) {
                ComposeFab(rememberLazyListState(), onClick = { opened = true }, description = "Tropfen in Garten schreiben")
            }
        }
        compose.onNodeWithContentDescription("Tropfen in Garten schreiben").performClick()
        assertTrue(opened)
    }
}
