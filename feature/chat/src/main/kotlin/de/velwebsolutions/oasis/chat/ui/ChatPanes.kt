package de.velwebsolutions.oasis.chat.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType

/** From this width the list and the conversation stand side by side (docs/oasis-chat-client.md, „Spacing“). */
internal val WIDE_CHAT = 840.dp

/** The list pane's width beside the conversation. */
internal val LIST_PANE = 360.dp

/**
 * The chat list and the open conversation: one at a time on phones; from [WIDE_CHAT] the list (360) beside the
 * conversation, the panes meeting at a full-height 1 px paper line with no extra gap. Without an open chat the right
 * pane says what to do.
 */
@Composable
internal fun ChatPanes(selected: Int?, list: @Composable () -> Unit, conversation: @Composable (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (!isWide(maxWidth)) {
            if (selected == null) list() else conversation(selected)
        } else {
            Row(Modifier.fillMaxSize().testTag("chat.panes")) {
                Box(Modifier.width(LIST_PANE).fillMaxHeight()) { list() }
                VerticalDivider(color = OasisColors.PaperLine, thickness = 1.dp)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (selected != null) {
                        conversation(selected)
                    } else {
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
                }
            }
        }
    }
}

/** Whether a chat area of [width] shows the list beside the conversation. */
internal fun isWide(width: Dp): Boolean = width >= WIDE_CHAT
