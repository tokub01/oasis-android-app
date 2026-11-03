package de.velwebsolutions.oasis.network.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.Post

/** „1 Stimme“, „3 Stimmen“. */
fun votes(count: Int): String = if (count == 1) "1 Stimme" else "$count Stimmen"

/**
 * The line under a poll: „3 Personen haben abgestimmt · Mehrere Antworten möglich · Endet Mi., 4. Nov., 18:00 Uhr · Wer
 * abgestimmt hat, sieht niemand.“ (`x-network.post-card.poll`).
 */
fun pollFooter(poll: Post.Poll): String {
    val who = if (poll.voters == 1) "1 Person hat abgestimmt" else "${poll.voters} Personen haben abgestimmt"
    val end = poll.endsAt
    val state = when {
        poll.closed -> "Beendet"
        end != null -> "Endet " + GermanTime.format(end, "EE', 'd. MMM, HH:mm") + " Uhr"
        else -> "Ohne Ende"
    }
    return listOfNotNull(who, "Mehrere Antworten möglich".takeIf { poll.multiple }, state, "Wer abgestimmt hat, sieht niemand.")
        .joinToString(" · ")
}

/**
 * The poll of a Tropfen: the question, one row per answer with its count (who voted is never shown), the own vote
 * marked. Members of the oasis vote while it runs (where the screen hands [LocalPollActions] down); its author ends it.
 */
@Composable
internal fun PostPollView(post: Post, poll: Post.Poll) {
    val actions = LocalPollActions.current
    var confirmClose by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(OasisRadius.S.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, OasisColors.DuneLine, shape)
            .padding(16.dp)
            .semantics { contentDescription = "Umfrage" }
            .testTag("post.poll.${post.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(poll.question, style = OasisType.BodyBold, color = OasisColors.Night)
        val voting = actions != null && poll.canVote && !poll.closed
        poll.options.forEachIndexed { index, option ->
            val mine = index in poll.myOptions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val answer = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 44.dp)
                    .background(if (voting) OasisColors.Paper else OasisColors.Sand, RoundedCornerShape(OasisRadius.XS.dp))
                    .border(
                        if (mine) 2.dp else 1.dp,
                        if (mine) OasisColors.Night else OasisColors.DuneLine,
                        RoundedCornerShape(OasisRadius.XS.dp)
                    )
                Text(
                    option,
                    style = OasisType.Body.copy(fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal),
                    color = OasisColors.Ink,
                    modifier = (if (voting && actions != null) answer.pressable({ actions.vote(post, index) }) else answer)
                        .semantics { selected = mine }
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                        .testTag("post.poll.answer.$index")
                )
                Text(
                    votes(poll.counts.getOrElse(index) { 0 }),
                    style = OasisType.SmallBold,
                    color = OasisColors.Ink2,
                    textAlign = TextAlign.End,
                    modifier = Modifier.defaultMinSize(minWidth = 64.dp)
                )
            }
        }
        Text(pollFooter(poll), style = OasisType.Small, color = OasisColors.Muted)
        if (actions != null && poll.canClose && !poll.closed) {
            Text(
                "Umfrage beenden",
                style = OasisType.BodyBold,
                color = OasisColors.Night,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp).pressable({ confirmClose = true }).padding(vertical = 11.dp)
                    .testTag("post.poll.close")
            )
        }
    }
    if (confirmClose && actions != null) {
        ConfirmDialog(
            "Umfrage beenden?",
            "Beenden",
            onConfirm = { actions.close(post) },
            onDismiss = { confirmClose = false },
            message = "Danach kann niemand mehr abstimmen."
        )
    }
}
