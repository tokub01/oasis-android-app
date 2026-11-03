package de.velwebsolutions.oasis.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The token values of one mode (docs/oasis-website.md, „Darstellung“: the web's table in `resources/css/oasis.css`).
 * The semantics stay the same in both modes; in dark mode `night` is the light colour and `onNight` the dark one.
 */
@Immutable
data class OasisPalette(
    val night: Color,
    val night2: Color,
    val nightLine: Color,
    val sand: Color,
    val paper: Color,
    val field: Color,
    val dune: Color,
    val duneLine: Color,
    val paperLine: Color,
    val fieldLine: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val sun: Color,
    val sunInk: Color,
    val sunText: Color,
    val sunOnNight: Color,
    val spring: Color,
    val springText: Color,
    val onNight: Color,
    val onNight2: Color,
    val onNightMuted: Color,
    val danger: Color,
    val onDanger: Color
) {
    companion object {
        val Light = OasisPalette(
            night = Color(0xFF0E2B2F),
            night2 = Color(0xFF12353A),
            nightLine = Color(0xFF2C4B4F),
            sand = Color(0xFFF4EDE1),
            paper = Color(0xFFFBF8F2),
            field = Color(0xFFFFFDF8),
            dune = Color(0xFFEADFCC),
            duneLine = Color(0xFFD6C8B0),
            paperLine = Color(0xFFE5DBC9),
            fieldLine = Color(0xFF9C8E77),
            ink = Color(0xFF1C2422),
            ink2 = Color(0xFF3E4A47),
            muted = Color(0xFF5E6B67),
            sun = Color(0xFFE06A43),
            sunInk = Color(0xFF0B2326),
            sunText = Color(0xFFA3441F),
            sunOnNight = Color(0xFFF2906B),
            spring = Color(0xFF2E9E95),
            springText = Color(0xFF1F6F69),
            onNight = Color(0xFFF4EDE1),
            onNight2 = Color(0xFFC9D6D2),
            onNightMuted = Color(0xFFA9BDB8),
            danger = Color(0xFFA3441F),
            onDanger = Color(0xFFFFFFFF)
        )

        val Dark = OasisPalette(
            night = Color(0xFFF4EDE1),
            night2 = Color(0xFFE6DDCC),
            nightLine = Color(0xFFC2B8A6),
            sand = Color(0xFF0F1A1B),
            paper = Color(0xFF162426),
            field = Color(0xFF1A2A2C),
            dune = Color(0xFF22322F),
            duneLine = Color(0xFF3D4F4B),
            paperLine = Color(0xFF2C3C3D),
            fieldLine = Color(0xFF7E8F8B),
            ink = Color(0xFFECE6DA),
            ink2 = Color(0xFFC8D1CD),
            muted = Color(0xFF9DABA7),
            sun = Color(0xFFE8784F),
            sunInk = Color(0xFF0B2326),
            sunText = Color(0xFFF2906B),
            sunOnNight = Color(0xFFA3441F),
            spring = Color(0xFF2E9E95),
            springText = Color(0xFF5CC4BA),
            onNight = Color(0xFF0E2B2F),
            onNight2 = Color(0xFF2C4B4F),
            onNightMuted = Color(0xFF46625F),
            danger = Color(0xFFF2906B),
            onDanger = Color(0xFF0B2326)
        )
    }
}

/**
 * The Oasis palette (docs/oasis-design/assets/tokens.css in the Laravel project). Names follow the CSS tokens; each
 * value is the one of the mode [Appearance] shows, so screens and drawings follow a switch at once.
 */
object OasisColors {
    /** Nachtwasser – headings, dark buttons, emphasis surfaces (the light colour in dark mode). */
    val Night: Color get() = Appearance.palette.night

    /** Hover of night. */
    val Night2: Color get() = Appearance.palette.night2

    /** Lines on night. */
    val NightLine: Color get() = Appearance.palette.nightLine

    /** Page background. */
    val Sand: Color get() = Appearance.palette.sand

    /** Cards and light sections. */
    val Paper: Color get() = Appearance.palette.paper

    /** Input fields. */
    val Field: Color get() = Appearance.palette.field

    /** Chips, alternative sections, selected list rows. */
    val Dune: Color get() = Appearance.palette.dune

    /** Lines on dune and cards. */
    val DuneLine: Color get() = Appearance.palette.duneLine

    /** Lines on paper. */
    val PaperLine: Color get() = Appearance.palette.paperLine

    /** Input borders (≥ 3:1 to field). */
    val FieldLine: Color get() = Appearance.palette.fieldLine

    /** Body text. */
    val Ink: Color get() = Appearance.palette.ink

    /** Secondary text. */
    val Ink2: Color get() = Appearance.palette.ink2

    /** Meta text. */
    val Muted: Color get() = Appearance.palette.muted

    /** Abendsonne – accent buttons, logo. */
    val Sun: Color get() = Appearance.palette.sun

    /** Text on sun and spring. */
    val SunInk: Color get() = Appearance.palette.sunInk

    /** Coral as text (errors, warnings, links needing attention). */
    val SunText: Color get() = Appearance.palette.sunText

    /** Accent text on night. */
    val SunOnNight: Color get() = Appearance.palette.sunOnNight

    /** Quelle – progress, focus, success. */
    val Spring: Color get() = Appearance.palette.spring

    /** The second accent as text. */
    val SpringText: Color get() = Appearance.palette.springText

    /** Text on night. */
    val OnNight: Color get() = Appearance.palette.onNight

    /** Secondary text on night. */
    val OnNight2: Color get() = Appearance.palette.onNight2

    /** Muted text on night. */
    val OnNightMuted: Color get() = Appearance.palette.onNightMuted

    /** Destructive buttons. */
    val Danger: Color get() = Appearance.palette.danger

    /** Text on danger. */
    val OnDanger: Color get() = Appearance.palette.onDanger
}

/** Corner radii (tokens `--r-*`), in dp. */
object OasisRadius {
    const val XL = 32
    const val L = 28
    const val M = 24
    const val S = 20

    /** Images, notices and nested boxes (the web's `rounded-2xl`). */
    const val TILE = 16
    const val XS = 14

    /** Small marks and thumbnails (the web's `rounded-xl`). */
    const val CHIP = 12
}
