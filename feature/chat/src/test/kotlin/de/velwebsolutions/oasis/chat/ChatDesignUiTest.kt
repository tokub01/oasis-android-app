package de.velwebsolutions.oasis.chat

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.chat.api.ChatAvatar
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.core.ChatBackground
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.core.LookChoice
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationListContent
import de.velwebsolutions.oasis.chat.ui.ConversationListState
import de.velwebsolutions.oasis.chat.ui.ConversationRowActions
import de.velwebsolutions.oasis.chat.ui.ConversationState
import de.velwebsolutions.oasis.chat.ui.ReactionActions
import de.velwebsolutions.oasis.chat.ui.ReactionChip
import de.velwebsolutions.oasis.chat.ui.ReplyQuote
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.Appearance
import de.velwebsolutions.oasis.design.theme.AppearanceChoice
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisTheme
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The chat's design and gestures at 360 dp, light and dark and with every chat look (docs/mildcrowd-chat-client.md). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class ChatDesignUiTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var screen by mutableStateOf<@Composable () -> Unit>({})

    @After
    fun light() {
        Appearance.show(AppearanceChoice.Automatic)
        Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }

    /** The system's animations on or off (the animator duration scale). */
    private fun motion(on: Boolean) {
        val resolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (on) 1f else 0f)
    }

    private fun show(content: @Composable () -> Unit) {
        screen = content
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(LocalNavigator provides Navigator()) {
                    Box(Modifier.background(OasisColors.Sand)) { screen() }
                }
            }
        }
    }

    private fun switchTo(content: @Composable () -> Unit) {
        screen = content
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/chat-design-$name.png")
    }

    private val members = listOf(
        ConversationMember("1", "mira", "Mira"),
        ConversationMember("2", "jonas", "Jonas", avatar = ChatAvatar("J", "spring")),
        ConversationMember("3", "aylin", "Aylin", avatar = ChatAvatar("A", "sun"))
    )
    private val group = ChatConversation("7", "group", "Gartenrunde", "1", 7, 1, false, members, "")

    /** A message sent at [sent] (German time „HH:MM“ on 2 or 3 November 2026). */
    private fun item(signature: String, sender: String, sent: String, text: String) = ChatMessageItem(
        signature,
        MessageEnvelope(1, "7", 1, sender, "n", "c", signature, null),
        sender,
        Instant.parse(sent).plusSeconds(7 * 86_400),
        ChatMessageItem.State.Readable,
        text,
        MessageContent(text)
    )

    private val timeline = ChatTimeline(
        listOf(
            item("a", "2", "2026-11-02T17:00:00Z", "Gießen wir morgen?"),
            item("b", "2", "2026-11-03T08:00:00Z", "Guten Morgen!"),
            item("c", "2", "2026-11-03T08:01:00Z", "Ich bringe die Kannen mit."),
            item("d", "3", "2026-11-03T08:02:00Z", "Ich komme gegen zehn."),
            item("e", "1", "2026-11-03T08:05:00Z", "Super, bis gleich."),
            item("f", "1", "2026-11-03T08:06:00Z", "Ich bringe Kuchen.")
        )
    )

    private fun state(look: ChatBackground = ChatBackground.Standard) = ConversationState(
        conversation = group,
        title = "Gartenrunde",
        timeline = timeline,
        canSend = true,
        look = LookChoice(look, "default"),
        reactions = mapOf("c" to listOf(ReactionChip("👍", 2, true, "👍 Aylin, Du"))),
        quotes = mapOf("e" to ReplyQuote("d", "Aylin", "Ich komme gegen zehn.", false)),
        rows = de.velwebsolutions.oasis.chat.ui.timelineRows(
            timeline.items,
            7,
            Instant.parse("2026-11-03T08:01:30Z"),
            "1",
            Instant.parse("2026-11-03T12:00:00Z")
        )
    )

    @Composable
    private fun Chat(state: ConversationState) = ConversationContent(
        state = state,
        myId = "1",
        senderName = { id -> members.first { it.userId == id }.name },
        onLoadOlder = {},
        onDraftChange = {},
        onSend = {},
        onReport = { calls += "report ${it.signature}" },
        onAccept = {},
        onDecline = {},
        onBlockFlagged = {},
        onDismissFlagged = {},
        onNoticeSpoken = {},
        attachments = attachmentActions(calls),
        senderAvatar = { id -> members.first { it.userId == id }.shownAvatar ?: ChatAvatar.Unknown },
        reactions = ReactionActions(
            onReact = { item, emoji -> calls += "react ${item.signature} $emoji" },
            onReply = { calls += "reply ${it.signature}" },
            onCancelReply = {},
            onQuote = { null },
            onPin = {},
            onVote = { _, _ -> },
            onClosePoll = {}
        )
    )

    @Test
    fun theConversationInBothModesAndEveryLook() {
        show { Chat(state()) }
        compose.onNodeWithText("Heute").assertExists()
        compose.onNodeWithText("Gestern").assertExists()
        compose.onNodeWithTag("chat.unread").assertExists()
        capture("conversation-light")
        Appearance.show(AppearanceChoice.Dark)
        capture("conversation-dark")
        Appearance.show(AppearanceChoice.Automatic)
        listOf(ChatBackground.Duene, ChatBackground.Oase, ChatBackground.Abendsonne, ChatBackground.Nachtwasser, ChatBackground.Sandkoerner)
            .forEach { look ->
                switchTo { Chat(state(look)) }
                capture("look-${look.key}")
            }
        Appearance.show(AppearanceChoice.Dark)
        switchTo { Chat(state(ChatBackground.Nachtwasser)) }
        capture("look-nachtwasser-dark")
    }

    @Test
    fun swipingAMessageRightAnswersIt() {
        show { Chat(state()) }
        val bubble = compose.onNodeWithTag("chat.message.d")
        bubble.performTouchInput { swipe(centerLeft + Offset(10f, 0f), centerLeft + Offset(10f + 80f * density, 0f), 300) }
        compose.waitForIdle()
        assertEquals(listOf("reply d"), calls)
        // Not far enough, the wrong way, or mostly up and down: nothing.
        bubble.performTouchInput { swipe(center, center - Offset(80f * density, 0f), 300) }
        bubble.performTouchInput { swipe(centerLeft + Offset(10f, 0f), centerLeft + Offset(10f + 30f * density, 0f), 300) }
        bubble.performTouchInput { swipe(center, center + Offset(20f * density, -80f * density), 300) }
        compose.waitForIdle()
        assertEquals(listOf("reply d"), calls)

        // Without motion the bubble stays in place and the swipe still answers.
        Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f
        )
        switchTo { Chat(state().copy(draft = " ")) }
        compose.onNodeWithTag("chat.message.b").performTouchInput {
            swipe(centerLeft + Offset(10f, 0f), centerLeft + Offset(10f + 80f * density, 0f), 300)
        }
        compose.waitForIdle()
        assertEquals(listOf("reply d", "reply b"), calls)

        // TalkBack: answer, react and report as the bubble's actions.
        val node = compose.onNodeWithTag("chat.message.d").fetchSemanticsNode()
        compose.runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == "Nachricht von Aylin melden" }.action() }
        compose.runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == "Reagieren" }.action() }
        compose.onNodeWithTag("chat.reactionBar.info").assertExists()
        assertEquals(listOf("reply d", "reply b", "report d"), calls)
    }

    @Test
    fun theButtonDownCountsWhatArrivedWhileScrolledUp() {
        val many = ChatTimeline(
            (0 until 30).map {
                item(
                    "m$it",
                    if (it % 2 ==
                        0
                    ) {
                        "2"
                    } else {
                        "1"
                    },
                    "2026-11-03T0${it / 10}:${10 + it}:00Z",
                    "Nachricht $it"
                )
            }
        )
        fun crowded(items: ChatTimeline) = state().copy(timeline = items, rows = emptyList(), reactions = emptyMap(), quotes = emptyMap())
        var shown by mutableStateOf(many)
        show { Chat(crowded(shown)) }
        compose.waitForIdle()
        // The oldest messages: the list is laid out newest first.
        compose.onNodeWithTag("chat.messages").performScrollToIndex(28)
        compose.waitForIdle()
        compose.onNodeWithTag("chat.scrollDown").assertExists()
        shown = ChatTimeline(many.items + item("new", "2", "2026-11-03T09:00:00Z", "Neu da"))
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Nach unten, 1 neue Nachricht")).assertExists()
        capture("scroll-down")
        compose.onNodeWithTag("chat.scrollDown").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat.scrollDown").assertDoesNotExist()
    }

    @Test
    fun aChatOpensAtTheDividerAndOlderMessagesLoadWithoutMovingTheShownOnes() {
        val many = ChatTimeline(
            (0 until 40).map { item("m$it", if (it % 2 == 0) "2" else "1", "2026-11-03T0${it / 10}:${10 + it % 10}:00Z", "Nachricht $it") }
        )
        fun opened(items: ChatTimeline) = state().copy(
            timeline = items,
            reactions = emptyMap(),
            quotes = emptyMap(),
            rows = de.velwebsolutions.oasis.chat.ui.timelineRows(
                items.items,
                7,
                Instant.parse("2026-11-03T01:14:30Z"),
                "1",
                Instant.parse("2026-11-03T12:00:00Z")
            )
        )
        var shown by mutableStateOf(opened(many))
        show { Chat(shown) }
        compose.waitForIdle()
        // The divider is in view (the new messages below it), not the newest message far below.
        compose.onNodeWithTag("chat.unread").assertIsDisplayed()
        capture("opened-at-divider")
        fun top() = compose.onNodeWithTag("chat.message.m15").fetchSemanticsNode().boundsInRoot.top
        val before = top()
        val older = (0 until 10).map { item("o$it", "2", "2026-11-02T10:0$it:00Z", "Älter $it") }
        shown = opened(ChatTimeline(older + many.items))
        compose.waitForIdle()
        assertEquals("older messages load above without moving the shown ones", before, top())
    }

    private fun row(
        id: String,
        unread: Boolean = false,
        muted: Boolean = false,
        ended: Boolean = false,
        type: String = "direct",
        owner: String = "2"
    ) = ChatConversation(
        id, type,
        if (type ==
            "group"
        ) {
            "Gartenrunde"
        } else {
            null
        },
        owner, 7, 1, false, members.take(2), "", unread = unread, muted = muted, ended = ended
    )

    private val actions = ConversationRowActions(
        preview = { if (it.id == "1") "Du: Bis gleich" else "Verschlüsselte Nachricht" },
        activity = { if (it.unread == true) "14:05" else "Gestern" },
        canLeave = { it.type != "group" },
        onMarkRead = { calls += "read ${it.id}" },
        onToggleMute = { calls += "mute ${it.id}" },
        onLeave = { calls += "leave ${it.id}" }
    )

    @Composable
    private fun List(rows: List<ChatConversation>) = ConversationListContent(
        state = ConversationListState(conversations = rows, loaded = true),
        canParticipate = true,
        title = { it.displayTitle("1") },
        meta = { "Zu zweit" },
        rows = actions,
        onRefresh = {},
        onLoadMore = {},
        onOpen = { calls += "open ${it.id}" },
        onNew = {},
        onApprove = {},
        onReject = {},
        myId = "1"
    )

    private val rows =
        listOf(row("1", unread = true), row("2", muted = true), row("3", ended = true), row("4", type = "group", owner = "1"))

    @Test
    fun theListRowsSwipeAndOfferTheSameInTheirMenu() {
        show { List(rows) }
        compose.onNodeWithText("Du: Bis gleich").assertExists()
        compose.onNodeWithText("Beendet").assertExists()
        compose.onNodeWithTag("chat.list.unread", useUnmergedTree = true).assertExists()
        capture("list-light")
        Appearance.show(AppearanceChoice.Dark)
        capture("list-dark")
        Appearance.show(AppearanceChoice.Automatic)

        // Right on an unread chat: marked read. Left: the actions; a tap elsewhere closes them.
        compose.onNodeWithTag("chat.conversation.1").performTouchInput {
            swipe(
                centerLeft + Offset(20f, 0f),
                centerLeft + Offset(20f + 90f * density, 0f),
                300
            )
        }
        compose.onNodeWithTag("chat.conversation.2").performTouchInput {
            swipe(
                centerRight - Offset(20f, 0f),
                centerRight - Offset(20f + 120f * density, 0f),
                300
            )
        }
        compose.waitForIdle()
        capture("list-open")
        compose.onNodeWithTag("chat.list.swipe.Ton an").performClick()
        compose.onNodeWithTag("chat.conversation.3").performTouchInput {
            swipe(
                centerRight - Offset(20f, 0f),
                centerRight - Offset(20f + 120f * density, 0f),
                300
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat.conversation.1").performClick()
        compose.onNodeWithTag("chat.conversation.3").performTouchInput {
            swipe(
                centerRight - Offset(20f, 0f),
                centerRight - Offset(20f + 120f * density, 0f),
                300
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat.list.swipe.Chat verlassen").performClick()
        compose.onNodeWithText("Chat verlassen? Er verschwindet aus deiner Liste.").assertExists()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Chat verlassen")).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        // A short swipe and a swipe right on a read chat change nothing; a tap opens it.
        compose.onNodeWithTag("chat.conversation.2").performTouchInput {
            swipe(
                centerRight - Offset(20f, 0f),
                centerRight - Offset(20f + 30f * density, 0f),
                300
            )
        }
        compose.onNodeWithTag("chat.conversation.2").performTouchInput {
            swipe(
                centerLeft + Offset(20f, 0f),
                centerLeft + Offset(20f + 90f * density, 0f),
                300
            )
        }
        compose.onNodeWithTag("chat.conversation.2").performClick()
        compose.waitForIdle()
        assertEquals(listOf("read 1", "mute 2", "leave 3", "open 2"), calls)

        // The „⋯“ menu and TalkBack's actions do the same.
        calls.clear()
        compose.onNodeWithTag("chat.list.menu.1").performClick()
        compose.onNodeWithTag("chat.list.action.Als gelesen markieren").performClick()
        compose.onNodeWithTag("chat.list.menu.1").performClick()
        compose.onNodeWithTag("chat.list.action.Stummschalten").performClick()
        compose.onNodeWithTag("chat.list.menu.1").performClick()
        compose.onNodeWithTag("chat.list.action.Chat verlassen").performClick()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Chat verlassen")).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        val node = compose.onNodeWithTag("chat.conversation.2").fetchSemanticsNode()
        compose.runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == "Ton an" }.action() }
        compose.waitForIdle()
        assertEquals(listOf("read 1", "mute 1", "leave 1", "mute 2"), calls)
        assertTrue(compose.onAllNodes(hasTestTag("chat.list.menu.4")).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("chat.list.menu.4").performClick()
        compose.onNodeWithTag("chat.list.action.Chat verlassen").assertDoesNotExist()
        // Without motion the row does not follow the finger; its actions still open.
        motion(false)
        switchTo { List(rows.take(2)) }
        compose.onNodeWithTag("chat.conversation.2").performTouchInput {
            swipe(
                centerRight - Offset(20f, 0f),
                centerRight - Offset(20f + 120f * density, 0f),
                300
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat.list.swipe.Ton an").assertExists()
        // A swipe right on the open row closes it again.
        compose.onNodeWithTag("chat.conversation.2").performTouchInput {
            swipe(
                centerLeft + Offset(20f, 0f),
                centerLeft + Offset(20f + 120f * density, 0f),
                300
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat.list.swipe.Ton an").assertDoesNotExist()
    }

    @Test
    fun theHeaderBarInDarkMode() {
        Appearance.show(AppearanceChoice.Dark)
        show {
            Column {
                de.velwebsolutions.oasis.chat.ui.ChatHeaderBar(group, "1", "Gartenrunde", "Ende-zu-Ende verschlüsselt", {
                    calls +=
                        "back"
                }, {})
                Chat(state())
            }
        }
        capture("header-dark")
        compose.onNodeWithTag("chat.header.back").performClick()
        assertEquals(listOf("back"), calls)
    }
}
