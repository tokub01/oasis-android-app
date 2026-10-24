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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.withTimeoutOrNull

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
    Outline
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
        Text(text, style = OasisType.Caption.copy(fontWeight = FontWeight.ExtraBold), color = foreground)
    }
}

/**
 * The confirmation of the last action and the last error as a Material snackbar at the bottom of the screen (the web's
 * `x-oasis.status`, a toast on iOS): always in view whatever was tapped, announced by TalkBack, with a haptic, gone after
 * a few seconds or when closed; an error and a confirmation queue one after the other. Setting a text shows it; the snackbar calls the dismiss callback to set it back to null.
 */
@Composable
fun BoxScope.StatusToast(
    message: String?,
    error: String?,
    onDismissMessage: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val host = remember { SnackbarHostState() }
    val currentDismissMessage by rememberUpdatedState(onDismissMessage)
    val currentDismissError by rememberUpdatedState(onDismissError)
    LaunchedEffect(error) {
        if (error != null) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            withTimeoutOrNull(8_000) { host.showSnackbar(StatusVisuals(error, isError = true)) }
            currentDismissError()
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            withTimeoutOrNull(5_000) { host.showSnackbar(StatusVisuals(message, isError = false)) }
            currentDismissMessage()
        }
    }
    SnackbarHost(
        host,
        modifier = modifier.align(Alignment.BottomCenter).widthIn(max = 720.dp).padding(horizontal = 8.dp, vertical = 8.dp)
    ) { data ->
        val isError = (data.visuals as? StatusVisuals)?.isError == true
        Snackbar(
            modifier = Modifier
                .testTag(if (isError) "status.error" else "status.message")
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(OasisRadius.CHIP.dp),
            containerColor = if (isError) OasisColors.SunText else OasisColors.Night,
            contentColor = if (isError) OasisColors.Paper else OasisColors.OnNight,
            dismissAction = {
                IconButton(onClick = { data.dismiss() }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Schließen")
                }
            }
        ) {
            Text(data.visuals.message, style = OasisType.Small)
        }
    }
}

private class StatusVisuals(override val message: String, val isError: Boolean) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDuration = SnackbarDuration.Indefinite
}
