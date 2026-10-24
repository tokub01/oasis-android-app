package de.velwebsolutions.oasis.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/** Eyebrow, title and lead of a page (`x-network.page-header`). */
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, lead: String? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (eyebrow != null) Eyebrow(eyebrow)
        Text(title, style = OasisType.display(32), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
        if (lead != null) Text(lead, style = OasisType.Lead, color = OasisColors.Ink2)
    }
}

/** The colour of a [Panel]: paper, dune, night, or dune with a sun border for warnings. */
enum class PanelTone { Paper, Dune, Night, Warning }

/** The surface of cards and sections (`rounded-[24px] p-6`). */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    tone: PanelTone = PanelTone.Paper,
    spacing: Int = 12,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(OasisRadius.M.dp)
    val background = when (tone) {
        PanelTone.Paper -> OasisColors.Paper
        PanelTone.Dune, PanelTone.Warning -> OasisColors.Dune
        PanelTone.Night -> OasisColors.Night
    }
    CompositionLocalProvider(LocalContentColor provides if (tone == PanelTone.Night) OasisColors.OnNight else OasisColors.Ink) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(background, shape)
                .then(if (tone == PanelTone.Warning) Modifier.border(2.dp, OasisColors.Sun, shape) else Modifier)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
            content = content
        )
    }
}

/**
 * A side card of the web's columns (`x-network.side-card`), stacked below the main content on phones. Its text is 15
 * in the muted colour of the tone, like the web's side-card paragraphs; rows set their own fonts.
 */
@Composable
fun SideCard(title: String, modifier: Modifier = Modifier, tone: PanelTone = PanelTone.Paper, content: @Composable ColumnScope.() -> Unit) {
    Panel(modifier, tone) {
        Text(
            title,
            style = OasisType.CardTitle,
            color = if (tone == PanelTone.Night) OasisColors.OnNight else OasisColors.Night,
            modifier = Modifier.semantics { heading() }
        )
        CompositionLocalProvider(
            LocalTextStyle provides OasisType.Small,
            LocalContentColor provides if (tone == PanelTone.Night) OasisColors.OnNight2 else OasisColors.Ink2
        ) {
            content()
        }
    }
}

/** A titled section of a settings or detail page: heading, optional explanation and count, then the content. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    count: Int? = null,
    tone: PanelTone = PanelTone.Paper,
    content: @Composable ColumnScope.() -> Unit
) {
    Panel(modifier, tone, spacing = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = OasisType.heading(),
                color = if (tone == PanelTone.Night) OasisColors.OnNight else OasisColors.Night,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            if (count != null) {
                Spacer(Modifier.width(8.dp))
                Pill("$count", tone = PillTone.Sand)
            }
        }
        if (detail != null) {
            Text(detail, style = OasisType.Small, color = if (tone == PanelTone.Night) OasisColors.OnNight2 else OasisColors.Ink2)
        }
        content()
    }
}

/** A dashed box for empty lists and the end of a feed; [highlighted] gives the spring border of a feed's end. */
@Composable
fun DashedNote(modifier: Modifier = Modifier, highlighted: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val color = if (highlighted) OasisColors.Spring else OasisColors.DuneLine
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(OasisRadius.S.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())))
                )
            }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

/** A [DashedNote] with one line of text. */
@Composable
fun DashedNote(text: String, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    DashedNote(modifier, highlighted) {
        Text(text, style = OasisType.Body, color = OasisColors.Ink2, textAlign = TextAlign.Center)
    }
}

/** A bulleted list in body text. */
@Composable
fun Bullets(items: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = OasisType.Body, color = OasisColors.Ink2)
                Text(item, style = OasisType.Body, color = OasisColors.Ink2)
            }
        }
    }
}
