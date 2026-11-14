package de.velwebsolutions.oasis.chat

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.chat.api.ChatAvatar
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.ui.ChatLayout
import de.velwebsolutions.oasis.chat.ui.ChatLayout.Run
import de.velwebsolutions.oasis.chat.ui.ChatSwipe
import de.velwebsolutions.oasis.chat.ui.ENCRYPTED_PREVIEW
import de.velwebsolutions.oasis.chat.ui.Newest
import de.velwebsolutions.oasis.chat.ui.QuoteTexts
import de.velwebsolutions.oasis.chat.ui.RowRelease
import de.velwebsolutions.oasis.chat.ui.SwipeTracker
import de.velwebsolutions.oasis.chat.ui.TimelineRow
import de.velwebsolutions.oasis.chat.ui.awayFromBottom
import de.velwebsolutions.oasis.chat.ui.bubbleShape
import de.velwebsolutions.oasis.chat.ui.hasTail
import de.velwebsolutions.oasis.chat.ui.headerStatus
import de.velwebsolutions.oasis.chat.ui.latestPreview
import de.velwebsolutions.oasis.chat.ui.newest
import de.velwebsolutions.oasis.chat.ui.previewOf
import de.velwebsolutions.oasis.chat.ui.rowGap
import de.velwebsolutions.oasis.chat.ui.rowOffset
import de.velwebsolutions.oasis.chat.ui.timelineRows
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.MessagePoll
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat's layout rules, texts and swipe thresholds (docs/mildcrowd-chat-client.md, „Chat design and gestures“). */
class ChatDesignTest {
    private val now = Instant.parse("2026-11-03T13:00:00Z")

    private fun item(signature: String, sender: String, sent: String?, text: String = "Hallo", lifetime: Long = 7) = ChatMessageItem(
        signature,
        MessageEnvelope(1, "7", 1, sender, "n", "c", signature, null),
        sender,
        sent?.let { Instant.parse(it).plusSeconds(lifetime * 86_400) },
        ChatMessageItem.State.Readable,
        text,
        MessageContent(text)
    )

    @Test
    fun runsJoinOneSenderWithinFiveMinutesOnOneDay() {
        val times = listOf(
            "2026-11-03T09:00:00Z",
            "2026-11-03T09:04:00Z",
            "2026-11-03T09:08:00Z",
            "2026-11-03T09:20:00Z",
            null,
            "2026-11-03T22:58:00Z",
            "2026-11-03T23:02:00Z"
        )
            .map { it?.let(Instant::parse) }
        val senders = listOf("2", "2", "2", "1", "1", "1", "1")
        val placements = ChatLayout.layout(senders, times)
        assertEquals(listOf(Run.First, Run.Middle, Run.Last, Run.Single, Run.Single, Run.Single, Run.Single), placements.map { it.run })
        // A day pill before the first message, after a message without a time, and when the German day changes (23:58 → 00:02).
        assertEquals(listOf(true, false, false, false, false, true, true), placements.map { it.day })
        assertEquals(Run.First, ChatLayout.layout(listOf("2", "2"), times.take(2)).first().run)
        assertEquals(
            "a gap of five minutes ends the run",
            Run.Single,
            ChatLayout.layout(listOf("2", "2"), listOf(times[0], times[0]!!.plusSeconds(300))).first().run
        )
    }

    @Test
    fun theDividerStandsBeforeTheFirstNewMessageOfOthers() {
        val times = listOf("2026-11-03T09:00:00Z", "2026-11-03T10:00:00Z", "2026-11-03T11:00:00Z").map(Instant::parse)
        val senders = listOf("2", "1", "2")
        val readAt = Instant.parse("2026-11-03T09:30:00Z")
        assertEquals("the own message does not count", 2, ChatLayout.firstUnread(senders, times, readAt, "1"))
        assertEquals(-1, ChatLayout.firstUnread(senders, times, null, "1"))
        assertEquals(-1, ChatLayout.firstUnread(senders, listOf(null, null, null), readAt, "1"))

        val rows = timelineRows(
            listOf(
                item("a", "2", "2026-11-02T09:00:00Z"),
                item("b", "2", "2026-11-03T09:00:00Z"),
                item("c", "2", "2026-11-03T12:00:00Z"),
                item("d", "1", null)
            ),
            7,
            Instant.parse("2026-11-03T10:00:00Z"),
            "1",
            now
        )
        assertEquals(
            listOf("day-2026-11-02", "a", "day-2026-11-03", "b", "unread", "c", "d"),
            rows.map { it.key }
        )
        assertEquals("Gestern", (rows[0] as TimelineRow.Day).label)
        assertEquals("Heute", (rows[2] as TimelineRow.Day).label)
        assertEquals(Instant.parse("2026-11-03T12:00:00Z"), (rows[5] as TimelineRow.Message).sent)
    }

    @Test
    fun daysAndTimesReadAsOnTheWeb() {
        assertEquals("14:05", ChatLayout.clockTime(Instant.parse("2026-11-03T13:05:00Z")))
        assertEquals("Heute", ChatLayout.dayLabel(now, now))
        assertEquals("Gestern", ChatLayout.dayLabel(Instant.parse("2026-11-02T10:00:00Z"), now))
        assertEquals("Sonntag, 1. November", ChatLayout.dayLabel(Instant.parse("2026-11-01T10:00:00Z"), now))
        assertEquals("Sonntag, 2. November 2025", ChatLayout.dayLabel(Instant.parse("2025-11-02T10:00:00Z"), now))
        assertEquals("14:00", ChatLayout.activityLabel(now, now))
        assertEquals("a time ahead of the clock reads as today", "15:00", ChatLayout.activityLabel(now.plusSeconds(3_600), now))
        assertEquals("Gestern", ChatLayout.activityLabel(Instant.parse("2026-11-02T10:00:00Z"), now))
        assertEquals("Freitag", ChatLayout.activityLabel(Instant.parse("2026-10-30T10:00:00Z"), now))
        assertEquals("27.10.26", ChatLayout.activityLabel(Instant.parse("2026-10-27T10:00:00Z"), now))
        assertEquals(
            "Gesendet heute, 13:00 · löscht sich am 10.11.2026 um 14:00",
            ChatLayout.infoLine(Instant.parse("2026-11-03T12:00:00Z"), Instant.parse("2026-11-10T13:00:00Z"), now)
        )
        assertEquals(
            "Gesendet gestern, 18:34 · löscht sich am 9.11.2026 um 18:34",
            ChatLayout.infoLine(Instant.parse("2026-11-02T17:34:00Z"), Instant.parse("2026-11-09T17:34:00Z"), now)
        )
        assertEquals(
            "Gesendet 1.11.2026, 09:05 · löscht sich am 8.11.2026 um 09:05",
            ChatLayout.infoLine(Instant.parse("2026-11-01T08:05:00Z"), Instant.parse("2026-11-08T08:05:00Z"), now)
        )
        assertEquals("", ChatLayout.infoLine(null, null, now))
        assertEquals("Nach unten", ChatLayout.scrollDownLabel(0))
        assertEquals("Nach unten, 1 neue Nachricht", ChatLayout.scrollDownLabel(1))
        assertEquals("Nach unten, 3 neue Nachrichten", ChatLayout.scrollDownLabel(3))
        assertEquals(null, ChatLayout.sentAt(item("x", "2", null), 7))
    }

    @Test
    fun previewsSayWhatTheMessageHeld() {
        assertEquals("Du: Bis gleich", previewOf(item("a", "1", null, "Bis   gleich\n"), mine = true))
        val poll = item("p", "2", null, "").copy(content = MessageContent("", poll = MessagePoll("Wann?", listOf("a", "b"), false)))
        assertEquals("Umfrage: Wann?", previewOf(poll, mine = false))
        val photo = item(
            "f",
            "2",
            null,
            ""
        ).copy(content = MessageContent("", attachments = listOf(MessageAttachment("1", "image", "image/jpeg", 3, "k", "h"))))
        assertEquals("Foto", previewOf(photo, mine = false))
        assertEquals("", previewOf(item("e", "2", null, ""), mine = false))
        val long = "ä".repeat(81)
        assertEquals("ä".repeat(80) + "…", previewOf(item("l", "2", null, long), mine = false))
        assertEquals("Verschlüsselte Nachricht", ENCRYPTED_PREVIEW)
        assertEquals("", QuoteTexts.label("sticker"))
    }

    @Test
    fun swipesNeedADistinctHorizontalDrag() {
        assertFalse("below the slop", ChatSwipe.horizontal(6f, 0f))
        assertFalse("too steep", ChatSwipe.horizontal(12f, 11f))
        assertTrue(ChatSwipe.horizontal(12f, 2f))
        assertTrue(ChatSwipe.horizontal(-12f, 2f))
        assertEquals(0f, ChatSwipe.replyOffset(-20f))
        assertEquals(30f, ChatSwipe.replyOffset(30f))
        assertEquals(52f, ChatSwipe.replyOffset(58f), 0.001f)
        assertEquals(64f, ChatSwipe.replyOffset(200f))
        assertEquals(0.5f, ChatSwipe.progress(24f))
        assertEquals(1f, ChatSwipe.progress(90f))
        assertEquals(-160f, ChatSwipe.rowOffset(-300f, unread = false))
        assertEquals(96f, ChatSwipe.rowOffset(300f, unread = true))
        assertEquals(0f, ChatSwipe.rowOffset(50f, unread = false))
        assertEquals(RowRelease.Open, ChatSwipe.release(-70f, unread = false))
        assertEquals(RowRelease.Read, ChatSwipe.release(70f, unread = true))
        assertEquals(RowRelease.Close, ChatSwipe.release(70f, unread = false))
        assertEquals(RowRelease.Close, ChatSwipe.release(-30f, unread = true))
    }

    @Test
    fun cornersTailAndGapsFollowTheRun() {
        fun corners(mine: Boolean, run: Run) = bubbleShape(mine, run).let { shape ->
            listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart)
        }
        val (outer, inner, tail) = listOf(CornerSize(20.dp), CornerSize(6.dp), CornerSize(4.dp))
        assertEquals(listOf(outer, outer, tail, outer), corners(mine = true, Run.Single))
        assertEquals(listOf(outer, outer, inner, outer), corners(mine = true, Run.First))
        assertEquals(listOf(outer, inner, inner, outer), corners(mine = true, Run.Middle))
        assertEquals(listOf(outer, inner, tail, outer), corners(mine = true, Run.Last))
        assertEquals(listOf(inner, outer, outer, tail), corners(mine = false, Run.Last))
        assertEquals(listOf(outer, outer, outer, inner), corners(mine = false, Run.First))
        assertTrue(hasTail(Run.Single) && hasTail(Run.Last) && !hasTail(Run.First) && !hasTail(Run.Middle))
        assertEquals(2.dp, rowGap(Run.Middle))
        assertEquals(2.dp, rowGap(Run.Last))
        assertEquals(12.dp, rowGap(Run.First))
        assertEquals(12.dp, rowGap(Run.Single))
        assertEquals("a day pill or „Neue Nachrichten“ keeps the distance", 0.dp, rowGap(Run.Single, afterSeparator = true))
        assertEquals(30, rowOffset(15f, 2f).x)
        assertEquals("unread", TimelineRow.Unread().key)
    }

    @Test
    fun theTrackerTellsSwipesFromScrolls() {
        val swipe = SwipeTracker { it > 0f }
        assertFalse("below the slop", swipe.move(4f, 0f))
        assertFalse(swipe.crossed(ChatSwipe.REPLY))
        assertTrue(swipe.move(10f, 1f))
        assertFalse(swipe.crossed(ChatSwipe.REPLY))
        assertTrue(swipe.move(40f, 0f))
        assertTrue("one tick at 48", swipe.crossed(ChatSwipe.REPLY))
        assertFalse("only once", swipe.crossed(ChatSwipe.REPLY))
        assertEquals(54f, swipe.dx)
        val left = SwipeTracker { it > 0f }
        assertFalse("the wrong way", left.move(-20f, 0f))
        assertTrue(left.rejected)
        assertFalse(left.move(50f, 0f))
        val steep = SwipeTracker()
        assertFalse(steep.move(8f, 20f))
        assertTrue(steep.rejected && !steep.swiping)
        assertTrue(SwipeTracker().move(-20f, 0f))
    }

    @Test
    fun theButtonDownAndTheNewestMessage() {
        assertFalse("at the newest message", awayFromBottom(0, 0, 160f))
        assertFalse(awayFromBottom(0, 150, 160f))
        assertTrue("more than 160 above it", awayFromBottom(0, 170, 160f))
        assertTrue("another row first", awayFromBottom(3, 0, 160f))
        assertEquals(Newest.Jump, newest(first = true, away = true, fromOthers = true))
        assertEquals(Newest.Count, newest(first = false, away = true, fromOthers = true))
        assertEquals("the own message scrolls down", Newest.Follow, newest(first = false, away = true, fromOthers = false))
        assertEquals(Newest.Follow, newest(first = false, away = false, fromOthers = true))
    }

    @Test
    fun headerAndPreviewOfAChat() {
        val members = listOf(ConversationMember("1", "mira", "Mira"), ConversationMember("2", "jonas", "Jonas", status = "Im Garten"))
        val direct = ChatConversation("7", "direct", null, "1", 30, 1, false, members, "")
        assertEquals("Im Garten", headerStatus(direct, "1"))
        assertEquals("Ende-zu-Ende verschlüsselt · löschen sich nach 30 Tagen", headerStatus(direct.copy(type = "group"), "1"))
        assertEquals("", headerStatus(null, "1"))
        val unreadable = item("x", "2", null).copy(state = ChatMessageItem.State.NoKey)
        assertEquals("Du: Hallo", latestPreview(listOf(item("a", "1", null), unreadable), "1"))
        assertEquals(null, latestPreview(listOf(unreadable), "1"))
    }

    @Test
    fun aPhotoKeepsTheSizeItsMessageNames() {
        val photo = MessageAttachment("1", "image", "image/jpeg", 3, "k", "n", width = 1200, height = 900)
        assertEquals(4f / 3f, de.velwebsolutions.oasis.chat.ui.photoRatio(photo)!!, 0.001f)
        assertEquals(null, de.velwebsolutions.oasis.chat.ui.photoRatio(photo.copy(width = null)))
        assertEquals(null, de.velwebsolutions.oasis.chat.ui.photoRatio(photo.copy(height = null)))
        assertEquals(null, de.velwebsolutions.oasis.chat.ui.photoRatio(photo.copy(width = 0)))
        assertEquals(null, de.velwebsolutions.oasis.chat.ui.photoRatio(photo.copy(height = 0)))
    }

    @Test
    fun aBubbleWithoutItsChatShowsNoName() {
        val state = de.velwebsolutions.oasis.chat.ui.ConversationState(
            timeline = de.velwebsolutions.oasis.chat.core.ChatTimeline(listOf(item("a", "2", null)))
        )
        val rows = de.velwebsolutions.oasis.chat.ui.rowModels(state, "1", { "Jonas" }, { ChatAvatar.Unknown }, { null }, now)
        val bubble = (rows.single() as de.velwebsolutions.oasis.chat.ui.RowModel.Bubble).model
        assertFalse(bubble.showName)
        assertEquals("theirs", rows.single().contentType)
        assertTrue(rows.single().hasFiles.not())
        assertEquals("unread", de.velwebsolutions.oasis.chat.ui.RowModel.Unread.contentType)
    }
}
