package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.live.api.LiveCountsData
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers at the bell and "Chats", and the in-app chat notice. */
class LiveCountsTest {
    private fun chat(conversation: String, from: String?) = RealtimeEvent(
        "InboxActivity",
        JsonObject(
            mapOf(
                "kind" to JsonPrimitive("chat"),
                "conversation_id" to JsonPrimitive(conversation),
                "from" to (from?.let(::JsonPrimitive) ?: JsonNull)
            )
        )
    )

    private fun TestScope.counts() = LiveCounts(backgroundScope, pollInterval = 1.hours)

    @Test
    fun loadsCountsAndRefreshesOnInboxActivity() = runTest {
        val realtime = FakeRealtime()
        val counts = counts()
        var answer = LiveCountsData(chats = 2, hints = 3)
        counts.start(load = { answer }, realtime = realtime, userId = 5)
        runCurrent()
        assertEquals(3, counts.hints.value)
        assertEquals(2, counts.chats.value)
        assertEquals(listOf("private-inbox.5"), realtime.channels)

        answer = LiveCountsData(chats = 3, hints = 3)
        realtime.events.emit(chat("8", "Jonas"))
        runCurrent()
        assertEquals("Neue Nachricht von Jonas", counts.toast.value?.text)
        assertEquals(8, counts.toast.value?.conversationId)
        assertEquals(3, counts.chats.value)

        counts.dismissToast()
        counts.isShowingChats = true
        realtime.events.emit(chat("8", null))
        runCurrent()
        assertNull("no notice while the chats are open", counts.toast.value)

        counts.stop()
        assertEquals(0, counts.hints.value)
        assertEquals(0, counts.chats.value)
    }

    @Test
    fun showsANamelessNoticeWithoutSenderAndNoneForHints() = runTest {
        val counts = counts()
        counts.handle(chat("2", null))
        assertEquals("Neue Chat-Nachricht", counts.toast.value?.text)
        counts.dismissToast()
        counts.handle(RealtimeEvent("InboxActivity", JsonObject(mapOf("kind" to JsonPrimitive("hint")))))
        assertNull(counts.toast.value)
    }

    @Test
    fun theNoticeGoesAfterFifteenSecondsAndThePollingRepeats() = runTest {
        var loads = 0
        val counts = LiveCounts(backgroundScope, pollInterval = 30.seconds)
        counts.start(load = { LiveCountsData(chats = 0, hints = ++loads) }, realtime = FakeRealtime(), userId = 1)
        runCurrent()
        assertEquals(1, counts.hints.value)
        advanceTimeBy(30.seconds + 1.seconds)
        assertEquals("every 30 seconds as a fallback", 2, counts.hints.value)

        counts.handle(chat("2", "Lea"))
        advanceTimeBy(14.seconds)
        assertEquals("Neue Nachricht von Lea", counts.toast.value?.text)
        advanceTimeBy(2.seconds)
        assertNull(counts.toast.value)
        counts.stop()
    }

    @Test
    fun ignoresOtherEventsAndNoticesWithoutAConversation() = runTest {
        val counts = counts()
        counts.handle(RealtimeEvent("MessageSent", JsonObject(mapOf("kind" to JsonPrimitive("chat")))))
        assertNull(counts.toast.value)
        counts.handle(RealtimeEvent("InboxActivity", JsonObject(mapOf("kind" to JsonPrimitive("chat")))))
        assertEquals("Neue Chat-Nachricht", counts.toast.value?.text)
        assertNull("without conversation the notice opens the chat list", counts.toast.value?.conversationId)
        counts.handle(chat("keine Zahl", "Mia"))
        assertNull(counts.toast.value?.conversationId)
        counts.dismissToast()
        counts.dismissToast()
        assertNull(counts.toast.value)
    }

    @Test
    fun aNewerNoticeKeepsItsOwnFifteenSeconds() = runTest {
        val counts = counts()
        counts.handle(chat("1", "Lea"))
        advanceTimeBy(10.seconds)
        counts.handle(chat("2", "Jonas"))
        advanceTimeBy(10.seconds)
        assertEquals("Neue Nachricht von Jonas", counts.toast.value?.text)
        advanceTimeBy(6.seconds)
        assertNull(counts.toast.value)
    }

    @Test
    fun keepsTheNumbersWhenLoadingFailsAndDropsAnswersAfterStopping() = runTest {
        val counts = counts()
        counts.refresh()
        assertEquals("nothing to load before start", 0, counts.hints.value)
        var fail = false
        lateinit var stopWhileLoading: () -> Unit
        var stopNow = false
        counts.start(
            load = {
                if (fail) error("offline")
                if (stopNow) stopWhileLoading()
                LiveCountsData(chats = 1, hints = 4)
            },
            realtime = FakeRealtime(),
            userId = 3
        )
        runCurrent()
        assertEquals(4, counts.hints.value)
        fail = true
        counts.refresh()
        assertEquals("a failed refresh keeps the numbers", 4, counts.hints.value)
        fail = false
        stopNow = true
        stopWhileLoading = { counts.stop() }
        counts.refresh()
        assertEquals("an answer after sign-out is dropped", 0, counts.hints.value)
    }

    @Test
    fun cancellationOfARefreshPassesThrough() = runTest {
        val counts = counts()
        counts.start(load = { throw kotlinx.coroutines.CancellationException("gone") }, realtime = FakeRealtime(), userId = 1)
        val failure = runCatching { counts.refresh() }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        counts.stop()
    }
}
