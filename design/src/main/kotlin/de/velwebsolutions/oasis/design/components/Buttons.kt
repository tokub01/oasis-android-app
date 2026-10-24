package de.velwebsolutions.oasis.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/**
 * Button looks of the web app: [Primary] (Nachtwasser), [Accent] (Abendsonne), [Secondary] (outlined),
 * [Destructive] (coral text) – pill-shaped, 48 dp high.
 */
enum class OasisButtonKind { Primary, Accent, Secondary, Destructive }

private fun OasisButtonKind.foreground() = when (this) {
    OasisButtonKind.Primary -> OasisColors.OnNight
    OasisButtonKind.Accent -> OasisColors.SunInk
    OasisButtonKind.Secondary -> OasisColors.Night
    OasisButtonKind.Destructive -> OasisColors.SunText
}

private fun OasisButtonKind.background() = when (this) {
    OasisButtonKind.Primary -> OasisColors.Night
    OasisButtonKind.Accent -> OasisColors.Sun
    OasisButtonKind.Secondary, OasisButtonKind.Destructive -> OasisColors.Paper
}

private fun OasisButtonKind.border(): BorderStroke? = when (this) {
    OasisButtonKind.Secondary -> BorderStroke(1.5.dp, OasisColors.Night)
    OasisButtonKind.Destructive -> BorderStroke(1.5.dp, OasisColors.SunText)
    else -> null
}

/** The Oasis pill button. While [loading] it shows a spinner and ignores taps (AsyncButton on iOS). */
@Composable
fun OasisButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: OasisButtonKind = OasisButtonKind.Primary,
    fullWidth: Boolean = true,
    enabled: Boolean = true,
    loading: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val border = kind.border()
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = 48.dp)
            .pressEffect(interaction)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(kind.background(), CircleShape)
            .then(if (border != null) Modifier.border(border, CircleShape) else Modifier)
            .clickable(interaction, indication = null, enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.alpha(if (loading) 0f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            }
            Text(text, style = OasisType.Button, color = kind.foreground(), textAlign = TextAlign.Center)
        }
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = kind.foreground(), strokeWidth = 2.dp)
    }
}

/** A button that runs a suspending action and shows a spinner meanwhile (disabled while running). */
@Composable
fun AsyncOasisButton(
    text: String,
    onClick: suspend () -> Unit,
    modifier: Modifier = Modifier,
    kind: OasisButtonKind = OasisButtonKind.Primary,
    fullWidth: Boolean = true,
    enabled: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    OasisButton(
        text = text,
        onClick = {
            if (!running) {
                running = true
                scope.launch {
                    try {
                        onClick()
                    } finally {
                        running = false
                    }
                }
            }
        },
        modifier = modifier,
        kind = kind,
        fullWidth = fullWidth,
        enabled = enabled,
        loading = running,
    )
}

/** A plain text button in Nachtwasser (toolbar actions like "Schließen", "Fertig"). */
@Composable
fun OasisTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = OasisColors.Night) {
    Text(
        text = text,
        style = OasisType.BodyBold,
        color = color,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * Shrinks slightly while pressed and springs back; transform only, so it stays cheap while scrolling (the iOS press
 * effect of cards and buttons).
 */
fun Modifier.pressEffect(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = if (pressed) 0.85f else 1f
    }
}

/** A tappable card or tile that gives way under the finger. */
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    pressEffect(interaction).clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}
