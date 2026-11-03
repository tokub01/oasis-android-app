package de.velwebsolutions.oasis.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.chat.api.ChatAvatar
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.ui.ChatAccessState
import de.velwebsolutions.oasis.chat.ui.ChatHeaderBar
import de.velwebsolutions.oasis.chat.ui.ChatPanes
import de.velwebsolutions.oasis.chat.ui.ChatSetupContent
import de.velwebsolutions.oasis.chat.ui.ChatUnlockContent
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationListContent
import de.velwebsolutions.oasis.chat.ui.ConversationListState
import de.velwebsolutions.oasis.chat.ui.ConversationRowActions
import de.velwebsolutions.oasis.chat.ui.ConversationState
import de.velwebsolutions.oasis.chat.ui.PinBar
import de.velwebsolutions.oasis.chat.ui.PollAnswer
import de.velwebsolutions.oasis.chat.ui.PollView
import de.velwebsolutions.oasis.chat.ui.ReactionChip
import de.velwebsolutions.oasis.chat.ui.ReplyBar
import de.velwebsolutions.oasis.chat.ui.ReplyQuote
import de.velwebsolutions.oasis.chat.ui.timelineInset
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every chat element at a narrow and a large phone and with larger text (font scale 1.3 and 1.5), light and dark, as
 * screenshots to look at (docs/oasis-chat-client.md, „Chat design and gestures“).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class ChatSpacingTest {
    @get:Rule val compose = createComposeRule()

    @After
    fun light() = Appearance.show(AppearanceChoice.Automatic)

    private var screen by mutableStateOf<@Composable () -> Unit>({})

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

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/chat-spacing-$name.png")
    }

    private val members = listOf(
        ConversationMember("1", "mira", "Mira"),
        ConversationMember("2", "jonas", "Jonas Weber", avatar = ChatAvatar("J", "spring")),
        ConversationMember("3", "aylin", "Aylin", avatar = ChatAvatar("A", "sun"))
    )
    private val group = ChatConversation("7", "group", "Gartenrunde am Fluss", "1", 7, 1, false, members, "")

    private fun item(signature: String, sender: String, sent: String, text: String, content: MessageContent = MessageContent(text)) =
        ChatMessageItem(
            signature,
            MessageEnvelope(1, "7", 1, sender, "n", "c", signature, null),
            sender,
            Instant.parse(sent).plusSeconds(7 * 86_400),
            ChatMessageItem.State.Readable,
            text,
            content
        )

    private val photo = MessageAttachment("p", "image", "image/jpeg", 1_000, "k", "n", width = 1200, height = 900)

    private val state = ConversationState(
        conversation = group,
        title = "Gartenrunde am Fluss",
        canSend = true,
        timeline = ChatTimeline(
            listOf(
                item("a", "2", "2026-11-02T17:00:00Z", "Gießen wir morgen? Ich hätte ab zehn Uhr Zeit, danach muss ich los."),
                item("b", "3", "2026-11-03T08:00:00Z", "Ich bringe Kuchen mit."),
                item("c", "2", "2026-11-03T08:01:00Z", "", MessageContent("", attachments = listOf(photo)))
                    .copy(attachmentStates = mapOf("p" to de.velwebsolutions.oasis.chat.api.AttachmentState("p", status = "accepted"))),
                item("d", "1", "2026-11-03T08:05:00Z", "Super, bis gleich."),
                item("e", "2", "2026-11-03T08:10:00Z", "Wann treffen wir uns?")
            )
        ),
        reactions = mapOf("b" to listOf(ReactionChip("👍", 2, true, "👍 Jonas, Du"), ReactionChip("❤️", 1, false, "❤️ Jonas"))),
        quotes = mapOf("d" to ReplyQuote("b", "Aylin", "Ich bringe Kuchen mit.", false)),
        polls = mapOf(
            "e" to PollView(
                "Wann treffen wir uns?",
                listOf(PollAnswer("Um zehn", 2, true, "Mira, Jonas"), PollAnswer("Um elf", 1, false, "Aylin")),
                multiple = false,
                closed = false,
                open = true,
                canClose = true
            )
        ),
        pinBar = PinBar("b", "Ich bringe Kuchen mit.", false),
        replyBar = ReplyBar("Antwort an Jonas Weber", "Wann treffen wir uns?"),
        draft = "Klingt gut, ich komme auch"
    )

    @Composable
    private fun Chat() = Column {
        ChatHeaderBar(group, "1", group.title.orEmpty(), "Ende-zu-Ende verschlüsselt · löschen sich nach 7 Tagen", {}, {})
        ConversationContent(
            state = state,
            myId = "1",
            senderName = { id -> members.first { it.userId == id }.name },
            onLoadOlder = {},
            onDraftChange = {},
            onSend = {},
            onReport = {},
            onAccept = {},
            onDecline = {},
            onBlockFlagged = {},
            onDismissFlagged = {},
            onNoticeSpoken = {},
            attachments = attachmentActions(mutableListOf()),
            senderAvatar = { id -> members.first { it.userId == id }.shownAvatar ?: ChatAvatar.Unknown }
        )
    }

    @Composable
    private fun List() = ConversationListContent(
        state = ConversationListState(
            conversations = listOf(
                ChatConversation("1", "direct", null, "1", 7, 1, false, members.take(2), "", unread = true),
                ChatConversation("2", "group", "Gartenrunde am Fluss mit einem langen Namen", "2", 7, 1, false, members, "", muted = true),
                ChatConversation("3", "direct", null, "1", 7, 1, false, members.take(2), "", ended = true)
            ),
            loaded = true
        ),
        canParticipate = true,
        title = { it.displayTitle("1") },
        meta = { "" },
        rows = ConversationRowActions(
            preview = { if (it.id == "1") "Du: Bis gleich, ich bringe die Kannen mit" else "Verschlüsselte Nachricht" },
            activity = { if (it.unread == true) "14:05" else "Gestern" },
            canLeave = { true },
            onMarkRead = {},
            onToggleMute = {},
            onLeave = {}
        ),
        onRefresh = {},
        onLoadMore = {},
        onOpen = {},
        onNew = {},
        onApprove = {},
        onReject = {},
        myId = "1"
    )

    private fun everything(name: String) {
        show { Chat() }
        capture("$name-conversation")
        compose.onNodeWithTag("chat.messages").performScrollToIndex(2)
        // A photo that is still loading keeps the size the message names.
        compose.onNodeWithTag("chat.attachment.placeholder", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("chat.messages").performScrollToIndex(6)
        capture("$name-conversation-top")
        show2 { List() }
        capture("$name-list")
        show2 { Unlock() }
        capture("$name-unlock")
        show2 { Setup() }
        capture("$name-setup")
    }

    private fun show2(content: @Composable () -> Unit) {
        screen = content
        compose.waitForIdle()
    }

    @Composable
    private fun Unlock() = ChatUnlockContent(
        ChatAccessState(
            hasPassphrase = true,
            canUnlockWithDevice = true,
            deviceUnlockError = "Der Fingerabdruck wurde nicht erkannt. Versuch es noch einmal."
        ),
        {},
        {},
        {},
        {},
        {},
        {}
    )

    @Composable
    private fun Setup() = ChatSetupContent(ChatAccessState(deviceAvailable = true), {}, {}, {}, {}, {})

    @Test
    fun atAnArrowPhone() = everything("360")

    @Test
    @Config(qualifiers = "w360dp-h740dp", fontScale = 1.3f)
    fun withTextAtOnePointThree() = everything("360-font130")

    @Test
    @Config(qualifiers = "w360dp-h740dp", fontScale = 1.5f)
    fun withTextAtOnePointFive() {
        Appearance.show(AppearanceChoice.Dark)
        everything("360-font150-dark")
    }

    @Test
    @Config(qualifiers = "w430dp-h932dp")
    fun atALargePhone() = everything("430")

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun atASmallPhone() = everything("320")

    @Test
    fun onePaneAtATimeOnPhones() {
        show { ChatPanes(null, list = { List() }) { Chat() } }
        compose.onNodeWithTag("chat.panes").assertDoesNotExist()
        compose.onNodeWithTag("chat.list").assertExists()
        show2 { ChatPanes(7, list = { List() }) { Chat() } }
        compose.onNodeWithTag("chat.list").assertDoesNotExist()
        compose.onNodeWithTag("chat.messages").assertExists()
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp")
    fun listBesideTheConversationOnWideScreens() {
        show { ChatPanes(7, list = { List() }) { Chat() } }
        compose.onNodeWithTag("chat.panes").assertExists()
        compose.onNodeWithTag("chat.list").assertExists()
        compose.onNodeWithTag("chat.messages").assertExists()
        capture("900-panes")
        show2 { ChatPanes(null, list = { List() }) { Chat() } }
        compose.onNodeWithTag("chat.panes.empty").assertExists()
        capture("900-empty")
        Appearance.show(AppearanceChoice.Dark)
        show2 { ChatPanes(7, list = { List() }) { Chat() } }
        capture("900-panes-dark")
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun aTabletInOnePaneUsesTheWiderInset() {
        assertEquals(16.dp, timelineInset(700.dp))
        assertEquals(12.dp, timelineInset(639.dp))
        everything("700")
    }
}
