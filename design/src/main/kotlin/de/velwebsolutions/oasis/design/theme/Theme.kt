package de.velwebsolutions.oasis.design.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Material 3 with the Oasis tokens: sand pages, paper cards, Nachtwasser for primary actions, Abendsonne as accent. */
private val OasisColorScheme = lightColorScheme(
    primary = OasisColors.Night,
    onPrimary = OasisColors.OnNight,
    primaryContainer = OasisColors.Dune,
    onPrimaryContainer = OasisColors.Night,
    secondary = OasisColors.Spring,
    onSecondary = OasisColors.SunInk,
    secondaryContainer = OasisColors.Dune,
    onSecondaryContainer = OasisColors.Night,
    tertiary = OasisColors.Sun,
    onTertiary = OasisColors.SunInk,
    background = OasisColors.Sand,
    onBackground = OasisColors.Ink,
    surface = OasisColors.Paper,
    onSurface = OasisColors.Ink,
    surfaceVariant = OasisColors.Dune,
    onSurfaceVariant = OasisColors.Ink2,
    surfaceContainer = OasisColors.Paper,
    surfaceContainerLow = OasisColors.Paper,
    surfaceContainerHigh = OasisColors.Paper,
    surfaceContainerHighest = OasisColors.Dune,
    surfaceContainerLowest = OasisColors.Field,
    error = OasisColors.SunText,
    onError = OasisColors.Paper,
    outline = OasisColors.FieldLine,
    outlineVariant = OasisColors.DuneLine,
)

private val OasisTypography = Typography(
    displayLarge = OasisType.display(36),
    displayMedium = OasisType.display(32),
    displaySmall = OasisType.display(30),
    headlineLarge = OasisType.display(28),
    headlineMedium = OasisType.heading(24),
    headlineSmall = OasisType.heading(21),
    titleLarge = OasisType.heading(21),
    titleMedium = OasisType.BodyBold,
    titleSmall = OasisType.SmallBold,
    bodyLarge = OasisType.Body,
    bodyMedium = OasisType.Small,
    bodySmall = OasisType.Caption,
    labelLarge = OasisType.Button,
    labelMedium = OasisType.SmallBold,
    labelSmall = OasisType.Caption,
)

private val OasisShapes = Shapes(
    extraSmall = RoundedCornerShape(OasisRadius.CHIP.dp),
    small = RoundedCornerShape(OasisRadius.XS.dp),
    medium = RoundedCornerShape(OasisRadius.TILE.dp),
    large = RoundedCornerShape(OasisRadius.M.dp),
    extraLarge = RoundedCornerShape(OasisRadius.L.dp),
)

/** The one theme of the app (light only, like iOS and the web). */
@Composable
fun OasisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OasisColorScheme, typography = OasisTypography, shapes = OasisShapes) {
        CompositionLocalProvider(
            LocalTextStyle provides OasisType.Body.copy(color = OasisColors.Ink),
            LocalTextSelectionColors provides TextSelectionColors(OasisColors.Spring, OasisColors.Spring.copy(alpha = 0.3f)),
            content = content,
        )
    }
}

/** The one motion of Oasis: short and settling, for state changes, insertions and removals. */
object OasisMotion {
    fun <T> spec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 900f)
}
