package de.velwebsolutions.oasis.chat.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.chat.core.ChatBackground
import de.velwebsolutions.oasis.chat.core.LookChoice
import de.velwebsolutions.oasis.chat.core.LookPhoto
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** How the message area looks: a chosen background (texts on it get a paper pill), a dark one (own bubbles outlined). */
data class LookStyle(val chosen: Boolean = false, val dark: Boolean = false) {
    companion object {
        /** The style of what a chat shows. */
        fun of(choice: LookChoice): LookStyle = LookStyle(choice.background != ChatBackground.Standard, choice.background.dark)
    }
}

/** The look of the message area for the bubbles and texts in it. */
val LocalChatLook = staticCompositionLocalOf { LookStyle() }

/** The colours of the backgrounds (docs/oasis-chat-client.md, "Backgrounds and effects"), top → bottom. */
internal object LookColors {
    val Sand = Color(0xFFF4EDE1)
    val Dune = Color(0xFFEADFCC)
    val Paper = Color(0xFFFBF8F2)
    val PaperSpring = Color(0xFFD2E6DF)
    val SandSun = Color(0xFFF0D0BE)
    val Night2 = Color(0xFF12353A)
    val NightLine = Color(0xFF2C4B4F)
    val DuneLine = Color(0xFFD6C8B0)

    /** Sand at 40 % over an own photo. */
    val Scrim = Color(0x66F4EDE1)
}

/** The gradient of a background, or null for the plain ones (Standard, Sandkörner, the photo). */
internal fun gradientOf(background: ChatBackground): List<Color>? = when (background) {
    ChatBackground.Duene -> listOf(LookColors.Sand, LookColors.Dune)
    ChatBackground.Oase -> listOf(LookColors.Paper, LookColors.PaperSpring)
    ChatBackground.Abendsonne -> listOf(LookColors.Sand, LookColors.SandSun)
    ChatBackground.Nachtwasser -> listOf(LookColors.Night2, LookColors.NightLine)
    else -> null
}

/** Paints a background behind the message area; „Eigenes Foto“ needs its decoded [photo]. */
fun Modifier.chatBackground(background: ChatBackground, photo: ImageBitmap?): Modifier = drawBehind {
    val gradient = gradientOf(background)
    when {
        gradient != null -> drawRect(Brush.verticalGradient(gradient))

        background == ChatBackground.Sandkoerner -> drawDots()

        background == ChatBackground.Photo && photo != null -> drawCover(photo)

        // Standard is the page itself, light or dark as „Darstellung“ shows it.
        else -> drawRect(OasisColors.Sand)
    }
}

/** Sandkörner: sand with dots of dune-line, 3 px across, on a 22 px grid. */
private fun DrawScope.drawDots() {
    drawRect(LookColors.Sand)
    val step = 22.dp.toPx()
    val radius = 1.5.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(LookColors.DuneLine, radius, Offset(x, y))
            x += step
        }
        y += step
    }
}

/** The own photo scaled to cover and centred, under the sand scrim. */
private fun DrawScope.drawCover(photo: ImageBitmap) {
    val scale = max(size.width / photo.width, size.height / photo.height)
    val width = (photo.width * scale).roundToInt()
    val height = (photo.height * scale).roundToInt()
    drawImage(
        photo,
        dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
        dstSize = IntSize(width, height)
    )
    drawRect(LookColors.Scrim)
}

/** The decoded own photo (null without one, or when it cannot be read). */
@Composable
internal fun rememberLookPhoto(photo: LookPhoto?): ImageBitmap? = remember(photo) {
    photo?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size)?.asImageBitmap() }
}

/** An opaque paper pill for a text drawn directly on a chosen background (the meta line, notes, „Ältere …“). */
fun Modifier.lookPill(style: LookStyle): Modifier = if (style.chosen) {
    background(OasisColors.Paper, RoundedCornerShape(OasisRadius.CHIP.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
} else {
    this
}

// region Effects

/** How long an effect stays on screen (ui/effects.js `EFFECT_MS`). */
const val EFFECT_MS = 2_600

/** Whether the device wants movement: not when the system's animations are off (animator duration scale 0). */
fun motionAllowed(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

private val SUN = Color(0xFFE06A43)
private val SPRING = Color(0xFF2E9E95)
private val DUNE = Color(0xFFEADFCC)
private val NIGHT = Color(0xFF0E2B2F)
private val SUN_ON_NIGHT = Color(0xFFF2906B)

/** The shapes of the effects. */
enum class Shape { Confetti, Heart, Balloon, Spark }

/**
 * One particle: its shape and colour, where it starts (fractions of the screen), when it starts (fraction of the run) and
 * how it moves – turning, swaying, or (sparks) spreading by [dx]/[dy] dp.
 */
data class Particle(
    val shape: Shape,
    val color: Color,
    val x: Float,
    val y: Float = 0f,
    val delay: Float,
    val turn: Float = 0f,
    val sway: Float = 0f,
    val dx: Float = 0f,
    val dy: Float = 0f
)

/** The particles of an effect, as on the web (ui/effects.js `particles`). */
fun particles(key: String, random: Random = Random.Default): List<Particle> {
    fun pick(colors: List<Color>) = colors[random.nextInt(colors.size)]
    return when (key) {
        "konfetti" -> List(60) {
            Particle(
                Shape.Confetti,
                pick(listOf(SUN, SPRING, DUNE, NIGHT)),
                random.nextFloat(),
                delay = random.nextFloat() * 0.23f,
                turn =
                    360f + random.nextFloat() * 720f
            )
        }

        "herzen" -> List(24) {
            Particle(
                Shape.Heart,
                pick(listOf(SUN, SUN_ON_NIGHT)),
                random.nextFloat(),
                delay = random.nextFloat() * 0.27f,
                sway =
                    random.nextFloat() * 60f - 30f
            )
        }

        "ballons" -> List(14) {
            Particle(
                Shape.Balloon,
                pick(listOf(SUN, SPRING, SUN_ON_NIGHT)),
                random.nextFloat() * 0.92f,
                delay = random.nextFloat() * 0.19f,
                sway =
                    random.nextFloat() * 40f - 20f
            )
        }

        // Feuerwerk: three bursts of dots spreading out from points in the upper half.
        else -> (0 until 3).flatMap { burst ->
            val x = 0.2f + burst * 0.3f + random.nextFloat() * 0.1f
            val y = 0.18f + random.nextFloat() * 0.24f
            List(18) { index ->
                val angle = index / 18.0 * PI * 2
                Particle(
                    Shape.Spark,
                    pick(listOf(SUN, SPRING, SUN_ON_NIGHT)),
                    x,
                    y,
                    burst * 0.17f,
                    dx = (cos(angle) * 120).toFloat(),
                    dy = (
                        sin(angle) *
                            120
                        ).toFloat()
                )
            }
        }
    }
}

/**
 * The effect over the whole screen while it plays: drawn on a canvas that takes no input, so every tap reaches the chat
 * below. It runs [EFFECT_MS] once and then calls [onDone]; with the system's animations off nothing is drawn.
 */
@Composable
fun EffectOverlay(play: EffectPlay, onDone: () -> Unit, random: Random = Random.Default, progress: Float? = null) {
    val context = LocalContext.current
    if (!motionAllowed(context)) {
        LaunchedEffect(play) { onDone() }
        return
    }
    val parts = remember(play) { particles(play.key, random) }
    val time = remember(play) { Animatable(progress ?: 0f) }
    LaunchedEffect(play) {
        if (progress == null) time.animateTo(1f, tween(EFFECT_MS, easing = LinearEasing))
        onDone()
    }
    Canvas(Modifier.fillMaxSize().testTag("chat.effect").clearAndSetSemantics {}) {
        parts.forEach { drawParticle(it, time.value) }
    }
}

/** One particle at [time] (0 … 1 of the run). */
internal fun DrawScope.drawParticle(particle: Particle, time: Float) {
    val local = ((time - particle.delay) / (1f - particle.delay)).coerceIn(0f, 1f)
    val fade = if (local > 0.85f) (1f - local) / 0.15f else 1f
    val color = particle.color.copy(alpha = fade)
    when (particle.shape) {
        Shape.Confetti -> {
            val center = Offset(particle.x * size.width, (-0.05f + local * 1.1f) * size.height)
            rotate(particle.turn * local, center) {
                drawRect(color, center - Offset(4.dp.toPx(), 6.dp.toPx()), Size(8.dp.toPx(), 12.dp.toPx()))
            }
        }

        Shape.Heart -> drawHeart(color, rising(particle, local))

        Shape.Balloon -> {
            val center = rising(particle, local)
            drawLine(color, center + Offset(0f, 18.dp.toPx()), center + Offset(0f, 48.dp.toPx()), 1.dp.toPx())
            drawOval(color, center - Offset(14.dp.toPx(), 18.dp.toPx()), Size(28.dp.toPx(), 36.dp.toPx()))
        }

        Shape.Spark -> drawCircle(
            particle.color.copy(alpha = 1f - local),
            3.dp.toPx(),
            Offset(particle.x * size.width + particle.dx.dp.toPx() * local, particle.y * size.height + particle.dy.dp.toPx() * local)
        )
    }
}

/** Rising from the bottom while swaying. */
private fun DrawScope.rising(particle: Particle, local: Float): Offset = Offset(
    particle.x * size.width + particle.sway.dp.toPx() * sin(local * 2 * PI).toFloat(),
    (1.05f - local * 1.1f) * size.height
)

private fun DrawScope.drawHeart(color: Color, center: Offset) {
    val s = 9.dp.toPx()
    val heart = Path().apply {
        moveTo(center.x, center.y + s)
        cubicTo(center.x - 2 * s, center.y - 0.2f * s, center.x - s, center.y - 1.6f * s, center.x, center.y - 0.6f * s)
        cubicTo(center.x + s, center.y - 1.6f * s, center.x + 2 * s, center.y - 0.2f * s, center.x, center.y + s)
        close()
    }
    drawPath(heart, color)
}

// endregion

/** A small sample of a background for its choice in the details (the photo shows as sand). */
@Composable
internal fun BackgroundSwatch(background: ChatBackground, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier.chatBackground(background, null))
}
