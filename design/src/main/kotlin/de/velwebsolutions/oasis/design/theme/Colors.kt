package de.velwebsolutions.oasis.design.theme

import androidx.compose.ui.graphics.Color

/** The Oasis palette (docs/oasis-design/assets/tokens.css in the Laravel project). Names follow the CSS tokens. */
object OasisColors {
    /** Nachtwasser – headers, primary buttons, dark sections. */
    val Night = Color(0xFF0E2B2F)
    val NightLine = Color(0xFF2C4B4F)

    /** Page background. */
    val Sand = Color(0xFFF4EDE1)

    /** Cards and light sections. */
    val Paper = Color(0xFFFBF8F2)

    /** Input fields. */
    val Field = Color(0xFFFFFDF8)

    /** Chips, alternative sections, selected list rows. */
    val Dune = Color(0xFFEADFCC)
    val DuneLine = Color(0xFFD6C8B0)
    val PaperLine = Color(0xFFE5DBC9)
    val FieldLine = Color(0xFF9C8E77)

    /** Body text. */
    val Ink = Color(0xFF1C2422)
    val Ink2 = Color(0xFF3E4A47)

    /** Meta text. */
    val Muted = Color(0xFF5E6B67)

    /** Abendsonne – accent buttons, logo. */
    val Sun = Color(0xFFE06A43)
    val SunInk = Color(0xFF0B2326)

    /** Coral as text on light backgrounds (errors, warnings, links needing attention). */
    val SunText = Color(0xFFA3441F)
    val SunOnNight = Color(0xFFF2906B)

    /** Quelle – progress, focus, success. */
    val Spring = Color(0xFF2E9E95)
    val SpringText = Color(0xFF1F6F69)
    val OnNight = Color(0xFFF4EDE1)
    val OnNight2 = Color(0xFFC9D6D2)
    val OnNightMuted = Color(0xFFA9BDB8)
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
