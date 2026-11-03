package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.api.ChatAvatar
import de.velwebsolutions.oasis.chat.api.ChatSuggestion
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.MemoryChatPreferences
import de.velwebsolutions.oasis.chat.core.SwipeHintProgress
import de.velwebsolutions.oasis.chat.ui.ChatLayout
import de.velwebsolutions.oasis.chat.ui.ConversationListViewModel
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.chat.ui.TimelineRow
import de.velwebsolutions.oasis.chat.ui.noticePlace
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration as KotlinDuration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The calmer start of round J (docs/oasis-chat-client.md, „Calmer start“): the backup row and the people to write first
 * on the list, „Einmal ansehen“ for the next message, the swipe hint and the places of quiet notices.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CalmerStartTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()
    private val picked = android.net.Uri.parse("content://oasis.test/1")
    private val server get() = support.server

    private fun model(context: ChatContext, id: String) = ConversationViewModel(id, context, FakeRealtime()).apply {
        pollInterval = KotlinDuration.INFINITE
        sweepInterval = KotlinDuration.INFINITE
    }

    private suspend fun chat(owner: ChatContext, vararg others: Int) =
        owner.repository.createConversation("direct", null, others.map(Int::toString), 7).id

    private fun person(id: String, name: String) = buildJsonObject {
        put("id", id)
        put("username", name.lowercase())
        put("display_name", name)
        put("avatar_url", if (id == "2") "/api/v1/network/profiles/jonas/photo/a.jpg" else null)
        put("reason", "Ihr seid befreundet")
    }

    @Test
    fun theBackupRowShowsWithoutAPassphraseCopyUntilLater() = runTest {
        val preferences = MemoryChatPreferences()
        val mira = support.member(1, preferences = preferences)
        val list = ConversationListViewModel(mira, FakeRealtime())
        list.load()
        assertFalse("a passphrase copy is the backup", list.state.value.backupRow)

        mira.vault.setStoredIdentity(mira.vault.storedIdentity!!.copy(lockedIdentity = null))
        list.load()
        assertTrue(list.state.value.backupRow)
        list.snoozeBackupRow()
        assertFalse(list.state.value.backupRow)
        val days = Duration.between(Instant.now(), preferences.backupSnoozes.getValue("1")).toDays()
        assertEquals("„Später“ hides it for 14 days", 13L, days)
        list.load()
        assertFalse(list.state.value.backupRow)
        preferences.backupSnoozes["1"] = Instant.now().minus(Duration.ofDays(1))
        list.load()
        assertTrue("after 14 days it is back", list.state.value.backupRow)
    }

    @Test
    fun anEmptyListOffersPeopleToWriteFirst() = runTest {
        server.suggestions = listOf(person("2", "Jonas"), person("3", "Aylin"), person("4", "Lea"))
        val mira = support.member(1)
        support.member(2)
        val list = ConversationListViewModel(mira, FakeRealtime())
        list.load()
        assertTrue(list.state.value.empty)
        val (jonas, aylin) = list.state.value.suggestions
        assertEquals("at most two", 2, list.state.value.suggestions.size)
        assertEquals(ChatSuggestion("2", "jonas", "Jonas", "/api/v1/network/profiles/jonas/photo/a.jpg", "Ihr seid befreundet"), jonas)
        assertEquals(ChatAvatar(initials = "J", photoUrl = "/api/v1/network/profiles/jonas/photo/a.jpg"), jonas.avatar)
        assertEquals(ChatAvatar(initials = "A"), aylin.avatar)
        assertEquals("without a photo", aylin.copy(avatarUrl = null), ChatSuggestion("3", "aylin", "Aylin", reason = "Ihr seid befreundet"))

        // Without a membership (402) or offline: only „Neuer Chat“.
        server.suggestionsStatus = 402
        list.load()
        assertTrue(list.state.value.suggestions.isEmpty())

        // With a chat, nobody is suggested.
        server.suggestionsStatus = 200
        chat(mira, 2)
        val asked = server.suggestions.size
        list.load()
        assertFalse(list.state.value.empty)
        assertEquals(asked, server.suggestions.size)
    }

    @Test
    fun aListWithOnlyRequestsOrNotLoadedIsNotEmpty() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        chat(mira, 2)
        val asRequest = ConversationListViewModel(jonas, FakeRealtime())
        asRequest.load()
        assertTrue(asRequest.state.value.conversations.isEmpty())
        assertFalse("a request is something to answer", asRequest.state.value.empty)
        server.failures["GET conversations"] = 500 to """{"message":"kaputt"}"""
        val failed = ConversationListViewModel(mira, FakeRealtime())
        failed.load()
        assertFalse(failed.state.value.empty)
    }

    @Test
    fun einmalAnsehenCoversEveryFileOfTheNextMessageAndTurnsOff() = runTest {
        val media = FakeChatMedia()
        val mira = support.member(1, media = media)
        support.member(2)
        val id = chat(mira, 2)
        val writer = model(mira, id)
        writer.open()
        writer.addPicked(picked)
        writer.setViewOnceNext(true)
        assertTrue(writer.state.value.viewOnceNext)
        assertTrue(writer.state.value.drafts.single().viewOnce)
        writer.addPicked(picked)
        writer.startRecording()
        writer.finishRecording()
        assertEquals("also the files added afterwards", listOf(true, true, true), writer.state.value.drafts.map { it.viewOnce })
        writer.setViewOnceNext(false)
        assertEquals(listOf(false, false, false), writer.state.value.drafts.map { it.viewOnce })
        writer.setViewOnceNext(true)
        writer.send()
        assertTrue(writer.state.value.drafts.isEmpty())
        assertFalse("the switch was for that message", writer.state.value.viewOnceNext)
        writer.setViewOnceNext(true)
        writer.close()
        assertFalse(writer.state.value.viewOnceNext)
        assertTrue(media.presented.isEmpty())
    }

    @Test
    fun theSwipeHintShowsInThreeChatsUntilItIsUnderstood() = runTest {
        val preferences = MemoryChatPreferences()
        val mira = support.member(1)
        val jonas = support.member(2, preferences = preferences)
        val chats = listOf(2, 2, 2, 2).map { chat(mira, it) }
        for (id in chats) {
            val writer = model(mira, id)
            writer.open()
            writer.setDraft("Hallo")
            writer.send()
        }
        val empty = chat(jonas, 1)
        val nothingReceived = model(jonas, empty)
        nothingReceived.open()
        assertFalse("no message of someone else, no hint", nothingReceived.state.value.swipeHint)
        assertEquals(SwipeHintProgress(), preferences.hint)

        val first = model(jonas, chats[0])
        first.open()
        assertTrue(first.state.value.swipeHint)
        val newest = first.state.value.bubbles.last()
        assertEquals(newest.id, first.state.value.nudge)
        first.nudged()
        assertNull(first.state.value.nudge)
        model(jonas, chats[1]).open()
        model(jonas, chats[2]).open()
        assertEquals(3, preferences.hint.shown.size)
        val fourth = model(jonas, chats[3])
        fourth.open()
        assertFalse("shown in three chats, then gone", fourth.state.value.swipeHint)
        first.open()
        assertTrue("one of the three still shows it", first.state.value.swipeHint)
        first.dismissSwipeHint()
        assertFalse(first.state.value.swipeHint)
        assertTrue(preferences.hint.done)
        first.dismissSwipeHint()
        first.open()
        assertFalse(first.state.value.swipeHint)
    }

    @Test
    fun theFirstSwipeToAnswerEndsTheHint() = runTest {
        val preferences = MemoryChatPreferences()
        val mira = support.member(1)
        val jonas = support.member(2, preferences = preferences)
        val id = chat(mira, 2)
        val writer = model(mira, id)
        writer.open()
        writer.setDraft("Hallo")
        writer.send()
        // Mira's own messages bring her no hint; swiping does not write anything then.
        writer.swipedReply(writer.state.value.bubbles.single())
        assertEquals("Antwort an dich", writer.state.value.replyBar?.title)
        val reader = model(jonas, id)
        reader.open()
        assertTrue(reader.state.value.swipeHint)
        reader.swipedReply(reader.state.value.bubbles.single())
        assertEquals("Antwort an Mira", reader.state.value.replyBar?.title)
        assertFalse(reader.state.value.swipeHint)
        assertTrue(preferences.hint.done)
    }

    private val envelope = MessageEnvelope(1, "7", 1, "2", "n", "c", "s1", "2100-01-01T00:00:00Z")

    private fun message(signature: String, sent: Instant?) = TimelineRow.Message(
        ChatMessageItem(signature, envelope.copy(signature = signature), "2", null, ChatMessageItem.State.Readable, "", MessageContent("")),
        ChatLayout.Run.Single,
        sent
    )

    @Test
    fun quietNoticesStandWhereTheyHappened() {
        val day = TimelineRow.Day("Gestern", "day-1")
        val rows = listOf(
            day,
            message("a", Instant.parse("2026-11-01T10:00:00Z")),
            message("b", Instant.parse("2026-11-01T12:00:00Z")),
            TimelineRow.Day("Heute", "day-2"),
            TimelineRow.Unread(),
            message("c", Instant.parse("2026-11-02T09:00:00Z")),
            message("d", null)
        )
        assertEquals("between two messages", 2, noticePlace(rows, Instant.parse("2026-11-01T11:00:00Z")))
        assertEquals("before the next day and the divider", 3, noticePlace(rows, Instant.parse("2026-11-01T13:00:00Z")))
        assertEquals("before what is loaded: at the end", rows.size, noticePlace(rows, Instant.parse("2026-10-01T00:00:00Z")))
        assertEquals("after everything: at the end", rows.size, noticePlace(rows, Instant.parse("2026-11-03T00:00:00Z")))
        assertEquals("unknown: at the end", rows.size, noticePlace(rows, null))
        assertEquals(0, noticePlace(emptyList(), Instant.now()))
    }
}
