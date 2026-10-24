package de.velwebsolutions.oasis.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.SupportContacts
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/**
 * Dialog "Nachricht melden" (partials/report.blade.php; iOS `ReportSheet`): consent-based report of one message, then
 * thanks, the offer to block and where to find help.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportSheet(model: ReportModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OasisColors.Sand
    ) {
        ReportContent(state, model::setCategory, model::setComment, model::send, model::block, onDismiss)
    }
}

@Composable
internal fun ReportContent(
    state: ReportState,
    onCategory: (String) -> Unit,
    onComment: (String) -> Unit,
    onSend: suspend () -> Unit,
    onBlock: suspend () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nachricht melden", style = OasisType.BodyBold, color = OasisColors.Night, modifier = Modifier.weight(1f))
            OasisTextButton(if (state.sent) "Schließen" else "Abbrechen", onClose)
        }
        if (state.sent) {
            Text(
                "Danke, dass du das meldest.",
                style = OasisType.heading(21),
                color = OasisColors.Night,
                modifier = Modifier.testTag("chat.report.thanks")
            )
            Text(
                "Du hast richtig gehandelt. Unser Moderationsteam schaut sich das an. Die Person erfährt nicht, wer gemeldet hat.",
                style = OasisType.Body,
                color = OasisColors.Ink2
            )
            if (state.blocked) {
                Text(
                    "Blockiert. Ihr könnt euch keine Nachrichten mehr schicken.",
                    style = OasisType.BodyBold,
                    color = OasisColors.SpringText
                )
            } else {
                AsyncOasisButton("Person blockieren", onBlock, fullWidth = false)
            }
            state.error?.let { FieldError(it) }
            OasisButton("Schließen", onClose, kind = OasisButtonKind.Secondary, fullWidth = false)
            SupportContacts()
        } else {
            Text(
                "Melden ist ausdrücklich erwünscht – auch wenn du dir nicht sicher bist. Diese eine Nachricht geht an unser Moderationsteam, zusammen mit Absender und Uhrzeit; andere Nachrichten aus dem Chat sieht dort niemand. Wir sagen der Person nicht, wer gemeldet hat.",
                style = OasisType.Body,
                color = OasisColors.Ink2
            )
            state.quote?.let {
                Text(
                    it,
                    style = OasisType.Body,
                    color = OasisColors.Ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.TILE.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            Text("Was ist das Problem?", style = OasisType.SmallBold, color = OasisColors.Ink)
            ReportModel.categories.forEach { category ->
                val selected = state.category == category
                val shape = RoundedCornerShape(OasisRadius.CHIP.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .background(if (selected) OasisColors.Dune else Color.Transparent, shape)
                        .border(1.dp, if (selected) OasisColors.Night else OasisColors.DuneLine, shape)
                        .selectable(selected, role = Role.RadioButton, onClick = { onCategory(category) })
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = OasisColors.Night
                    )
                    Text(category, style = OasisType.Small, color = OasisColors.Ink)
                }
            }
            OasisTextField("Möchtest du noch etwas dazu sagen? (freiwillig)", state.comment, onComment, lines = 3..10)
            state.error?.let { FieldError(it) }
            AsyncOasisButton("Melden", onSend, fullWidth = false, modifier = Modifier.testTag("chat.report.submit"))
        }
    }
}
