package de.velwebsolutions.oasis.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/** A paper card with the web's border and radius. */
@Composable
fun OasisCard(modifier: Modifier = Modifier, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(OasisRadius.S.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, shape)
            .border(1.dp, OasisColors.PaperLine, shape)
            .padding(padding.dp),
        content = content,
    )
}

/** Small uppercase label above a heading (`o-eyebrow`, on night `o-eyebrow-night`). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, onNight: Boolean = false) {
    Text(
        text.uppercase(),
        style = OasisType.Eyebrow.copy(letterSpacing = 0.18.em),
        color = if (onNight) OasisColors.SunOnNight else OasisColors.SunText,
        modifier = modifier,
    )
}

/** A count badge (unread chats, hints); nothing for zero. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(OasisColors.Sun, CircleShape)
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = "$count neu" },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else "$count", style = OasisType.Caption.copy(fontWeight = FontWeight.Bold), color = OasisColors.SunInk)
    }
}

/** The sand page background used by every screen. */
fun Modifier.oasisScreenBackground(): Modifier = background(OasisColors.Sand)
