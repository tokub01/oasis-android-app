package de.velwebsolutions.oasis.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType

/** A scrolling page with the iOS padding (20) and spacing between its sections. */
@Composable
internal fun ChatPage(modifier: Modifier = Modifier, spacing: Int = 20, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(20.dp)),
        verticalArrangement = Arrangement.spacedBy(spacing.dp),
        content = content
    )
}

/** A heading that opens and closes the content below it (SwiftUI's DisclosureGroup). */
@Composable
internal fun Disclosure(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .pressable(onToggle)
                .semantics { stateDescription = if (expanded) "Aufgeklappt" else "Zugeklappt" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = OasisType.BodyBold, color = OasisColors.Night, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = OasisColors.Night
            )
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** A confirmation before a destructive step (SwiftUI's confirmationDialog). */
@Composable
internal fun ConfirmDialog(title: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(title, style = OasisType.Body, color = OasisColors.Ink) },
        confirmButton = {
            OasisTextButton(
                confirm,
                {
                    onDismiss()
                    onConfirm()
                },
                color = OasisColors.SunText
            )
        },
        dismissButton = { OasisTextButton("Abbrechen", onDismiss) },
        containerColor = OasisColors.Paper
    )
}

/** A small text button with a 44 dp touch target ("Entfernen", "Passphrase verwenden"). */
@Composable
internal fun SmallAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OasisColors.Night,
    underline: Boolean = false
) {
    Text(
        text,
        style = if (underline) OasisType.SmallBold.copy(textDecoration = TextDecoration.Underline) else OasisType.SmallBold,
        color = color,
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .pressable(onClick)
            .padding(vertical = 12.dp)
    )
}
