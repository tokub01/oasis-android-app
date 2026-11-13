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
    val springInk: Color,
    val springText: Color,
    val onNight: Color,
    val onNight2: Color,
    val onNightMuted: Color,
    val danger: Color,
    val onDanger: Color
) {
    companion object {
        val Light = OasisPalette(
            night = Color(0xFF171A2E),
            night2 = Color(0xFF1F2340),
            nightLine = Color(0xFF353A5C),
            sand = Color(0xFFF4F5FA),
            paper = Color(0xFFFFFFFF),
            field = Color(0xFFFFFFFF),
            dune = Color(0xFFE6E8F5),
            duneLine = Color(0xFFC9CDE6),
            paperLine = Color(0xFFDDE0EF),
            fieldLine = Color(0xFF7E84A6),
            ink = Color(0xFF1B1E33),
            ink2 = Color(0xFF3D4160),
            muted = Color(0xFF5A5F7A),
            sun = Color(0xFF5560E0),
            sunInk = Color(0xFFFFFFFF),
            sunText = Color(0xFF4A54D1),
            sunOnNight = Color(0xFFA9B0FF),
            spring = Color(0xFFFFA982),
            springInk = Color(0xFF171A2E),
            springText = Color(0xFF4A54D1),
            onNight = Color(0xFFF4F5FA),
            onNight2 = Color(0xFFC9CCE0),
            onNightMuted = Color(0xFFA0A5C0),
            danger = Color(0xFFB42318),
            onDanger = Color(0xFFFFFFFF)
        )

        val Dark = OasisPalette(
            night = Color(0xFFF4F5FA),
            night2 = Color(0xFFE3E6F4),
            nightLine = Color(0xFFC3C7DE),
            sand = Color(0xFF0F1120),
            paper = Color(0xFF171A2E),
            field = Color(0xFF1C2038),
            dune = Color(0xFF232846),
            duneLine = Color(0xFF3A4066),
            paperLine = Color(0xFF2A2F4E),
            fieldLine = Color(0xFF8187AD),
            ink = Color(0xFFECEEF8),
            ink2 = Color(0xFFC9CCE0),
            muted = Color(0xFFA0A5C0),
            sun = Color(0xFF8E97FF),
            sunInk = Color(0xFF0F1120),
            sunText = Color(0xFFA9B0FF),
            sunOnNight = Color(0xFF4A54D1),
            spring = Color(0xFFFFA982),
            springInk = Color(0xFF171A2E),
            springText = Color(0xFFA9B0FF),
            onNight = Color(0xFF171A2E),
            onNight2 = Color(0xFF353A5C),
            onNightMuted = Color(0xFF555B80),
            danger = Color(0xFFFF9B8F),
            onDanger = Color(0xFF2A0E0A)
        )
    }
}

/**
 * The Mildcrowd palette „Blaue Stunde“ (docs/mildcrowd-design.md in the Laravel project). Names follow the CSS tokens,
 * which are older than the look; each value is the one of the mode [Appearance] shows, so screens and drawings follow
 * a switch at once.
 */
object OasisColors {
    /** Tinte – headings, dark buttons, emphasis surfaces (the light colour in dark mode). */
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

    /** Mild, the accent blue – accent buttons, the sign. */
    val Sun: Color get() = Appearance.palette.sun

    /** Text on sun. */
    val SunInk: Color get() = Appearance.palette.sunInk

    /** The accent as text (links, markers). */
    val SunText: Color get() = Appearance.palette.sunText

    /** Accent text on night. */
    val SunOnNight: Color get() = Appearance.palette.sunOnNight

    /** Pfirsich – the second colour, only as a surface (text [SpringInk]). */
    val Spring: Color get() = Appearance.palette.spring

    /** Text on spring. */
    val SpringInk: Color get() = Appearance.palette.springInk

    /** The second accent as text. */
    val SpringText: Color get() = Appearance.palette.springText

    /** Text on night. */
    val OnNight: Color get() = Appearance.palette.onNight

    /** Secondary text on night. */
    val OnNight2: Color get() = Appearance.palette.onNight2

    /** Muted text on night. */
    val OnNightMuted: Color get() = Appearance.palette.onNightMuted

    /** Errors, warnings and destructive buttons. */
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
