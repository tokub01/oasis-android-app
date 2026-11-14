package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.data.ChatRepository
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.chat.ui.PinBar
import de.velwebsolutions.oasis.chat.ui.PollAnswer
import de.velwebsolutions.oasis.chat.ui.PollForm
import de.velwebsolutions.oasis.chat.ui.PollView
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.chatcrypto.MessageReply
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.testing.MainDispatcherRule
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
 * The pinned message and polls in the view model (docs/mildcrowd-chat-client.md, "Pinned message and polls"): quiet
 * encrypted control messages, decided only by the decrypted payload, counted by the rules of the spec.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PinsAndPollsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()
    private val server get() = support.server

    private fun model(context: ChatContext, id: String) = ConversationViewModel(id, context, FakeRealtime()).apply {
        pollInterval = Duration.INFINITE
        sweepInterval = Duration.INFINITE
        recordingTick = Duration.INFINITE
    }

    /** Mira's chat with Jonas (a group of Mira's when [group]), both screens open. */
    private suspend fun chat(group: Boolean = false): Triple<ConversationViewModel, ConversationViewModel, String> {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = mira.repository.createConversation(if (group) "group" else "direct", if (group) "Wandern" else null, listOf("2"), 7).id
        val owner = model(mira, id)
        owner.open()
        val member = model(jonas, id)
        member.open()
        owner.open()
        return Triple(owner, member, id)
    }

    private fun lastSent(id: String): MessageEnvelope =
        Json.decodeFromString(MessageEnvelope.serializer(), server.messages.getValue(id.toInt()).last().second.toString())

    private fun ConversationViewModel.live(id: String) = handle(RealtimeEvent("MessageSent", lastSent(id).toJsonObject()))

    /** A control message sent as another client would (any payload, any kind). */
    private suspend fun ConversationViewModel.forge(id: String, plaintext: String, kind: String = ChatRepository.VOTE) {
        val conversation = checkNotNull(state.value.conversation)
        context.repository.sendMessage(id, ChatMessages.seal(plaintext, conversation, context.vault, context.crypto), kind = kind)
    }

    private suspend fun ConversationViewModel.write(text: String) {
        setDraft(text)
        send()
    }

    @Test
    fun inADirectChatBothPinAndTheNewestPinCounts() = runTest {
        val (mira, jonas, id) = chat()
        mira.write("Treffpunkt: Bahnhof, 9 Uhr")
        jonas.live(id)
        val message = jonas.state.value.bubbles.single()
        assertTrue(jonas.state.value.canPin)
        val rows = jonas.state.value.leadingRows

        jonas.togglePin(message)
        assertEquals("pin", server.kinds.values.last())
        assertEquals(PinBar(message.signature, "Treffpunkt: Bahnhof, 9 Uhr", false), jonas.state.value.pinBar)
        assertEquals("a pin is no bubble", 1, jonas.state.value.bubbles.size)
        assertEquals("the bar is a row above the bubbles", rows + 1, jonas.state.value.leadingRows)
        mira.noticeSpoken()
        mira.live(id)
        assertNull("quiet", mira.state.value.spokenNotice)
        assertEquals(message.signature, mira.state.value.pinBar?.to)

        // „Lösen“ unpins; a message without a date cannot be pinned.
        jonas.togglePin(message)
        assertNull(jonas.state.value.pinBar)
        jonas.togglePin(message.copy(expiresAt = null))
        assertNull(jonas.state.value.pinBar)
        clear(mira)
        clear(jonas)
    }

    @Test
    fun inAGroupOnlyTheOwnersPinCountsAndAnExpiredOneIsGone() = runTest {
        val (mira, jonas, id) = chat(group = true)
        mira.write("Packliste")
        jonas.live(id)
        assertFalse("only the owner pins", jonas.state.value.canPin)
        assertTrue(mira.state.value.canPin)
        val message = jonas.state.value.bubbles.single()
        jonas.togglePin(message)
        assertNull("a member's pin is ignored", jonas.state.value.pinBar)

        val old = MessageReply(message.signature, "1", "image", "", "2020-01-01T00:00:00Z")
        mira.forge(id, MessagePayload.pin(old), ChatRepository.PIN)
        jonas.live(id)
        val pin = checkNotNull(jonas.state.value.pinBar)
        assertTrue(pin.gone)
        assertEquals("Nachricht nicht mehr verfügbar", pin.shown)
        assertEquals("Foto", pin.text)
        assertEquals("Foto", PinBar("x", "Foto", false).shown)
        clear(mira)
        clear(jonas)
    }

    @Test
    fun theFormChecksThePollBeforeItGoes() = runTest {
        val (mira, jonas, id) = chat()
        mira.editPoll { it.withQuestion("ignored") }
        assertNull("no form, nothing to edit", mira.state.value.pollForm)
        mira.sendPoll()
        mira.showPollForm(PollForm())
        assertEquals(PollForm(), mira.state.value.pollForm)

        suspend fun problem(change: (PollForm) -> PollForm): String? {
            mira.editPoll { change(PollForm()) }
            mira.sendPoll()
            return mira.state.value.pollForm?.error
        }
        assertEquals("Bitte stell eine Frage.", problem { it.withQuestion("  ") })
        assertEquals("Die Frage ist zu lang (höchstens 200 Zeichen).", problem { it.withQuestion("x".repeat(201)) })
        assertEquals("Eine Umfrage braucht mindestens zwei Antworten.", problem { it.withQuestion("Wann?").withOption(0, "Samstag") })
        assertEquals(
            "Eine Antwort ist zu lang (höchstens 80 Zeichen).",
            problem { it.withQuestion("Wann?").withOption(0, "Samstag").withOption(1, "y".repeat(81)) }
        )

        // Up to six answers and down to two.
        var form = PollForm()
        repeat(5) { form = form.adding() }
        assertEquals(6, form.options.size)
        assertFalse(form.canAdd)
        repeat(5) { form = form.removing(0) }
        assertEquals(2, form.options.size)
        assertFalse(form.canRemove)
        assertTrue(form.withMultiple(true).multiple)

        // A failed send keeps the form with the reason; one interrupted by leaving says nothing.
        mira.editPoll {
            checkNotNull(
                it
            ).withQuestion(" Wann wandern wir? ").withOption(0, "Samstag").withOption(1, " ").adding().withOption(2, "Sonntag")
        }
        server.failures["POST conversations/$id/messages"] = 500 to """{"message":"kaputt"}"""
        mira.sendPoll()
        assertTrue(mira.state.value.pollForm?.error != null)
        server.onRequest = { if (it == "POST conversations/$id/messages") mira.close() }
        mira.sendPoll()
        mira.open()
        mira.showPollForm(PollForm())
        server.failures["POST conversations/$id/messages"] = 500 to """{"message":"kaputt"}"""
        mira.editPoll { it.withQuestion("Wann?").withOption(0, "a").withOption(1, "b") }
        mira.sendPoll()
        assertNull(mira.state.value.pollForm?.error)
        server.onRequest = {}

        mira.showPollForm(PollForm())
        mira.showPollForm(null)
        assertNull(mira.state.value.pollForm)
        mira.close()
        mira.showPollForm(PollForm())
        mira.sendPoll()
        clear(mira)
        clear(jonas)
    }

    @Test
    fun votesCountByTheRulesAndTheCreatorEndsThePoll() = runTest {
        val (mira, jonas, id) = chat()
        mira.showPollForm(PollForm())
        mira.editPoll { it.withQuestion("Wann wandern wir?").withOption(0, "Samstag").withOption(1, "Sonntag") }
        mira.sendPoll()
        assertNull(mira.state.value.pollForm)
        assertEquals("message", server.kinds.values.last())
        assertEquals("Umfrage gesendet.", mira.state.value.spokenNotice)
        jonas.live(id)
        assertEquals("a poll notifies like a message", "Neue Nachricht von Mira.", jonas.state.value.spokenNotice)
        val poll = jonas.state.value.bubbles.single()
        assertEquals("Wann wandern wir?", poll.content.text)
        assertEquals(
            PollView(
                "Wann wandern wir?",
                listOf(PollAnswer("Samstag", 0, false, "Samstag: "), PollAnswer("Sonntag", 0, false, "Sonntag: ")),
                multiple = false,
                closed = false,
                open = true,
                canClose = false
            ),
            jonas.state.value.polls[poll.signature]
        )
        assertTrue(mira.state.value.polls.values.single().canClose)

        // Single choice: a vote, the same again takes it back, another replaces it.
        jonas.vote(poll, 0)
        assertEquals("vote", server.kinds.values.last())
        assertTrue(jonas.state.value.polls.getValue(poll.signature).answers[0].mine)
        jonas.vote(poll, 0)
        assertEquals(0, jonas.state.value.polls.getValue(poll.signature).answers[0].count)
        jonas.vote(poll, 1)
        jonas.vote(poll, 0)
        mira.vote(mira.state.value.bubbles.single(), 0)
        jonas.live(id)
        val counted = jonas.state.value.polls.getValue(poll.signature).answers
        assertEquals(PollAnswer("Samstag", 2, true, "Samstag: Mira, Du"), counted[0])
        assertEquals("2 Stimmen für Samstag", counted[0].description)
        assertEquals("1 Stimme für Sonntag", PollAnswer("Sonntag", 1, false, "").description)

        // Invalid votes are ignored: two answers in a single-choice poll, an answer it does not have, an unknown poll.
        mira.forge(id, MessagePayload.vote(poll.signature, listOf(0, 1)))
        jonas.live(id)
        mira.forge(id, MessagePayload.vote(poll.signature, listOf(4)))
        jonas.live(id)
        mira.forge(id, MessagePayload.vote("Z".repeat(86), listOf(0)))
        jonas.live(id)
        assertEquals(2, jonas.state.value.polls.getValue(poll.signature).answers[0].count)

        // Only the creator ends it; votes after that do not count.
        jonas.closePoll(poll)
        assertFalse(jonas.state.value.polls.getValue(poll.signature).closed)
        mira.closePoll(mira.state.value.bubbles.single())
        jonas.live(id)
        jonas.vote(poll, 1)
        val ended = jonas.state.value.polls.getValue(poll.signature)
        assertTrue(ended.closed)
        assertFalse(ended.open)
        assertEquals("Beendet", ended.note)
        assertTrue(ended.answers[0].mine)
        jonas.vote(jonas.state.value.bubbles.single().copy(signature = "nope"), 0)
        clear(mira)
        clear(jonas)
    }

    @Test
    fun withSeveralAnswersEachTogglesAndTheNoteSaysSo() = runTest {
        val (mira, jonas, id) = chat()
        mira.forge(id, MessagePayload.poll("Was nehmen wir mit?", listOf("Brot", "Käse", "Obst"), multiple = true), ChatRepository.MESSAGE)
        jonas.live(id)
        val poll = jonas.state.value.bubbles.single()
        jonas.vote(poll, 2)
        jonas.vote(poll, 0)
        assertEquals(listOf(true, false, true), jonas.state.value.polls.getValue(poll.signature).answers.map { it.mine })
        jonas.vote(poll, 2)
        assertEquals(listOf(true, false, false), jonas.state.value.polls.getValue(poll.signature).answers.map { it.mine })
        assertEquals("Mehrere Antworten möglich", jonas.state.value.polls.getValue(poll.signature).note)
        assertEquals("Mehrere Antworten möglich · Beendet", jonas.state.value.polls.getValue(poll.signature).copy(closed = true).note)
        assertNull(jonas.state.value.polls.getValue(poll.signature).copy(multiple = false).note)
        clear(mira)
        clear(jonas)
    }
}
