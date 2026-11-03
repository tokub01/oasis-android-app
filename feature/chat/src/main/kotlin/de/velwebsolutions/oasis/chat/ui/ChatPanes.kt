package de.velwebsolutions.oasis.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType

/** From this width the list and the conversation stand side by side (docs/oasis-chat-client.md, „Spacing“). */
internal val WIDE_CHAT = 840.dp

/** The list pane's width beside the conversation. */
internal val LIST_PANE = 360.dp

/** The list pane's narrowest width (the table's 320–360), taken while the details leave the conversation little room. */
internal val NARROW_LIST_PANE = 320.dp

/** The details pane's width beside the conversation. */
internal val DETAILS_PANE = 320.dp

/** From this width every pane keeps the wider inset (24 instead of 16). */
internal val WIDE_INSET = 1024.dp

/** The inset of each side-by-side pane; null while one screen fills the chat area (its own gutter applies). */
internal val LocalPaneInset = staticCompositionLocalOf<Dp?> { null }

/**
 * The chat list, the open conversation and its details: one at a time on phones; from [WIDE_CHAT] the list ([listPane]) beside
 * the conversation and, while [details] is open, the details (320) beside that. The panes meet at a full-height 1 px
 * paper line with no extra gap, and each keeps its own inset ([paneInset]). Without an open chat the right pane says
 * what to do. [onCloseDetails] closes the details pane (its close button and system back); on phones the details are a
 * screen of their own and back works as everywhere.
 */
@Composable
internal fun ChatPanes(
    selected: Int?,
    list: @Composable () -> Unit,
    details: Int? = null,
    onCloseDetails: () -> Unit = {},
    detailsPane: @Composable (id: Int, onClose: (() -> Unit)?) -> Unit = { _, _ -> },
    conversation: @Composable (Int) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (!isWide(maxWidth)) {
            when {
                details != null -> detailsPane(details, null)
                selected == null -> list()
                else -> conversation(selected)
            }
        } else {
            val width = maxWidth
            CompositionLocalProvider(LocalPaneInset provides paneInset(width)) {
                Row(Modifier.fillMaxSize().testTag("chat.panes")) {
                    Box(Modifier.width(listPane(width, details != null)).fillMaxHeight().testTag("chat.panes.list")) { list() }
                    PaneDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (selected != null) conversation(selected) else EmptyPane()
                    }
                    if (details != null) {
                        PaneDivider()
                        Box(Modifier.width(DETAILS_PANE).fillMaxHeight().testTag("chat.panes.details")) {
                            BackHandler(onBack = onCloseDetails)
                            detailsPane(details, onCloseDetails)
                        }
                    }
                }
            }
        }
    }
}

/** Where two panes meet: a full-height 1 px paper line, no gap. */
@Composable
private fun PaneDivider() = VerticalDivider(color = OasisColors.PaperLine, thickness = 1.dp)

/** The conversation pane while no chat is open. */
@Composable
private fun EmptyPane() {
    Box(Modifier.fillMaxSize().background(OasisColors.Sand).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Wähle links einen Chat aus.",
            style = OasisType.Body,
            color = OasisColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("chat.panes.empty")
        )
    }
}

/** Whether a chat area of [width] shows the list beside the conversation. */
internal fun isWide(width: Dp): Boolean = width >= WIDE_CHAT

/** The list pane's width: 360, and 320 while the details stand beside the conversation in an area narrower than [WIDE_INSET]. */
internal fun listPane(width: Dp, withDetails: Boolean): Dp = if (withDetails && width < WIDE_INSET) NARROW_LIST_PANE else LIST_PANE

/** The inset of every pane in a chat area of [width]: 16, and 24 from [WIDE_INSET]. */
internal fun paneInset(width: Dp): Dp = if (width >= WIDE_INSET) 24.dp else 16.dp

/**
 * Closes the details pane of [conversationId]: back to the conversation beside the list. Opened from the conversation,
 * that is one step back; opened by a link (nothing below it), the conversation takes its place.
 */
internal fun Navigator.closeDetails(conversationId: Int) {
    back()
    val conversation = ChatRoute.Conversation(conversationId)
    if (stack(selectedTab).lastOrNull() != conversation) open(conversation)
}

/** Opens the details of [conversationId] unless they are [isOpen] already (beside the conversation). */
internal fun Navigator.openDetails(conversationId: Int, isOpen: Boolean) {
    if (!isOpen) open(ChatRoute.Details(conversationId))
}

/** „Details“ in the conversation's header: opens the details, or closes them while they are [isOpen] beside it. */
internal fun Navigator.toggleDetails(conversationId: Int, isOpen: Boolean) {
    if (isOpen) closeDetails(conversationId) else openDetails(conversationId, false)
}
