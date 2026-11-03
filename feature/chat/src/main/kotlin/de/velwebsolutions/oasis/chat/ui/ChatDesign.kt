package de.velwebsolutions.oasis.chat.ui

import androidx.compose.runtime.Immutable
import de.velwebsolutions.oasis.chat.api.ChatAvatar
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.core.ChatKeyring
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatPreferences
import de.velwebsolutions.oasis.chat.core.ChatVault
import de.velwebsolutions.oasis.chat.core.lifetimeLabel
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.network.OasisJson
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * How the conversation is laid out (docs/oasis-chat-client.md, „Chat design and gestures“; the web's
 * timeline-layout.js): runs of one sender, day separators, the „Neue Nachrichten“ divider, times and the list's last
 * activity. Pure functions, the same rules as the web and iOS.
 */
object ChatLayout {
    /** Messages of one sender closer than this belong to one run. */
    val RUN_GAP: Duration = Duration.ofMinutes(5)

    /** The place of a message in its run of one sender. */
    enum class Run { Single, First, Middle, Last }

    /** A message's run and whether a day separator comes before it. */
    data class Placement(val run: Run, val day: Boolean)

    /** When a message was sent: its authenticated expiry minus the chat's lifetime; null without a valid expiry. */
    fun sentAt(item: ChatMessageItem, lifetimeDays: Int): Instant? = item.expiresAt?.minus(Duration.ofDays(lifetimeDays.toLong()))

    /** The calendar day in German time. */
    fun localDay(instant: Instant): LocalDate = instant.atZone(GermanTime.zone).toLocalDate()

    private fun daysBetween(earlier: Instant, later: Instant): Long = ChronoUnit.DAYS.between(localDay(earlier), localDay(later))

    /** „14:05“. */
    fun clockTime(instant: Instant): String = GermanTime.format(instant, "HH:mm")

    /** Day separator: „Heute“, „Gestern“, „Montag, 2. November“ (with the year when not the current one). */
    fun dayLabel(instant: Instant, now: Instant): String = when (daysBetween(instant, now)) {
        0L -> "Heute"
        1L -> "Gestern"
        else -> GermanTime.format(instant, if (localDay(instant).year == localDay(now).year) "EEEE, d. MMMM" else "EEEE, d. MMMM yyyy")
    }

    /** The list's last activity: „14:05“ today, „Gestern“, the weekday within the last 6 days, else „02.11.26“. */
    fun activityLabel(instant: Instant, now: Instant): String {
        val days = daysBetween(instant, now)
        return when {
            days <= 0 -> clockTime(instant)
            days == 1L -> "Gestern"
            days < 7 -> GermanTime.format(instant, "EEEE")
            else -> GermanTime.format(instant, "dd.MM.yy")
        }
    }

    /** The place of each message (senders and sent times in time order; a message without a time stands alone). */
    fun layout(senders: List<String>, times: List<Instant?>): List<Placement> {
        fun together(a: Int, b: Int): Boolean {
            val first = times[a] ?: return false
            val second = times[b] ?: return false
            return senders[a] == senders[b] && Duration.between(first, second) < RUN_GAP && localDay(first) == localDay(second)
        }
        return senders.indices.map { index ->
            val withPrevious = index > 0 && together(index - 1, index)
            val withNext = index < senders.lastIndex && together(index, index + 1)
            val run = when {
                withPrevious && withNext -> Run.Middle
                withPrevious -> Run.Last
                withNext -> Run.First
                else -> Run.Single
            }
            val time = times[index]
            val previous = times.getOrNull(index - 1)
            Placement(run, time != null && (previous == null || localDay(previous) != localDay(time)))
        }
    }

    /** The index of the first message of others sent after [readAt] (the „Neue Nachrichten“ divider), or -1. */
    fun firstUnread(senders: List<String>, times: List<Instant?>, readAt: Instant?, myId: String): Int {
        if (readAt == null) return -1
        return senders.indices.firstOrNull { senders[it] != myId && times[it]?.isAfter(readAt) == true } ?: -1
    }

    /** „Nach unten“, „Nach unten, 1 neue Nachricht“, „Nach unten, 3 neue Nachrichten“. */
    fun scrollDownLabel(count: Int): String = when (count) {
        0 -> "Nach unten"
        1 -> "Nach unten, 1 neue Nachricht"
        else -> "Nach unten, $count neue Nachrichten"
    }

    /**
     * The long-press bar's info line and a message's text for screen readers: „Gesendet heute, 18:34 · löscht sich am
     * 10.11.2026 um 18:34“, before yesterday „Gesendet 2.11.2026, 09:05 · …“.
     */
    fun infoLine(sent: Instant?, expires: Instant?, now: Instant): String = listOfNotNull(
        sent?.let { "Gesendet ${sentDay(it, now)}, ${clockTime(it)}" },
        expires?.let { "löscht sich am ${GermanTime.format(it, "d.M.yyyy")} um ${clockTime(it)}" }
    ).joinToString(" · ")

    /** The day in the info line: „heute“, „gestern“, else „2.11.2026“. */
    private fun sentDay(instant: Instant, now: Instant): String = when (daysBetween(instant, now)) {
        0L -> "heute"
        1L -> "gestern"
        else -> GermanTime.format(instant, "d.M.yyyy")
    }
}

/** One row of the message list: a day pill, the „Neue Nachrichten“ divider or a bubble with its place in its run. */
sealed interface TimelineRow {
    val key: String

    data class Day(val label: String, override val key: String) : TimelineRow

    data class Unread(override val key: String = "unread") : TimelineRow

    data class Message(val item: ChatMessageItem, val run: ChatLayout.Run, val sent: Instant?) : TimelineRow {
        override val key: String get() = item.id
    }
}

/** The header bar's line under the name: a 1:1 partner's status, else the encryption and the lifetime; empty without a chat. */
internal fun headerStatus(conversation: ChatConversation?, myId: String): String = conversation?.let {
    it.directStatus(myId) ?: "Ende-zu-Ende verschlüsselt · löschen sich nach ${lifetimeLabel(it.lifetimeDays)}"
}.orEmpty()

/** The list's line 2 for the newest readable of [bubbles], or null without one. */
internal fun latestPreview(bubbles: List<ChatMessageItem>, myId: String): String? =
    bubbles.lastOrNull { it.state == ChatMessageItem.State.Readable }?.let { previewOf(it, it.senderId == myId) }

/** The row of the message with [signature] among these rows, or null while it is not loaded. */
internal fun List<TimelineRow>.indexOfMessage(signature: String): Int? =
    indexOfFirst { (it as? TimelineRow.Message)?.item?.signature == signature }.takeIf { it >= 0 }

/** „Nachrichtenanfrage von Jonas“ above a request ([conversation] the member has not accepted); null otherwise. */
internal fun requestTitle(conversation: ChatConversation?, myId: String): String? = conversation?.takeIf { it.request == true }?.let {
    val others = it.members.filter { member -> member.userId != myId }
    "Nachrichtenanfrage von ${if (others.size == 1) others[0].name else "Jemand"}"
}

/** The rows of [bubbles] (time order) for the member [myId]: day pills, the divider after [readAt], the bubbles. */
internal fun timelineRows(
    bubbles: List<ChatMessageItem>,
    lifetimeDays: Int,
    readAt: Instant?,
    myId: String,
    now: Instant
): List<TimelineRow> {
    val senders = bubbles.map { it.senderId }
    val times = bubbles.map { ChatLayout.sentAt(it, lifetimeDays) }
    val placements = ChatLayout.layout(senders, times)
    val unread = ChatLayout.firstUnread(senders, times, readAt, myId)
    return buildList {
        bubbles.forEachIndexed { index, item ->
            dayRow(times[index], placements[index].day, now)?.let(::add)
            if (index == unread) add(TimelineRow.Unread())
            add(TimelineRow.Message(item, placements[index].run, times[index]))
        }
    }
}

/** The day pill before a message sent at [time] when a [day] begins there. */
private fun dayRow(time: Instant?, day: Boolean, now: Instant): TimelineRow.Day? =
    time?.takeIf { day }?.let { TimelineRow.Day(ChatLayout.dayLabel(it, now), "day-${ChatLayout.localDay(it)}") }

/** The list's line 2 of a message this device decrypted: „Du: “ before own ones, „Umfrage: …“, a file's kind; 80 at most. */
internal fun previewOf(item: ChatMessageItem, mine: Boolean): String {
    val text = (item.content.poll?.let { "Umfrage: ${it.question}" } ?: item.content.text).replace(Regex("\\s+"), " ").trim()
    val shown = text.ifEmpty { item.content.attachments.firstOrNull()?.let { QuoteTexts.label(it.kind) }.orEmpty() }
    val cut = if (shown.codePointCount(0, shown.length) > PREVIEW_LENGTH) {
        shown.substring(0, shown.offsetByCodePoints(0, PREVIEW_LENGTH)) + "…"
    } else {
        shown
    }
    return (if (mine) "Du: " else "") + cut
}

private const val PREVIEW_LENGTH = 80

/** Line 2 of a chat whose last message this device did not decrypt in this session. */
internal const val ENCRYPTED_PREVIEW = "Verschlüsselte Nachricht"

/**
 * The thresholds of the swipes (docs/oasis-chat-client.md, „Gestures“), in dp: a swipe counts once it moved [SLOP] and
 * is horizontal-dominant; anything else stays a vertical scroll.
 */
internal object ChatSwipe {
    const val SLOP = 10f
    const val REPLY = 48f
    const val REPLY_MAX = 64f
    const val RESISTANCE = 0.4f
    const val ROW_OPEN = 64f
    const val ROW_ACTIONS = 160f
    const val ROW_READ_MAX = 96f

    /** Whether a drag of [dx]/[dy] is a swipe: past the slop and |dx| > 1.2 × |dy|. */
    fun horizontal(dx: Float, dy: Float): Boolean = hypot(dx, dy) >= SLOP && abs(dx) > 1.2f * abs(dy)

    /** A bubble's offset: 1:1 up to [REPLY], then with resistance up to at most [REPLY_MAX]; never to the left. */
    fun replyOffset(dx: Float): Float = when {
        dx <= 0f -> 0f
        dx <= REPLY -> dx
        else -> min(REPLY + (dx - REPLY) * RESISTANCE, REPLY_MAX)
    }

    /** The reply icon's progress 0…1 (opacity and scale follow it). */
    fun progress(dx: Float): Float = (dx / REPLY).coerceIn(0f, 1f)

    /** A list row's offset while dragged: left down to the open actions, right only while unread up to [ROW_READ_MAX]. */
    fun rowOffset(dx: Float, unread: Boolean): Float = when {
        dx < 0f -> dx.coerceAtLeast(-ROW_ACTIONS)
        unread -> dx.coerceAtMost(ROW_READ_MAX)
        else -> 0f
    }

    /** What releasing a row at [dx] does. */
    fun release(dx: Float, unread: Boolean): RowRelease = when {
        dx <= -ROW_OPEN -> RowRelease.Open
        unread && dx >= ROW_OPEN -> RowRelease.Read
        else -> RowRelease.Close
    }
}

/**
 * Follows one drag in dp: waiting below the slop, then a swipe in an [allowed] direction (horizontal-dominant), or not
 * one at all – then the drag stays a vertical scroll.
 */
internal class SwipeTracker(private val allowed: (dx: Float) -> Boolean = { true }) {
    var dx = 0f
        private set
    private var dy = 0f
    private var ticked = false

    /** Whether the drag became a swipe. */
    var swiping = false
        private set

    /** Whether the drag is not a swipe (too steep or the wrong way). */
    var rejected = false
        private set

    /** Adds a move; returns whether it belongs to the swipe (the caller consumes it). */
    fun move(x: Float, y: Float): Boolean {
        dx += x
        dy += y
        if (!swiping && !rejected && hypot(dx, dy) >= ChatSwipe.SLOP) {
            swiping = ChatSwipe.horizontal(dx, dy) && allowed(dx)
            rejected = !swiping
        }
        return swiping
    }

    /** True once, when the swipe first reaches [threshold] (the haptic tick). */
    fun crossed(threshold: Float): Boolean {
        val now = swiping && !ticked && dx >= threshold
        if (now) ticked = true
        return now
    }
}

/**
 * Whether the list (newest first, drawn from the bottom) is scrolled up more than [distance] px from the newest
 * message: another row is the first one shown, or the newest one is scrolled off by more than that.
 */
internal fun awayFromBottom(firstIndex: Int, firstOffset: Int, distance: Float): Boolean = firstIndex > 0 || firstOffset > distance

/** What a new newest message does: the first one jumps there, others' while scrolled up count, the rest scroll down. */
internal enum class Newest { Jump, Count, Follow }

internal fun newest(first: Boolean, away: Boolean, fromOthers: Boolean): Newest = when {
    first -> Newest.Jump
    away && fromOthers -> Newest.Count
    else -> Newest.Follow
}

/** The end of a row's swipe: its actions shown, the chat marked read, or back in place. */
internal enum class RowRelease { Open, Read, Close }

/** Everything a bubble shows, made once per change of the timeline (compared by value, so unchanged rows skip). */
@Immutable
data class BubbleModel(
    val item: ChatMessageItem,
    val mine: Boolean,
    val run: ChatLayout.Run,
    /** „14:05“ inside the bubble; null without a send time. */
    val time: String?,
    /** The long-press bar's line with send time and expiry. */
    val info: String,
    val showName: Boolean,
    val senderName: String,
    val avatar: ChatAvatar?,
    val profile: ProfileLink?,
    val quote: ReplyQuote?,
    val chips: List<ReactionChip>,
    val own: String,
    val canAct: Boolean,
    val pinLabel: String?,
    val poll: PollView?
)

/** One row of the message list as it is drawn. */
@Immutable
sealed interface RowModel {
    val key: String

    /** Rows of one kind share their composition in the list. */
    val contentType: String

    /** Whether the row shows files (only those rows get the files' views). */
    val hasFiles: Boolean get() = false

    data class Day(val label: String, override val key: String) : RowModel {
        override val contentType: String get() = "day"
    }

    data object Unread : RowModel {
        override val key: String get() = "unread"
        override val contentType: String get() = "unread"
    }

    data class Bubble(val model: BubbleModel) : RowModel {
        override val key: String get() = model.item.id
        override val contentType: String get() = if (model.mine) "mine" else "theirs"
        override val hasFiles: Boolean get() = model.item.content.attachments.isNotEmpty()
    }

    /** A quiet line where something happened ([QuietNotice]). */
    data class Notice(val text: String, override val key: String) : RowModel {
        override val contentType: String get() = "notice"
    }
}

/** The rows of [state] with everything each bubble shows, for the member [myId] at [now]. */
internal fun rowModels(
    state: ConversationState,
    myId: String,
    senderName: (String) -> String,
    senderAvatar: (String) -> ChatAvatar,
    senderProfile: (String) -> ProfileLink?,
    now: Instant
): List<RowModel> {
    val rows = state.shownRows
    val models = rows.map { row ->
        if (row is TimelineRow.Day) {
            RowModel.Day(row.label, row.key)
        } else if (row is TimelineRow.Unread) {
            RowModel.Unread
        } else {
            bubbleModel(row as TimelineRow.Message, state, myId, senderName, senderAvatar, senderProfile, now)
        }
    }.toMutableList()
    // Placed among the rows as they are, inserted from the back so the places of the others stay right; notices at the
    // same place keep their order.
    state.quietNotices
        .mapIndexed { index, notice -> Triple(noticePlace(rows, notice.at), index, notice) }
        .sortedWith(compareByDescending<Triple<Int, Int, QuietNotice>> { it.first }.thenByDescending { it.second })
        .forEach { (place, index, notice) -> models.add(place, RowModel.Notice(notice.text, "notice-$index")) }
    return models
}

/**
 * Where a quiet notice of [at] goes among [rows]: before the first message sent after it (and before that message's day
 * pill or the „Neue Nachrichten“ divider). Unknown, before the loaded messages or after all of them: at the end.
 */
internal fun noticePlace(rows: List<TimelineRow>, at: Instant?): Int {
    val messages = rows.withIndex().filter { it.value is TimelineRow.Message }
    val next = messages.firstOrNull { (it.value as TimelineRow.Message).sent?.isAfter(at ?: Instant.MAX) == true }
    if (next == null || next == messages.first()) return rows.size
    var place = next.index
    while (rows[place - 1] !is TimelineRow.Message) place -= 1
    return place
}

private fun bubbleModel(
    row: TimelineRow.Message,
    state: ConversationState,
    myId: String,
    senderName: (String) -> String,
    senderAvatar: (String) -> ChatAvatar,
    senderProfile: (String) -> ProfileLink?,
    now: Instant
): RowModel.Bubble {
    val item = row.item
    val mine = item.senderId == myId
    return RowModel.Bubble(
        BubbleModel(
            item = item,
            mine = mine,
            run = row.run,
            time = row.sent?.let(ChatLayout::clockTime),
            info = ChatLayout.infoLine(row.sent, item.expiresAt, now),
            // Others' names in groups, on the first bubble of each of their runs.
            showName = !mine && state.conversation?.isGroup == true &&
                (row.run == ChatLayout.Run.First || row.run == ChatLayout.Run.Single),
            senderName = senderName(item.senderId),
            avatar = senderAvatar(item.senderId).takeUnless { mine },
            profile = senderProfile(item.senderId).takeUnless { mine },
            quote = state.quotes[item.signature],
            chips = state.reactions[item.signature].orEmpty(),
            own = state.ownReaction(item.signature),
            canAct = state.canAct(item),
            pinLabel = state.pinLabel(item.signature),
            poll = state.polls[item.signature]
        )
    )
}

/**
 * Quiet lines about members with a new device or newly set-up chats (when, if known; during this session: [now]) and
 * members who cannot receive this chat securely (at the end).
 */
internal fun keyNotices(conversation: ChatConversation, directory: ChatKeyring.Directory, myId: String, now: Instant): List<QuietNotice> {
    val names = directory.users.associate { it.id to it.name }
    fun newDevice(id: String) = "${names.getValue(id)} nutzt jetzt ein neues Gerät oder hat die Chats neu eingerichtet."
    val changed = conversation.members.filter { it.userId != myId }.mapNotNull { member ->
        directory.user(member.userId)?.keyChangedAt?.let { QuietNotice(newDevice(member.userId), OasisJson.parseInstant(it)) }
    }
    return changed + directory.changedInSession.map { QuietNotice(newDevice(it), now) } +
        directory.invalid.map { QuietNotice("${names.getValue(it)} kann diesen Chat gerade nicht sicher empfangen.", null) }
}

/**
 * The swipe hint of one chat on this device (docs/oasis-chat-client.md, „C8 Swipe hint once“): shown in the first three
 * chats with a message of someone else, gone for good after „Verstanden“ or the first swipe to answer.
 */
internal class SwipeHintTracker(private val preferences: ChatPreferences, private val conversationId: String) {
    /** Done on this device (read when the chat opens), so a swipe does not write it again. */
    private var done = true

    /** Whether the hint shows in this chat; the chat counts as one it was shown in. */
    suspend fun offer(): Boolean {
        val progress = preferences.swipeHint()
        done = progress.done
        if (!progress.showsIn(conversationId)) return false
        preferences.setSwipeHint(progress.copy(shown = progress.shown + conversationId))
        return true
    }

    /** „Verstanden“ or the first swipe to answer. */
    suspend fun finish() {
        if (done) return
        done = true
        preferences.setSwipeHint(preferences.swipeHint().copy(done = true))
    }
}

/** Why nobody can write in [current] (yet), as a banner on top: ended, waiting for the new key, no access yet; or null. */
internal fun waitingNotice(current: ChatConversation, vault: ChatVault, myId: String): String? = when {
    current.ended -> "Dieser Chat ist beendet. Du kannst ihn noch lesen und Nachrichten melden, aber nicht mehr schreiben."

    // Shown instead of the composer (ConversationState.untrustedOwner).
    !ChatKeyring.ownerTrusted(vault, current) -> null

    current.rotationRequired && current.createdBy != myId ->
        "Der Chat wird gerade neu gesichert. Sobald die Person, die ihn gestartet hat, online ist, kannst du schreiben."

    vault.conversationKey(current.id, current.keyVersion) == null ->
        "Du kannst hier noch nicht schreiben. Sobald die Person, die den Chat gestartet hat, online ist, geht es los."

    else -> null
}
