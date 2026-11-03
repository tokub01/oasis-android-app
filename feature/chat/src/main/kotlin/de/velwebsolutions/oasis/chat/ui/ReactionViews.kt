package de.velwebsolutions.oasis.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/**
 * The steps of reactions and replies (docs/oasis-chat-client.md, "Reactions and replies"): react to a message (the own
 * emoji again removes it), answer it, cancel the answer, and find the original of a quote (its position among the
 * bubbles, or null while it is not loaded).
 */
class ReactionActions(
    val onReact: suspend (ChatMessageItem, String) -> Unit,
    val onReply: (ChatMessageItem) -> Unit,
    val onCancelReply: () -> Unit,
    val onQuote: (String) -> Int?,
    /** „Anheften“ / „Lösen“. */
    val onPin: suspend (ChatMessageItem) -> Unit,
    /** A vote for the answer with the index in the poll. */
    val onVote: suspend (ChatMessageItem, Int) -> Unit,
    /** „Umfrage beenden“. */
    val onClosePoll: suspend (ChatMessageItem) -> Unit,
    /** Swiping a message right: answering it, which also ends the swipe hint. */
    val onSwipeReply: suspend (ChatMessageItem) -> Unit = { onReply(it) }
) {
    companion object {
        /** Screens without reactions (previews). */
        val None = ReactionActions({ _, _ -> }, {}, {}, { null }, {}, { _, _ -> }, {})
    }
}

/**
 * What a bubble shows around its message besides the files: the reply's [quote], the reaction [chips] with the [own]
 * emoji, and – when one [canAct] – „Reagieren“ and „Antworten“.
 */
class BubbleExtras(
    val quote: ReplyQuote?,
    val chips: List<ReactionChip>,
    val own: String,
    val canAct: Boolean,
    val onReact: (String) -> Unit,
    val onReply: () -> Unit,
    val onQuote: (String) -> Unit,
    /** „Anheften“ or „Lösen“ in the reaction bar for those who may pin, else null. */
    val pinLabel: String?,
    val onPin: () -> Unit,
    /** The poll this bubble shows instead of its text. */
    val poll: PollView?,
    val pollActions: PollActions
)

/** The quote of a reply above the text: who and what, tappable to the original; past its expiry only a note. */
@Composable
internal fun ReplyQuoteView(quote: ReplyQuote, mine: Boolean, onQuote: () -> Unit) {
    val color = if (mine) OasisColors.OnNight else OasisColors.Ink
    if (quote.gone) {
        Text(
            QuoteTexts.GONE,
            style = OasisType.Small.copy(fontStyle = FontStyle.Italic),
            color = color,
            modifier = Modifier.testTag("chat.message.quote.gone")
        )
        return
    }
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .pressable(onQuote)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Zur Nachricht von ${quote.name} springen"
            }
            .testTag("chat.message.quote")
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (mine) OasisColors.Sun else OasisColors.Spring))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(quote.name, style = OasisType.Small.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = color)
            Text(quote.text, style = OasisType.Small.copy(fontSize = 13.sp), color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The chips under a bubble, „{emoji} {count}“; tapping one shows who reacted under them (tapping it again hides it). */
@Composable
internal fun ReactionChips(chips: List<ReactionChip>, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { chip ->
                Text(
                    "${chip.emoji} ${chip.count}",
                    style = OasisType.Small.copy(fontSize = 13.sp),
                    color = OasisColors.Ink,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 26.dp)
                        .shadow(3.dp, CircleShape)
                        .background(OasisColors.Paper, CircleShape)
                        .border(1.dp, if (chip.mine) OasisColors.Night else OasisColors.PaperLine, CircleShape)
                        .pressable({ shown = if (shown == chip.names) null else chip.names })
                        .semantics {
                            role = Role.Button
                            contentDescription = chip.description
                            selected = chip.mine
                        }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .testTag("chat.message.reaction.${chip.emoji}")
                )
            }
        }
        shown?.let { names ->
            Text(
                names,
                style = OasisType.Small,
                color = OasisColors.Ink2,
                modifier = Modifier.lookPill(LocalChatLook.current).testTag("chat.message.reactors")
            )
        }
    }
}

/**
 * The long-press bar: the six emoji (the own one pressed; tapping it again removes it), „Antworten“, „Anheften“ /
 * „Lösen“, „Melden“ for others' messages, and the [info] line with send time and expiry.
 */
@Composable
internal fun ReactionBar(expanded: Boolean, extras: BubbleExtras, info: String, onReport: (() -> Unit)?, onDismiss: () -> Unit) {
    val own = extras.own
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = OasisColors.Paper) {
        if (info.isNotEmpty()) {
            Text(
                info,
                style = OasisType.Caption,
                color = OasisColors.Muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("chat.reactionBar.info")
            )
        }
        if (extras.canAct) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "Reaktion wählen" }
                    .testTag("chat.reactionBar"),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MessagePayload.REACTIONS.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(if (emoji == own) OasisColors.Dune else Color.Transparent, CircleShape)
                            .border(1.dp, if (emoji == own) OasisColors.Night else Color.Transparent, CircleShape)
                            .pressable({
                                onDismiss()
                                extras.onReact(emoji)
                            })
                            .semantics {
                                role = Role.Button
                                contentDescription = "Mit $emoji reagieren"
                                selected = emoji == own
                            }
                            .testTag("chat.reactionBar.$emoji"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, style = OasisType.heading(20))
                    }
                }
            }
        }
        if (extras.canAct) {
            DropdownMenuItem(
                text = { Text("Antworten", style = OasisType.Body, color = OasisColors.Ink) },
                onClick = {
                    onDismiss()
                    extras.onReply()
                },
                modifier = Modifier.testTag("chat.reactionBar.reply")
            )
        }
        onReport?.let { report ->
            DropdownMenuItem(
                text = { Text("Melden", style = OasisType.Body, color = OasisColors.Ink) },
                onClick = {
                    onDismiss()
                    report()
                },
                modifier = Modifier.testTag("chat.reactionBar.report")
            )
        }
        extras.pinLabel?.takeIf { extras.canAct }?.let { label ->
            DropdownMenuItem(
                text = { Text(label, style = OasisType.Body, color = OasisColors.Ink) },
                onClick = {
                    onDismiss()
                    extras.onPin()
                },
                modifier = Modifier.testTag("chat.reactionBar.pin")
            )
        }
    }
}

/** The quote bar above the composer: „Antwort an …“, the excerpt and „✕“ („Antwort abbrechen“). */
@Composable
internal fun ReplyBarView(bar: ReplyBar, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.CHIP.dp))
            .padding(start = 12.dp)
            .testTag("chat.composer.reply"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(bar.title, style = OasisType.SmallBold, color = OasisColors.Night)
            Text(bar.text, style = OasisType.Small, color = OasisColors.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCancel, modifier = Modifier.testTag("chat.composer.replyCancel")) {
            Icon(Icons.Outlined.Close, contentDescription = "Antwort abbrechen", tint = OasisColors.Night)
        }
    }
}
