package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.api.AttachmentState
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.chat.ui.QuoteTexts
import de.velwebsolutions.oasis.chat.ui.ReactionChip
import de.velwebsolutions.oasis.chat.ui.ReplyBar
import de.velwebsolutions.oasis.chat.ui.ReplyQuote
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.chatcrypto.MessageReply
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reactions and replies in the view model (docs/oasis-chat-client.md, "Reactions and replies"): quiet encrypted
 * reaction messages that show as chips, never as bubbles, and quotes that go along inside the payload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReactionsAndRepliesTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()
    private val server get() = support.server
    private val thumb = MessagePayload.REACTIONS[0]
    private val heart = MessagePayload.REACTIONS[1]
    private val laugh = MessagePayload.REACTIONS[2]

    private fun model(context: ChatContext, id: String) = ConversationViewModel(id, context, FakeRealtime()).apply {
        pollInterval = Duration.INFINITE
        sweepInterval = Duration.INFINITE
        recordingTick = Duration.INFINITE
    }

    private suspend fun chat(): Triple<ConversationViewModel, ConversationViewModel, String> {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = mira.repository.createConversation("direct", null, listOf("2"), 7).id
        val writer = model(mira, id)
        writer.open()
        val reader = model(jonas, id)
        reader.open()
        return Triple(writer, reader, id)
    }

    private fun lastSent(id: String): MessageEnvelope =
        Json.decodeFromString(MessageEnvelope.serializer(), server.messages.getValue(id.toInt()).last().second.toString())

    private fun ConversationViewModel.live(id: String) = handle(RealtimeEvent("MessageSent", lastSent(id).toJsonObject()))

    @Test
    fun aReactionIsAQuietChipAndTheNewestPerPersonCounts() = runTest {
        val (writer, reader, id) = chat()
        writer.setDraft("Wollen wir morgen wandern?")
        writer.send()
        reader.live(id)
        reader.noticeSpoken()
        val original = reader.state.value.bubbles.single()

        reader.react(original, thumb)
        assertEquals("reaction", server.kinds.values.last())
        assertEquals(listOf(ReactionChip(thumb, 1, true, "$thumb Du")), reader.state.value.reactions[original.signature])
        assertEquals("never a bubble", 1, reader.state.value.bubbles.size)
        assertEquals(thumb, reader.state.value.ownReaction(original.signature))

        // Mira sees it live, without an announcement.
        writer.noticeSpoken()
        writer.live(id)
        assertNull(writer.state.value.spokenNotice)
        assertEquals(listOf(ReactionChip(thumb, 1, false, "$thumb Jonas")), writer.state.value.reactions[original.signature])

        // The same emoji again removes it; another one replaces it.
        reader.react(original, thumb)
        assertNull(reader.state.value.reactions[original.signature])
        assertEquals("", reader.state.value.ownReaction(original.signature))
        reader.react(original, laugh)
        reader.react(original, heart)
        writer.react(writer.state.value.bubbles.single(), heart)
        reader.live(id)
        val both = ReactionChip(heart, 2, true, "$heart Mira, Du")
        assertEquals(listOf(both), reader.state.value.reactions[original.signature])
        assertEquals("$heart, 2 Reaktionen", both.description)
        assertEquals("$thumb, 1 Reaktion", ReactionChip(thumb, 1, false, "").description)

        // From the history the same.
        reader.open()
        assertEquals(listOf(both), reader.state.value.reactions[original.signature])
        assertEquals(1, reader.state.value.bubbles.size)
        clear(reader)
        clear(writer)
    }

    @Test
    fun aFailedOrInterruptedReactionAndNoneWhileOneMayNotWrite() = runTest {
        val (writer, reader, id) = chat()
        writer.setDraft("Hallo")
        writer.send()
        reader.live(id)
        val original = reader.state.value.bubbles.single()
        assertTrue(reader.state.value.canAct(original))
        assertFalse(reader.state.value.canAct(original.copy(state = ChatMessageItem.State.NoKey)))

        server.failures["POST conversations/$id/messages"] = 500 to """{"message":"kaputt"}"""
        reader.react(original, thumb)
        assertTrue(reader.state.value.actionError != null)
        reader.dismissActionError()

        // Left meanwhile: sent, but nothing more for this screen – also not its error.
        server.onRequest = { if (it == "POST conversations/$id/messages") reader.close() }
        reader.react(original, thumb)
        reader.open()
        server.failures["POST conversations/$id/messages"] = 500 to """{"message":"kaputt"}"""
        reader.react(original, heart)
        assertNull(reader.state.value.actionError)
        server.onRequest = {}

        // A closed chat reacts to nothing; one that may not be written to offers nothing.
        reader.close()
        reader.react(original, thumb)
        assertFalse(reader.state.value.canAct(original))
        clear(reader)
        clear(writer)
    }

    @Test
    fun aReplyCarriesItsQuoteAndShowsItInTheBubble() = runTest {
        val (writer, reader, id) = chat()
        writer.setDraft("  Wollen wir\n\nmorgen wandern?  ")
        writer.send()
        reader.live(id)
        val original = reader.state.value.bubbles.single()

        reader.startReply(original)
        assertEquals(ReplyBar("Antwort an Mira", "Wollen wir morgen wandern?"), reader.state.value.replyBar)
        reader.cancelReply()
        assertNull(reader.state.value.replyBar)
        reader.startReply(original)
        reader.setDraft("Ja, gern!")
        reader.send()
        assertNull("sending closes the bar", reader.state.value.replyBar)
        val sent = reader.state.value.bubbles.last()
        assertEquals(
            MessageReply(original.signature, "1", "text", "Wollen wir morgen wandern?", sent.content.reply!!.expiresAt),
            sent.content.reply
        )
        assertEquals(ReplyQuote(original.signature, "Mira", "Wollen wir morgen wandern?", false), reader.state.value.quotes[sent.signature])
        assertEquals("after the day pill", 1, reader.quoteTarget(original.signature))

        // Mira sees the quote of her own message as „Du“; her own message answered: „Antwort an dich“.
        writer.live(id)
        assertEquals("Du", writer.state.value.quotes[sent.signature]?.name)
        writer.startReply(writer.state.value.bubbles.first())
        assertEquals("Antwort an dich", writer.state.value.replyBar?.title)
        writer.close()
        assertNull("leaving the chat drops it", writer.state.value.replyBar)

        // Not loaded: a note instead.
        assertNull(reader.quoteTarget("x".repeat(86)))
        assertEquals(QuoteTexts.NOT_LOADED, reader.state.value.status)
        clear(reader)
        clear(writer)
    }

    @Test
    fun aQuoteOfAViewOnceOrExpiredMessageShowsNothingOfIt() = runTest {
        val (writer, reader, id) = chat()
        val conversation = checkNotNull(writer.state.value.conversation)
        val target = "A".repeat(86)
        val expired = MessageReply(target, "2", "text", "Alt", "2020-01-01T00:00:00Z")
        val mira = writer.context
        val envelope = ChatMessages.seal(
            MessagePayload.plaintext(MessageContent("Weißt du noch?", reply = expired)),
            conversation,
            mira.vault,
            mira.crypto
        )
        mira.repository.sendMessage(id, envelope)
        reader.live(id)
        val quote = reader.state.value.quotes.values.single()
        assertEquals(ReplyQuote(target, "Du", "Alt", true), quote)

        // A view-once photo is quoted as „Einmal ansehen“, a photo as „Foto“; a message without a date not at all.
        val photo = MessageAttachment("5", "image", "image/jpeg", 3, "k".repeat(43), "n".repeat(32))
        val item = reader.state.value.bubbles.single().copy(content = MessageContent("", listOf(photo)))
        reader.startReply(item.copy(attachmentStates = mapOf("5" to AttachmentState("5", viewOnce = true))))
        assertEquals("Einmal ansehen", reader.state.value.replyBar?.text)
        reader.startReply(item)
        assertEquals("Foto", reader.state.value.replyBar?.text)
        reader.cancelReply()
        reader.startReply(item.copy(expiresAt = null))
        assertNull(reader.state.value.replyBar)
        assertTrue(Instant.parse(expired.expiresAt).isBefore(Instant.now()))
        clear(reader)
        clear(writer)
    }
}
