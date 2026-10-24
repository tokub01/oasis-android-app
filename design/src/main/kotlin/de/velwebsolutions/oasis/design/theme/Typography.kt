package de.velwebsolutions.oasis.design.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.velwebsolutions.oasis.design.R

@OptIn(ExperimentalTextApi::class)
private fun variable(resource: Int, weight: Int) =
    Font(resource, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Fraunces for headings (bundled variable font, OFL). */
val Fraunces = FontFamily(
    variable(R.font.fraunces, 400),
    variable(R.font.fraunces, 600),
    variable(R.font.fraunces, 700)
)

/** Manrope for everything else (bundled variable font, OFL). */
val Manrope = FontFamily(
    variable(R.font.manrope, 400),
    variable(R.font.manrope, 500),
    variable(R.font.manrope, 600),
    variable(R.font.manrope, 700),
    variable(R.font.manrope, 800)
)

/**
 * The text styles of the iOS app (OasisDesign Typography): sizes in sp, so they scale with the system font size like
 * Dynamic Type.
 */
object OasisType {
    /** Large display heading (screen titles in content, empty states). */
    fun display(size: Int = 30) = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        lineHeight = (
            size *
                1.15
            ).sp
    )

    /** Section and card headings. */
    fun heading(size: Int = 21) = TextStyle(
        fontFamily = Fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        lineHeight = (
            size *
                1.25
            ).sp
    )

    /** The lead sentence below a page title (`o-lead`, 18 px on phones). */
    val Lead = TextStyle(fontFamily = Manrope, fontSize = 18.sp, lineHeight = 26.sp)

    /** Titles of side and section cards (`text-[19px] font-extrabold`). */
    val CardTitle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, lineHeight = 25.sp)

    /** Body text (17 like the web's `--fs-body`). */
    val Body = TextStyle(fontFamily = Manrope, fontSize = 17.sp, lineHeight = 25.sp)
    val BodyBold = Body.copy(fontWeight = FontWeight.Bold)

    /** Secondary text (15, `--fs-small`). */
    val Small = TextStyle(fontFamily = Manrope, fontSize = 15.sp, lineHeight = 21.sp)
    val SmallBold = Small.copy(fontWeight = FontWeight.Bold)

    /** Meta lines, timestamps, counters. */
    val Caption = TextStyle(fontFamily = Manrope, fontSize = 13.sp, lineHeight = 18.sp)

    /** Small uppercase labels above sections (`--fs-eyebrow`). */
    val Eyebrow = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.18.em)

    /** Buttons. */
    val Button = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp)

    /** Large figures (statistics, prices, countdowns). */
    fun figure(size: Int = 28) = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = size.sp)

    /** The initials of an avatar, a third of its size like the web. */
    fun initials(size: TextUnit) = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = size)

    /** Numbers people compare digit by digit (safety and check numbers). */
    val Digits = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
}
