package de.velwebsolutions.oasis.chat.hilt

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.chat.ChatScreen
import de.velwebsolutions.oasis.chat.ChatTestSupport
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.member
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The module's entry point with its real Hilt view models (Robolectric, a test graph over the fake server): every route,
 * the gate before it, and the taps that lead elsewhere.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h2000dp")
class ChatScreenWiringTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val support = ChatTestSupport()
    private val navigator = Navigator()

    @Before
    fun graph() {
        ChatTestGraph.support = support
        hilt.inject()
    }

    private fun signIn(context: ChatContext) {
        ChatTestGraph.context = context
        ChatTestGraph.session.update(context.me)
    }

    private var shown: org.robolectric.android.controller.ActivityController<ChatTestActivity>? = null

    /** Shows [route] in a fresh activity; the previous one closes, like leaving a screen. */
    private fun show(route: ChatRoute) {
        shown?.pause()?.stop()?.destroy()
        val controller = Robolectric.buildActivity(ChatTestActivity::class.java).setup()
        shown = controller
        val activity = controller.get()
        activity.setContentView(
            ComposeView(activity).apply {
                setContent {
                    OasisTheme {
                        CompositionLocalProvider(LocalNavigator provides navigator) { ChatScreen(route) }
                    }
                }
            }
        )
    }

    private fun waitFor(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 20_000)

    @Test
    fun signedOutShowsOnlyTheFrame() {
        ChatTestGraph.context = support.context(1)
        runBlocking { ChatTestGraph.session.signOut() }
        show(ChatRoute.Conversations)
        compose.waitForIdle()
        assertEquals(SessionState.SignedOut, ChatTestGraph.session.state.value)
    }

    @Test
    fun aNewMemberSetsUpFirst() {
        signIn(support.context(1))
        show(ChatRoute.Conversations)
        waitFor("Chat einrichten")
    }

    @Test
    fun theListLeadsToEverySubpage() {
        val mira = support.member(1)
        signIn(mira)
        show(ChatRoute.Conversations)
        waitForTag("chat.new")
        compose.onNodeWithTag("chat.new").performClick()
        compose.onNodeWithTag("chat.settings").performClick()
        assertEquals(listOf(ChatRoute.NewConversation, ChatRoute.Settings), navigator.stack(navigator.selectedTab).toList())
        compose.onNodeWithTag("chat.lock").performClick()
        waitFor("Nachrichten entsperren")
        assertFalse(mira.vault.unlocked)
    }

    @Test
    fun everyRouteShowsItsScreen() {
        val mira = support.member(1)
        support.member(2)
        val id = runBlocking { mira.repository.createConversation("direct", null, listOf("2"), 7).id }.toInt()
        signIn(mira)
        for ((route, wait) in listOf(
            ChatRoute.Conversation(id) to "chat.conversation.title",
            ChatRoute.Details(id) to "chat.details.mute",
            ChatRoute.NewConversation to "chat.new.submit",
            ChatRoute.NewConversationWith("jonas") to "chat.new.submit",
            ChatRoute.Settings to "chat.backup.save",
            ChatRoute.Devices to "chat.backup.save"
        )) {
            show(route)
            waitForTag(wait)
        }
        compose.onNodeWithTag("chat.settings.hints").performClick()
        assertEquals(de.velwebsolutions.oasis.design.navigation.LiveRoute.Settings, navigator.stack(navigator.selectedTab).last())
    }
}
