package de.velwebsolutions.oasis.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationState
import de.velwebsolutions.oasis.chat.ui.QuoteTexts
import de.velwebsolutions.oasis.chat.ui.ReactionActions
import de.velwebsolutions.oasis.chat.ui.ReactionChip
import de.velwebsolutions.oasis.chat.ui.ReplyBar
import de.velwebsolutions.oasis.chat.ui.ReplyQuote
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Reaction chips, the reaction bar, quotes and the quote bar at 360 dp, with a screenshot of each. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class ReactionsUiTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(ConversationState())
    private var target: Int? = 0

    private val thumb = MessagePayload.REACTIONS[0]
    private val heart = MessagePayload.REACTIONS[1]
    private val members = listOf(ConversationMember("1", "mira", "Mira"), ConversationMember("2", "jonas", "Jonas"))
    private val conversation = ChatConversation("7", "direct", null, "1", 7, 1, false, members, "")
    private val envelope = MessageEnvelope(1, "7", 1, "2", "n", "c", "s1", "2100-01-01T00:00:00Z")
    private val expiry = Instant.parse("2100-01-01T00:00:00Z")

    private fun item(signature: String, sender: String, text: String, state: ChatMessageItem.State = ChatMessageItem.State.Readable) =
        ChatMessageItem(signature, envelope.copy(signature = signature), sender, expiry, state, text, MessageContent(text))

    private val reactions = ReactionActions(
        onReact = { item, emoji -> calls += "react ${item.signature} $emoji" },
        onReply = { calls += "reply ${it.signature}" },
        onCancelReply = { calls += "cancel" },
        onQuote = {
            calls += "quote $it"
            target
        },
        onPin = { calls += "pin ${it.signature}" },
        onVote = { item, index -> calls += "vote ${item.signature} $index" },
        onClosePoll = { calls += "close ${it.signature}" }
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
        reactions = reactions
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/chat-reactions-$name.png")
    }

    private fun shown(): ConversationState {
        val question = item("a", "2", "Wollen wir morgen wandern?")
        val answer = item("b", "1", "Ja, gern!")
        val bubbles = listOf(question, answer)
        return ConversationState(
            conversation = conversation,
            timeline = ChatTimeline(bubbles),
            canSend = true,
            reactions = mapOf(
                "a" to listOf(ReactionChip(thumb, 2, true, "$thumb Jonas, Du"), ReactionChip(heart, 1, false, "$heart Jonas"))
            ),
            quotes = mapOf("b" to ReplyQuote("a", "Jonas", "Wollen wir morgen wandern?", false))
        )
    }

    @Test
    fun theRowsAboveTheFirstBubble() {
        val loaded = shown()
        assertEquals("nothing above the timeline", 0, loaded.leadingRows)
        assertEquals(3, loaded.copy(notices = listOf("Hinweis"), requestTitle = "Anfrage", nextBeforeId = "3").leadingRows)
        assertEquals("the note", 1, loaded.copy(timeline = ChatTimeline()).leadingRows)
        assertEquals(1, loaded.copy(conversation = null).leadingRows)
        assertEquals(1, loaded.copy(loadFailed = "Fehler").leadingRows)
    }

    @Test
    fun chipsAndQuoteInTheBubbles() {
        state = shown()
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides Navigator()) { Chat() } } }
        compose.onNodeWithContentDescription("$thumb, 2 Reaktionen").assertIsSelected()
        capture("chips-and-quote")

        // Who reacted: shown under the chips, hidden again by the same chip.
        compose.onNodeWithTag("chat.message.reaction.$thumb").performClick()
        compose.onNodeWithText("$thumb Jonas, Du").assertExists()
        compose.onNodeWithTag("chat.message.reaction.$thumb").performClick()
        compose.onNodeWithTag("chat.message.reactors").assertDoesNotExist()

        // The quote leads to the original, or says it is not loaded.
        compose.onNode(hasContentDescription("Zur Nachricht von Jonas springen")).performClick()
        target = null
        compose.onNodeWithTag("chat.message.quote").performClick()
        compose.waitForIdle()
        assertEquals(listOf("quote a", "quote a"), calls.filter { it.startsWith("quote") })

        // Past the original's expiry only a note; unreadable messages and a read-only chat offer nothing.
        state = state.copy(quotes = mapOf("b" to ReplyQuote("a", "Jonas", "", true)))
        compose.onNodeWithText(QuoteTexts.GONE).assertExists()
        state = state.copy(canSend = false)
        compose.onNodeWithTag("chat.message.a").performTouchInput { longClick(bottomRight - androidx.compose.ui.geometry.Offset(6f, 6f)) }
        compose.onNodeWithContentDescription("Reaktion wählen").assertDoesNotExist()
    }

    @Test
    fun theBarByHoldingTheBubbleOrReagierenAndTheQuoteBar() {
        state = shown()
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides Navigator()) { Chat() } } }
        // TalkBack's „Antworten“ on the bubble.
        customAction("chat.message.a", "Antworten")
        // Held on the bubble (on its time, outside the selectable text): the bar with the own reaction pressed.
        compose.onNodeWithTag("chat.message.a").performTouchInput { longClick(bottomRight - androidx.compose.ui.geometry.Offset(6f, 6f)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Reaktion wählen").assertExists()
        compose.onNodeWithContentDescription("Mit $thumb reagieren").assertIsSelected()
        capture("bar")
        compose.onNodeWithContentDescription("Mit $heart reagieren").performClick()
        compose.waitForIdle()
        customAction("chat.message.b", "Reagieren")
        compose.onNodeWithTag("chat.reactionBar.reply").performClick()
        compose.waitForIdle()
        assertEquals(listOf("reply a", "react a $heart", "reply b"), calls)
        // TalkBack: the bubble's long-press action opens the bar too.
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        )[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithContentDescription("Reaktion wählen").assertExists()
        compose.onNodeWithTag("chat.reactionBar.reply").performClick()

        state = state.copy(replyBar = ReplyBar("Antwort an Jonas", "Wollen wir morgen wandern?"), draft = "Ja!")
        compose.onNodeWithText("Antwort an Jonas").assertExists()
        capture("reply-bar")
        compose.onNodeWithContentDescription("Antwort abbrechen").performClick()
        assertEquals("cancel", calls.last())
        // The first bubble with the long-press action is the newest one (the list is laid out newest first).
        assertEquals(listOf("reply a", "react a $heart", "reply b", "reply b"), calls.dropLast(1))
    }

    /** Runs the TalkBack action [label] of the node with [tag]. */
    private fun customAction(tag: String, label: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        compose.runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == label }.action() }
    }
}
