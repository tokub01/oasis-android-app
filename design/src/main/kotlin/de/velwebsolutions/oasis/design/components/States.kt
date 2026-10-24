package de.velwebsolutions.oasis.design.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/** Placeholder shapes of the coming content instead of a spinner. */
enum class SkeletonLayout { Cards, Rows }

/** Shows a placeholder, the error with retry, or the content of a [Loadable]. */
@Composable
fun <T> LoadableView(
    state: Loadable<T>,
    onRetry: suspend () -> Unit,
    modifier: Modifier = Modifier,
    skeleton: SkeletonLayout? = null,
    content: @Composable (T) -> Unit
) {
    when (state) {
        is Loadable.Loaded -> content(state.value)
        is Loadable.Failed -> ErrorStateView(state.error, onRetry, modifier)
        else -> if (skeleton != null) SkeletonView(modifier, skeleton) else LoadingView(modifier)
    }
}

/** Centered spinner with "Lädt …" or its own label ("Oasis startet …"). */
@Composable
fun LoadingView(modifier: Modifier = Modifier, label: String = "Lädt …") {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = OasisColors.Night)
        Spacer(Modifier.height(12.dp))
        Text(label, style = OasisType.Small, color = OasisColors.Muted)
    }
}

/**
 * Placeholder shapes in the layout of the coming content (heading, then Tropfen cards or list rows), so the screen
 * does not jump when the data arrives. One opacity animation for the whole block keeps it cheap.
 */
@Composable
fun SkeletonView(modifier: Modifier = Modifier, layout: SkeletonLayout = SkeletonLayout.Cards) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(1f, 0.55f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Column(
        modifier
            .fillMaxSize()
            .background(OasisColors.Sand)
            .padding(20.dp)
            .alpha(alpha)
            .clearAndSetSemantics { contentDescription = "Lädt …" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Bar(90.dp, 12.dp)
        Bar(220.dp, 30.dp)
        Bar(300.dp, 14.dp)
        Spacer(Modifier.height(8.dp))
        repeat(if (layout == SkeletonLayout.Cards) 3 else 6) {
            if (layout == SkeletonLayout.Cards) {
                Column(
                    Modifier.fillMaxWidth().background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(OasisColors.Dune, CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Bar(130.dp, 14.dp)
                            Bar(90.dp, 11.dp)
                        }
                    }
                    Bar(null, 14.dp)
                    Bar(null, 14.dp)
                    Bar(180.dp, 14.dp)
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Bar(160.dp, 15.dp)
                    Bar(240.dp, 12.dp)
                }
            }
        }
    }
}

@Composable
private fun Bar(width: Dp?, height: Dp) {
    Box(
        (if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .background(OasisColors.Dune, RoundedCornerShape(height / 2))
    )
}

/** Error with a retry button. */
@Composable
fun ErrorStateView(error: ApiError, onRetry: suspend () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = OasisColors.SunText, modifier = Modifier.size(32.dp))
        Text(error.userMessage, style = OasisType.Body, color = OasisColors.Ink, textAlign = TextAlign.Center)
        AsyncOasisButton("Noch einmal versuchen", onRetry, kind = OasisButtonKind.Secondary, fullWidth = false)
    }
}

/** Friendly empty state ("Noch keine Tropfen …"). */
@Composable
fun EmptyStateView(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = Icons.Outlined.WaterDrop,
    actions: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = OasisColors.Spring, modifier = Modifier.size(34.dp))
        Text(title, style = OasisType.heading(), color = OasisColors.Night, textAlign = TextAlign.Center)
        if (message != null) Text(message, style = OasisType.Small, color = OasisColors.Muted, textAlign = TextAlign.Center)
        Column(Modifier.padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, content = actions)
    }
}

/** The kind of a [Banner]. */
enum class BannerKind { Error, Info, Success }

/** Inline banner for errors or notices inside forms and lists. */
@Composable
fun Banner(text: String, modifier: Modifier = Modifier, kind: BannerKind = BannerKind.Error) {
    val (icon, foreground, background) = when (kind) {
        BannerKind.Error -> Triple(Icons.Outlined.ErrorOutline, OasisColors.SunText, OasisColors.Sun.copy(alpha = 0.12f))
        BannerKind.Info -> Triple(Icons.Outlined.Info, OasisColors.Ink, OasisColors.Dune)
        BannerKind.Success -> Triple(Icons.Outlined.CheckCircle, OasisColors.SpringText, OasisColors.Spring.copy(alpha = 0.14f))
    }
    Row(
        modifier.fillMaxWidth().background(background, RoundedCornerShape(OasisRadius.XS.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
        Text(text, style = OasisType.Small, color = foreground, modifier = Modifier.weight(1f))
    }
}
