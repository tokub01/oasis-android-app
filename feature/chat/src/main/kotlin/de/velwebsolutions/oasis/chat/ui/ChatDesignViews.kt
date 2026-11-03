package de.velwebsolutions.oasis.chat.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The size of the chat's round buttons and touch targets. */
internal val CHAT_TARGET = 44.dp

/**
 * The conversation's one header bar: back („Alle Nachrichten“), avatar 40, name 17/800 and the status line 13, and
 * „Details & Sicherheitsnummern“.
 */
@Composable
internal fun ChatHeaderBar(
    conversation: ChatConversation?,
    myId: String,
    title: String,
    status: String,
    onBack: (() -> Unit)?,
    onDetails: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(OasisColors.Paper).statusBarsPadding().testTag("chat.header")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(CHAT_TARGET).testTag("chat.header.back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Alle Nachrichten", tint = OasisColors.Night)
                }
            }
            conversation?.let {
                // A 1:1 partner's avatar opens their profile.
                val link = it.directProfile(myId)
                ConversationAvatar(
                    it,
                    myId,
                    Modifier.opensProfile(link, profileOpener(link)).testTag("chat.conversation.avatar"),
                    size = 40.dp
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = OasisType.Body.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold),
                    color = OasisColors.Night,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }.testTag("chat.conversation.title")
                )
                if (status.isNotEmpty()) {
                    Text(
                        status,
                        style = OasisType.Small.copy(fontSize = 13.sp),
                        color = OasisColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("chat.conversation.status")
                    )
                }
            }
            IconButton(onClick = onDetails, modifier = Modifier.size(CHAT_TARGET).testTag("chat.details")) {
                Icon(Icons.Outlined.Info, contentDescription = "Details & Sicherheitsnummern", tint = OasisColors.Night)
            }
        }
        HorizontalDivider(color = OasisColors.PaperLine, thickness = 1.dp)
    }
}

/** The day pill between days: „Heute“, „Gestern“, „Montag, 2. November“. */
@Composable
internal fun DayPill(label: String) {
    Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = OasisType.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = OasisColors.Ink2,
            modifier = Modifier
                .background(OasisColors.Dune, CircleShape)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { heading() }
                .testTag("chat.day")
        )
    }
}

/**
 * A quiet notice in the timeline (docs/oasis-chat-client.md, „C9 Quiet notices“): centred, small and muted; on a chosen
 * background in the paper pill. A tap opens the details with the safety numbers.
 */
@Composable
internal fun QuietLine(text: String, onOpen: () -> Unit) {
    val look = LocalChatLook.current
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = OasisType.Caption.copy(fontSize = 13.sp),
            color = OasisColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth(QUIET_WIDTH)
                .clip(RoundedCornerShape(OasisRadius.CHIP.dp))
                .clickable(role = Role.Button, onClickLabel = "Details öffnen", onClick = onOpen)
                .lookPill(look)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("chat.notice")
        )
    }
}

/** How wide a quiet line may get. */
private const val QUIET_WIDTH = 0.9f

/** „Tipp: Nachricht nach rechts wischen zum Antworten“ with „Verstanden“ (C8), above the composer. */
@Composable
internal fun SwipeHintPill(onDismiss: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, CircleShape)
            .border(1.dp, OasisColors.PaperLine, CircleShape)
            .padding(start = 16.dp, end = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("chat.swipeHint"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Tipp: Nachricht nach rechts wischen zum Antworten",
            style = OasisType.Small,
            color = OasisColors.Ink,
            modifier = Modifier.weight(1f)
        )
        OasisTextButton("Verstanden", { scope.launch { onDismiss() } }, Modifier.testTag("chat.swipeHint.dismiss"))
    }
}

/** „Neue Nachrichten“: a sun-text line with the centred label before the first new message of others. */
@Composable
internal fun UnreadDivider() {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).testTag("chat.unread"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(Modifier.weight(1f), color = OasisColors.SunText, thickness = 1.dp)
        // On a chosen background the label keeps its paper pill, like every free-standing text there.
        Text(
            "Neue Nachrichten",
            style = OasisType.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold),
            color = OasisColors.SunText,
            modifier = Modifier.lookPill(LocalChatLook.current)
        )
        HorizontalDivider(Modifier.weight(1f), color = OasisColors.SunText, thickness = 1.dp)
    }
}

/** „Nach unten“ (44, round) with the number of new messages of others that arrived while scrolled up. */
@Composable
internal fun ScrollDownButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = ChatLayout.scrollDownLabel(count)
    Box(modifier) {
        Box(
            Modifier
                .size(CHAT_TARGET)
                .shadow(6.dp, CircleShape)
                .background(OasisColors.Paper, CircleShape)
                .border(1.dp, OasisColors.PaperLine, CircleShape)
                .clip(CircleShape)
                .pressable(onClick)
                .testTag("chat.scrollDown")
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.ArrowDownward, contentDescription = null, tint = OasisColors.Night)
        }
        if (count > 0) {
            Text(
                "$count",
                style = OasisType.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold),
                color = OasisColors.SunInk,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                    .background(OasisColors.Sun, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
                    .testTag("chat.scrollDown.badge")
            )
        }
    }
}

/** The corners of a bubble by its place in a run: 20, 6 on the sender's side inside a run, 4 where the tail sits. */
internal fun bubbleShape(mine: Boolean, run: ChatLayout.Run): RoundedCornerShape {
    val joinedAbove = run == ChatLayout.Run.Middle || run == ChatLayout.Run.Last
    val joinedBelow = run == ChatLayout.Run.First || run == ChatLayout.Run.Middle
    val top = if (joinedAbove) INNER else OUTER
    val bottom = if (joinedBelow) INNER else TAIL_CORNER
    return if (mine) {
        RoundedCornerShape(topStart = OUTER, topEnd = top, bottomEnd = bottom, bottomStart = OUTER)
    } else {
        RoundedCornerShape(topStart = top, topEnd = OUTER, bottomEnd = OUTER, bottomStart = bottom)
    }
}

/** Whether a bubble carries the tail: the last of a run, or a single one. */
internal fun hasTail(run: ChatLayout.Run): Boolean = run == ChatLayout.Run.Last || run == ChatLayout.Run.Single

private val OUTER = 20.dp
private val INNER = 6.dp
private val TAIL_CORNER = 4.dp

/** The 8 × 10 tail at the bottom corner on the sender's side, in the bubble's colour. */
internal fun Modifier.bubbleTail(show: Boolean, mine: Boolean, color: Color): Modifier = if (!show) {
    this
} else {
    drawBehind {
        val width = 8.dp.toPx()
        val height = 10.dp.toPx()
        val path = Path().apply {
            if (mine) {
                moveTo(size.width - 1f, size.height - height)
                lineTo(size.width + width, size.height)
                lineTo(size.width - width, size.height)
            } else {
                moveTo(1f, size.height - height)
                lineTo(-width, size.height)
                lineTo(width, size.height)
            }
            close()
        }
        drawPath(path, color)
    }
}

/**
 * Swipe a message right → „Antworten“ (docs/oasis-chat-client.md, „Gestures“): only a clearly horizontal drag past the
 * slop, the bubble follows 1:1 to 48 and then with resistance to 64, one tick at 48, releasing past 48 replies. Without
 * motion (the system's animations off) nothing moves, the icon still shows the progress.
 */
@Composable
internal fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, nudge: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val view = LocalView.current
    val moves = motionAllowed(LocalContext.current)
    val scope = rememberCoroutineScope()
    // The offset is read only in the layout and drawing of the row (offset { }, graphicsLayer { }): a swipe moves the
    // bubble without composing it again for every pixel, and no move starts a coroutine.
    val drag = remember { mutableFloatStateOf(0f) }
    // The swipe hint: 24 to the right and back once (2 × 200 ms, ease-out); without motion only the pill shows.
    LaunchedEffect(nudge != null) {
        if (nudge == null) return@LaunchedEffect
        if (moves) {
            val spec = tween<Float>(NUDGE_MS, easing = FastOutSlowInEasing)
            animate(0f, NUDGE, animationSpec = spec) { value, _ -> drag.floatValue = value }
            animate(NUDGE, 0f, animationSpec = spec) { value, _ -> drag.floatValue = value }
        }
        nudge()
    }
    val onMove: (SwipeTracker) -> Unit = { tracker ->
        if (tracker.crossed(ChatSwipe.REPLY)) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        drag.floatValue = tracker.dx
    }
    val onEnd: (SwipeTracker) -> Unit = { tracker ->
        if (tracker.dx >= ChatSwipe.REPLY) onReply()
        scope.launch {
            animate(drag.floatValue, 0f, animationSpec = tween(SETTLE_MS, easing = FastOutSlowInEasing)) { value, _ ->
                drag.floatValue =
                    value
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.pointerInput(onReply, moves) { swipes({ it > 0f }, onMove, onEnd) } else Modifier)
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(32.dp)
                .graphicsLayer {
                    val progress = ChatSwipe.progress(drag.floatValue)
                    alpha = progress
                    scaleX = 0.6f + 0.4f * progress
                    scaleY = scaleX
                }
                .background(OasisColors.Dune, CircleShape)
                .testTag("chat.swipe.replyIcon"),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(18.dp))
        }
        Box(
            Modifier.offset {
                IntOffset(if (moves) (ChatSwipe.replyOffset(drag.floatValue) * density).roundToInt() else 0, 0)
            }
        ) { content() }
    }
}

/**
 * Follows the drags on this element: [onMove] for each move of a swipe in an [allowed] direction (consumed), [onEnd]
 * on releasing it. A drag that is no swipe is left alone, so the list keeps scrolling.
 */
internal suspend fun PointerInputScope.swipes(allowed: (Float) -> Boolean, onMove: (SwipeTracker) -> Unit, onEnd: (SwipeTracker) -> Unit) =
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val tracker = SwipeTracker(allowed)
        var change = awaitPointerEvent().changes.first()
        while (change.pressed && !tracker.rejected) {
            val delta = change.positionChange()
            if (tracker.move(delta.x / density, delta.y / density)) {
                change.consume()
                onMove(tracker)
            }
            change = awaitPointerEvent().changes.first()
        }
        if (tracker.swiping) onEnd(tracker)
    }

/** How long a bubble or row takes back to its place. */
internal const val SETTLE_MS = 180

/** How far the swipe hint moves the newest received bubble (dp), and how long each way takes. */
private const val NUDGE = 24f
private const val NUDGE_MS = 200

/** The gap above a row of the message list: 2 inside a run, 12 between runs. */
internal fun rowGap(run: ChatLayout.Run): Dp = if (run == ChatLayout.Run.Middle || run == ChatLayout.Run.Last) 2.dp else 12.dp

/** The offset of a list row in px. */
internal fun rowOffset(value: Float, density: Float): IntOffset = IntOffset((value * density).roundToInt(), 0)
