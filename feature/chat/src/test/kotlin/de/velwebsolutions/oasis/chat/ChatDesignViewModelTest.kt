package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.ui.ConversationListViewModel
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.chat.ui.TimelineRow
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The view-model side of the chat design (docs/mildcrowd-chat-client.md, „Chat design and gestures“): the header's status,
 * the „Neue Nachrichten“ divider from `last_read_at`, the list's previews kept in memory, last activity and row actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDesignViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()
    private val server get() = support.server

    private fun model(context: ChatContext, id: String) = ConversationViewModel(id, context, FakeRealtime()).apply {
        pollInterval = Duration.INFINITE
        sweepInterval = Duration.INFINITE
    }

    private suspend fun chat(owner: ChatContext, vararg others: Int, type: String = "direct") =
        owner.repository.createConversation(type, null, others.map(Int::toString), 7).id

    @Test
    fun theHeaderTheDividerAndThePreview() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = chat(mira, 2)
        val owner = model(mira, id)
        owner.open()
        assertEquals("Ende-zu-Ende verschlüsselt · löschen sich nach 7 Tagen", owner.state.value.headerStatus)
        owner.setDraft("Erste")
        owner.send()
        val list = ConversationListViewModel(mira, FakeRealtime())
        list.load()
        val row = list.state.value.conversations.single()
        assertEquals("the own last message, kept in memory only", "Du: Erste", list.preview(row))

        // Jonas last had the chat open before Mira's message: the divider stands before it, taken once per opening.
        server.conversations.getValue(id.toInt()).lastReadAt[2] = "2000-01-01T00:00:00Z"
        val reader = model(jonas, id)
        reader.open()
        assertEquals(listOf(TimelineRow.Unread::class, TimelineRow.Message::class), reader.state.value.rows.drop(1).map { it::class })
        server.conversations.getValue(id.toInt()).lastReadAt[2] = "2100-01-01T00:00:00Z"
        reader.refresh()
        assertTrue("still the reading of the opening", reader.state.value.rows.any { it is TimelineRow.Unread })
        reader.open()
        assertFalse("opened again: the new reading", reader.state.value.rows.any { it is TimelineRow.Unread })
        assertEquals(1, reader.quoteTarget(reader.state.value.bubbles.single().signature))
        assertNull(reader.quoteTarget("unknown"))

        // „Sperren“ forgets every preview.
        mira.vault.lock()
        assertEquals("Verschlüsselte Nachricht", list.preview(row))
        clear(owner)
        clear(reader)
    }

    @Test
    fun aPartnersStatusStandsInTheHeader() = runTest {
        val mira = support.member(1)
        support.member(2)
        val id = chat(mira, 2)
        server.statuses[2] = "Im Urlaub"
        val owner = model(mira, id)
        owner.open()
        assertEquals("Im Urlaub", owner.state.value.headerStatus)
        assertEquals("", model(mira, "999").state.value.headerStatus)
        clear(owner)
    }

    @Test
    fun rowsAreMarkedReadMutedAndLeft() = runTest {
        val mira = support.member(1)
        support.member(2)
        support.member(3)
        val direct = chat(mira, 2)
        val group = chat(mira, 2, 3, type = "group")
        server.conversations.getValue(direct.toInt()).apply {
            unread += 1
            lastActivityAt = "2026-11-03T12:00:00Z"
        }
        val list = ConversationListViewModel(mira, FakeRealtime())
        list.load()
        fun row(id: String) = list.state.value.conversations.single { it.id == id }
        assertTrue(list.activity(row(direct)).isNotEmpty())
        assertEquals("", list.activity(row(group)))
        assertTrue(list.canLeave(row(direct)))
        assertFalse("the owner of a group removes members instead", list.canLeave(row(group)))
        assertTrue("a member of someone else's group may leave", list.canLeave(row(group).copy(createdBy = "2")))

        list.markRead(row(direct))
        assertEquals("Als gelesen markiert.", list.state.value.status)
        assertEquals(false, row(direct).unread)
        list.toggleMute(row(direct))
        assertEquals("Stummgeschaltet.", list.state.value.status)
        assertEquals("permanently", "", server.conversations.getValue(direct.toInt()).mutedUntil[1])
        assertEquals(true, row(direct).muted)
        list.toggleMute(row(direct))
        assertEquals("Ton ist wieder an.", list.state.value.status)
        assertEquals(false, row(direct).muted)

        server.failures["POST conversations/$direct/read"] = 500 to """{"message":"kaputt"}"""
        list.markRead(row(direct))
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", list.state.value.actionError)
        list.dismissActionError()

        list.leave(row(direct))
        assertEquals("Chat verlassen.", list.state.value.status)
        assertEquals(listOf(group), list.state.value.conversations.map { it.id })
    }

    @Test
    fun aPageDecryptedWhileTheChatClosesIsDropped() = runTest {
        val mira = support.member(1)
        support.member(2)
        val id = chat(mira, 2)
        val owner = model(mira, id)
        owner.open()
        owner.setDraft("Hallo")
        owner.send()
        clear(owner)
        lateinit var reader: ConversationViewModel
        var armed = false
        val closing = support.context(1, vault = mira.vault, cryptoDispatcher = HookedDispatcher { if (armed) reader.close() })
        reader = model(closing, id)
        reader.open()
        assertEquals(
            reader.state.value.let {
                "${it.loadFailed} ${it.notices} ${it.timeline.items.size}"
            },
            1,
            reader.state.value.bubbles.size
        )
        armed = true
        reader.loadOlder()
        assertTrue("a page decrypted while the chat closed is dropped", reader.state.value.bubbles.isEmpty())
        clear(reader)
    }
}
