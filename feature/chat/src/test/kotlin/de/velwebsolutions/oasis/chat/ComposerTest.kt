package de.velwebsolutions.oasis.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.core.AttachmentKind
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.core.ChatSpam
import de.velwebsolutions.oasis.chat.core.DraftAttachment
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationState
import de.velwebsolutions.oasis.chat.ui.DraftLimit
import de.velwebsolutions.oasis.chat.ui.fieldMax
import de.velwebsolutions.oasis.chat.ui.showsScrollDown
import de.velwebsolutions.oasis.design.theme.OasisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The composer's field (docs/oasis-chat-client.md, „Composer limit and draft“): it keeps exactly what is typed without
 * waiting for the view model, takes at most the limit, and shows only the view model's own changes of the draft.
 */
@RunWith(RobolectricTestRunner::class)
class ComposerTest {
    @get:Rule val compose = createComposeRule()

    private val typed = mutableListOf<String>()
    private val conversation = ChatConversation("7", "direct", null, "1", 7, 1, false, emptyList(), "2026-10-22T12:00:00Z")
    private var state by mutableStateOf(ConversationState(conversation = conversation, canSend = true))

    private fun show() = compose.setContent {
        OasisTheme {
            ConversationContent(
                state = state,
                myId = "1",
                senderName = { "Jonas" },
                onLoadOlder = {},
                onDraftChange = { typed += it },
                onSend = {},
                onReport = {},
                onAccept = {},
                onDecline = {},
                onBlockFlagged = {},
                onDismissFlagged = {},
                onNoticeSpoken = {},
                attachments = attachmentActions(mutableListOf())
            )
        }
    }

    private val field get() = compose.onNodeWithTag("chat.composer.field")

    private fun shown(): String = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun theFieldKeepsEveryKeyPressWithoutWaitingForTheViewModel() {
        show()
        // The state never takes the typed text back (as while the view model is busy): nothing is lost or rewritten.
        "a  b".forEach { field.performTextInput(it.toString()) }
        field.performTextInput("   ")
        assertEquals("a  b   ", shown())
        assertEquals("a  b   ", typed.last())
        // Holding backspace: every repeat deletes one more character.
        repeat(3) {
            val text = shown()
            field.performTextReplacement(text.dropLast(1))
        }
        assertEquals("a  b", shown())
    }

    @Test
    fun onlyTheViewModelsOwnChangesReachTheField() {
        state = state.copy(draft = "Hallo")
        show()
        assertEquals("Hallo", shown())
        field.performTextInput(" Jonas")
        // A state lagging behind the typing (the same revision) does not overwrite the field.
        state = state.copy(draft = "Hallo")
        compose.waitForIdle()
        assertEquals("Hallo Jonas", shown())
        // Sent, shared in or left: a new revision writes the view model's draft into the field.
        state = state.copy(draft = "", draftRevision = 1)
        compose.waitForIdle()
        assertEquals("", shown())
        state = state.copy(draft = "", draftRevision = 2)
        compose.waitForIdle()
        assertEquals("", shown())
    }

    @Test
    fun typingBeyondTheLimitDoesNothingAndAPasteIsCut() {
        state = state.copy(draft = "a".repeat(3_998))
        show()
        field.performTextInputSelection(TextRange(3_998))
        field.performTextInput("bcdef")
        assertEquals("a".repeat(3_998) + "bc", shown())
        field.performTextInput("x")
        assertEquals("a".repeat(3_998) + "bc", shown())
        // Deleting always works.
        field.performTextReplacement("a".repeat(10))
        assertEquals("a".repeat(10), shown())
        // Files leave less room for the text.
        state = state.copy(limit = DraftLimit(List(4) { DraftAttachment(it.toLong(), AttachmentKind.Video, "video/mp4", byteArrayOf(1)) }))
        compose.waitForIdle()
        field.performTextInput("b".repeat(4_000))
        val kept = shown()
        assertTrue("files leave less room", kept.length < 4_000)
        assertEquals(state.limit.measure(kept).max, kept.length)
        assertFalse(state.limit.measure(kept).tooLong)
    }

    @Test
    fun theCounterShowsFromThreeQuartersAndAtTheLimit() {
        show()
        compose.onNodeWithTag("chat.composer.counter").assertDoesNotExist()
        state = state.copy(counter = "3.120 / 4.000")
        compose.onNodeWithTag("chat.composer.counter").assertTextEquals("3.120 / 4.000")
        state = state.copy(counter = "Höchstens 4.000 Zeichen", atLimit = true)
        compose.onNodeWithTag("chat.composer.counter").assertTextEquals("Höchstens 4.000 Zeichen")
    }

    @Test
    fun theSendButtonSaysTrotzdemSendenAndCountsDownAPause() {
        show()
        state = state.copy(draft = "ICH FREUE MICH SO SEHR", hint = ChatSpam.SHOUTING_HINT, sendLabel = ChatSpam.SEND_ANYWAY)
        compose.onNodeWithTag("chat.composer.hint").assertTextEquals(ChatSpam.SHOUTING_HINT)
        compose.onNodeWithText(ChatSpam.SEND_ANYWAY).assertExists()
        state = state.copy(paused = true, hint = ChatSpam.FLOOD_PAUSE, sendLabel = "Noch 27 s")
        compose.onNodeWithText("Noch 27 s").assertExists()
        compose.onNodeWithTag("chat.composer.send").assertIsNotEnabled()
    }

    @Test
    fun theCounterAndTheMaximumFollowTheMeasurement() {
        assertEquals("", DraftLimit.counter(ChatMessages.measure("a".repeat(2_999))))
        assertEquals("3.000 / 4.000", DraftLimit.counter(ChatMessages.measure("a".repeat(3_000))))
        assertEquals("Höchstens 4.000 Zeichen", DraftLimit.counter(ChatMessages.measure("a".repeat(4_000))))
        assertEquals("Bis zu 4.000 Zeichen", DraftLimit.placeholder(4_000))
        assertEquals("Bis zu 950 Zeichen", DraftLimit.placeholder(950))
        assertTrue(DraftLimit.atLimit(ChatMessages.measure("a".repeat(4_000))))
        assertFalse(DraftLimit.atLimit(ChatMessages.measure("a".repeat(3_999))))
        assertTrue("a file added after the text", DraftLimit.atLimit(ChatMessages.measure("a".repeat(4_001))))
        // Every palm tree takes 4 bytes: the maximum shrinks with the bytes left.
        val palms = ChatMessages.measure("🌴".repeat(1_400))
        assertEquals(1_400 + 5_984 - palms.bytes, palms.max)
        assertEquals("over the bytes", "Höchstens 1.184 Zeichen", DraftLimit.counter(ChatMessages.measure("🌴".repeat(1_600))))
        assertEquals("never below 0", 0, ChatMessages.measure("🌴".repeat(3_000)).max)
        // With files the plaintext is JSON, whose characters count too.
        val video = listOf(DraftAttachment(1, AttachmentKind.Video, "video/mp4", byteArrayOf(1)))
        val withFile = ChatMessages.measure("a".repeat(100), video)
        assertEquals(100 + 4_000 - withFile.plaintextCharacters, withFile.max)
    }

    @Test
    fun anEditKeepsOnlyWhatFits() {
        val fits = { text: String -> text.codePointCount(0, text.length) <= 5 }
        assertNull("it fits", DraftLimit.keep("abc", "abcd", fits))
        assertNull("deleting always works", DraftLimit.keep("abcdefg", "abcdef", fits))
        assertEquals(DraftLimit.Kept("abcde", 5), DraftLimit.keep("abc", "abcdefgh", fits))
        assertEquals("pasted in the middle", DraftLimit.Kept("a12bc", 3), DraftLimit.keep("abc", "a1234bc", fits))
        assertEquals("nothing more fits", DraftLimit.Kept("abcde", 5), DraftLimit.keep("abcde", "abcdef", fits))
        // Whole characters only: a palm tree after another one is not split.
        assertEquals(DraftLimit.Kept("🌴🌴🌴🌴🌴", 10), DraftLimit.keep("🌴🌴🌴🌴", "🌴🌴🌴🌴🌴🌴", fits))
        // Two characters that share their low half (U+1F334, U+1F734) are kept apart at the end.
        val one = { text: String -> text.codePointCount(0, text.length) <= 1 }
        assertEquals(DraftLimit.Kept("x", 1), DraftLimit.keep("🌴", "x🜴", one))
        // … and at the start (U+1F334, U+1F333 share their high half).
        assertEquals(DraftLimit.Kept("🌳", 2), DraftLimit.keep("🌴", "🌳🌳", one))
    }

    @Test
    fun aLongDraftTakesAtMostAThirdAndTheButtonOnlyWhereItFits() {
        assertEquals(200.dp, fieldMax(600.dp))
        assertEquals("at least one line", 44.dp, fieldMax(90.dp))
        assertEquals(androidx.compose.ui.unit.Dp.Unspecified, fieldMax(0.dp))
        assertTrue(showsScrollDown(true, 400.dp, 120.dp))
        assertFalse("too little room above the composer", showsScrollDown(true, 200.dp, 120.dp))
        assertFalse("at the newest message", showsScrollDown(false, 400.dp, 120.dp))
    }
}
