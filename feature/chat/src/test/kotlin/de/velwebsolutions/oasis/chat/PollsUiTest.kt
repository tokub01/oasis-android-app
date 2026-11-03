package de.velwebsolutions.oasis.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationState
import de.velwebsolutions.oasis.chat.ui.PinBar
import de.velwebsolutions.oasis.chat.ui.PollAnswer
import de.velwebsolutions.oasis.chat.ui.PollForm
import de.velwebsolutions.oasis.chat.ui.PollFormActions
import de.velwebsolutions.oasis.chat.ui.PollView
import de.velwebsolutions.oasis.chat.ui.ReactionActions
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pin bar, the poll form and the poll bubble at 360 dp, with a screenshot of each. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class PollsUiTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(ConversationState())
    private var target: Int? = 0

    private val members = listOf(ConversationMember("1", "mira", "Mira"), ConversationMember("2", "jonas", "Jonas"))
    private val conversation = ChatConversation("7", "direct", null, "1", 7, 1, false, members, "")
    private val envelope = MessageEnvelope(1, "7", 1, "2", "n", "c", "s1", "2100-01-01T00:00:00Z")

    private fun item(signature: String, sender: String, text: String) = ChatMessageItem(
        signature,
        envelope.copy(signature = signature),
        sender,
        Instant.parse("2100-01-01T00:00:00Z"),
        ChatMessageItem.State.Readable,
        text,
        MessageContent(text)
    )

    private val reactions = ReactionActions(
        onReact = { _, _ -> },
        onReply = {},
        onCancelReply = {},
        onQuote = {
            calls += "jump $it"
            target
        },
        onPin = { calls += "pin ${it.signature}" },
        onVote = { item, index -> calls += "vote ${item.signature} $index" },
        onClosePoll = { calls += "close ${it.signature}" }
    )

    private val polls = PollFormActions(
        onOpen = { calls += "open" },
        onEdit = { change -> state = state.copy(pollForm = state.pollForm?.let(change)) },
        onCancel = { calls += "cancel" },
        onSend = { calls += "send" }
    )

    @Composable
    private fun Chat() = ConversationContent(
        state = state,
        myId = "1",
        senderName = { if (it == "2") "Jonas" else "Mira" },
        onLoadOlder = {},
        onDraftChange = {},
        onSend = {},
        onReport = {},
        onAccept = {},
        onDecline = {},
        onBlockFlagged = {},
        onDismissFlagged = {},
        onNoticeSpoken = {},
        attachments = attachmentActions(calls),
        reactions = reactions,
        polls = polls
    )

    private fun show() = compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides Navigator()) { Chat() } } }

    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/chat-polls-$name.png")
    }

    private fun poll(closed: Boolean = false) = PollView(
        "Wann wandern wir?",
        listOf(PollAnswer("Samstag", 2, true, "Samstag: Jonas, Du"), PollAnswer("Sonntag", 1, false, "Sonntag: Jonas")),
        multiple = true,
        closed = closed,
        open = !closed,
        canClose = !closed
    )

    private fun shown(): ConversationState {
        val meeting = item("a", "2", "Treffpunkt: Bahnhof, 9 Uhr")
        val question = item("p", "1", "Wann wandern wir?")
        return ConversationState(
            conversation = conversation,
            timeline = ChatTimeline(listOf(meeting, question)),
            canSend = true,
            canPin = true,
            pinBar = PinBar("a", "Treffpunkt: Bahnhof, 9 Uhr", false),
            polls = mapOf("p" to poll())
        )
    }

    @Test
    fun aPollOfAChatOneMayNotWriteToTakesNoVotes() {
        val poll = item("p", "1", "Wann?").let {
            it.copy(
                content = MessageContent("Wann?", poll = de.velwebsolutions.oasis.chatcrypto.MessagePoll("Wann?", listOf("a", "b"), false))
            )
        }
        val views = de.velwebsolutions.oasis.chat.ui.TimelineViews("1", { "Jonas" }, Instant.parse("2026-11-03T00:00:00Z"))
        val shown = views.polls(listOf(poll), canWrite = false).getValue("p")
        assertEquals(false, shown.open)
        assertEquals(false, shown.canClose)
        assertEquals(listOf(1), shown.choosing(1))
    }

    @Test
    fun anhaftenOrLoesenOnlyForThoseWhoMayPin() {
        assertEquals("Lösen", shown().pinLabel("a"))
        assertEquals("Anheften", shown().pinLabel("p"))
        assertEquals(null, shown().copy(canPin = false).pinLabel("a"))
    }

    @Test
    fun thePinBarAndThePollBubble() {
        state = shown()
        show()
        capture("pin-and-poll")
        compose.onNodeWithTag("chat.pin").performClick()
        target = null
        compose.onNodeWithTag("chat.pin").performClick()
        compose.onNodeWithTag("chat.poll.answer.0").performScrollTo().assertIsSelected().performClick()
        compose.onNodeWithContentDescription("1 Stimme für Sonntag").assertExists()
        compose.onNodeWithTag("chat.poll.count.1").performClick()
        compose.onNodeWithText("Sonntag: Jonas").assertExists()
        compose.onNodeWithText("Mehrere Antworten möglich").assertExists()
        compose.onNodeWithTag("chat.poll.close").performScrollTo().performClick()
        compose.onNodeWithText("Umfrage beenden? Danach kann niemand mehr abstimmen.").assertExists()
        compose.onNodeWithText("Beenden").performClick()
        compose.waitForIdle()

        // Pinning from the reaction bar: „Lösen“ for the pinned message.
        compose.onAllNodes(hasText("Jonas"), useUnmergedTree = true)[0].performTouchInput { longClick() }
        compose.onNodeWithText("Lösen").performClick()
        compose.waitForIdle()
        assertEquals(listOf("jump a", "jump a", "vote p 0", "close p", "pin a"), calls)

        // Ended, and an expired pin: nothing to tap any more.
        state = state.copy(polls = mapOf("p" to poll(closed = true)), pinBar = PinBar("a", "Foto", true))
        compose.onNodeWithText("Nachricht nicht mehr verfügbar").assertExists()
        compose.onNodeWithTag("chat.pin").assertIsNotEnabled()
        compose.onNodeWithText("Mehrere Antworten möglich · Beendet").assertExists()
        compose.onNodeWithTag("chat.poll.answer.1").assertIsNotEnabled()
        compose.onNodeWithTag("chat.poll.close").assertDoesNotExist()
    }

    @Test
    fun theFormFromThePlusMenu() {
        state = shown().copy(pinBar = null)
        show()
        compose.onNodeWithTag("chat.composer.add").performClick()
        compose.onNodeWithTag("chat.composer.poll").performClick()
        assertEquals(listOf("open"), calls)

        state = state.copy(pollForm = PollForm())
        fun field(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
        field("chat.poll.question").performTextInput("Wann wandern wir?")
        field("chat.poll.option.0").performTextInput("Samstag")
        field("chat.poll.option.1").performTextInput("Sonntag")
        compose.onNodeWithTag("chat.poll.add").performScrollTo().performClick()
        compose.onNodeWithTag("chat.poll.remove.2").performScrollTo().performClick()
        compose.onNodeWithTag("chat.poll.multiple").performScrollTo().performClick()
        state = state.copy(pollForm = state.pollForm?.copy(error = "Eine Umfrage braucht mindestens zwei Antworten."))
        capture("form")
        assertEquals(
            PollForm("Wann wandern wir?", listOf("Samstag", "Sonntag"), true, "Eine Umfrage braucht mindestens zwei Antworten."),
            state.pollForm
        )
        compose.onNodeWithText("Eine Umfrage braucht mindestens zwei Antworten.").assertExists()
        compose.onNodeWithTag("chat.poll.send").performScrollTo().performClick()
        compose.onNodeWithTag("chat.poll.cancel").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("open", "send", "cancel"), calls)
        assertTrue(compose.onAllNodes(hasTestTag("chat.poll.remove.0")).fetchSemanticsNodes().isEmpty())
        // Six answers: no more to add.
        state = state.copy(pollForm = PollForm("Wann?", List(6) { "Tag ${it + 1}" }))
        compose.onNodeWithTag("chat.poll.add").assertDoesNotExist()
        compose.onNodeWithTag("chat.poll.remove.5").assertExists()
    }
}
