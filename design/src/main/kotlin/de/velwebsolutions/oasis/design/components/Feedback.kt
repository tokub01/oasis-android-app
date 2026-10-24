package de.velwebsolutions.oasis.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.delay

/** The colour of a [Pill]. */
enum class PillTone {
    /** Something new or waiting (`bg-sun text-sun-ink`). */
    Sun,

    /** A good state (`bg-spring`). */
    Spring,
    Night,
    Dune,

    /** Counts on paper (`bg-sand`). */
    Sand,
    Outline,
}

/** A small rounded label: "Neu", a status, a count or a role. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, tone: PillTone = PillTone.Dune, icon: ImageVector? = null) {
    val foreground = when (tone) {
        PillTone.Sun, PillTone.Spring -> OasisColors.SunInk
        PillTone.Night -> OasisColors.OnNight
        else -> OasisColors.Night
    }
    val background = when (tone) {
        PillTone.Sun -> OasisColors.Sun
        PillTone.Spring -> OasisColors.Spring
        PillTone.Night -> OasisColors.Night
        PillTone.Dune -> OasisColors.Dune
        PillTone.Sand -> OasisColors.Sand
        PillTone.Outline -> Color.Transparent
    }
    Row(
        modifier = modifier
            .background(background, CircleShape)
            .then(if (tone == PillTone.Outline) Modifier.border(1.dp, OasisColors.DuneLine, CircleShape) else Modifier)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
        Text(text, style = OasisType.Caption.copy(fontWeight = FontWeight.ExtraBold), color = foreground)
    }
}

/**
 * The confirmation of the last action and the last error as a toast above the bottom of the screen (the web's
 * `x-oasis.status`): always in view whatever was tapped, read out by TalkBack, with a haptic, gone after a few seconds
 * or on tap. Showing a text shows it; the toast calls the dismiss callback to set it back to null.
 */
@Composable
fun BoxScope.StatusToast(
    message: String?,
    error: String?,
    onDismissMessage: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(message) {
        if (message != null) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            delay(5_000)
            onDismissMessage()
        }
    }
    LaunchedEffect(error) {
        if (error != null) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            delay(8_000)
            onDismissError()
        }
    }
    Column(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .widthIn(max = 720.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(error != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Toast(error.orEmpty(), isError = true, onTap = onDismissError, modifier = Modifier.testTag("status.error"))
        }
        AnimatedVisibility(message != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Toast(message.orEmpty(), isError = false, onTap = onDismissMessage, modifier = Modifier.testTag("status.message"))
        }
    }
}

@Composable
private fun Toast(text: String, isError: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(OasisRadius.TILE.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp, shape, ambientColor = OasisColors.Night.copy(alpha = 0.15f), spotColor = OasisColors.Night.copy(alpha = 0.15f))
            .background(if (isError) OasisColors.Paper else OasisColors.Night, shape)
            .then(if (isError) Modifier.border(1.dp, OasisColors.SunText, shape) else Modifier)
            .clickable(onClick = onTap)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                onClick(label = "Schließen") {
                    onTap()
                    true
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (isError) OasisColors.SunText else OasisColors.OnNight
        Icon(if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, contentDescription = null, tint = color)
        Text(text, style = OasisType.Small, color = color)
    }
}

/** A Box with the status toast on top of [content]. */
@Composable
fun WithStatusToast(
    message: String?,
    error: String?,
    onDismissMessage: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        content()
        StatusToast(message, error, onDismissMessage, onDismissError)
    }
}
