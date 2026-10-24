package de.velwebsolutions.oasis.design.components

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.net.toUri
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/** The Oasis logo like `x-oasis.logo`: a half sun over two waves, with the word mark "oasis" next to it. */
@Composable
fun OasisLogo(modifier: Modifier = Modifier, size: Dp = 26.dp, wordmark: Boolean = true) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Oasis" },
        horizontalArrangement = Arrangement.spacedBy(size * 0.25f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogoSign(Modifier.size(size))
        if (wordmark) {
            val fontSize = with(LocalDensity.current) { (size * 0.75f).toSp() }
            Text("oasis", style = OasisType.display().copy(fontSize = fontSize, letterSpacing = (-0.02).em), color = OasisColors.Night)
        }
    }
}

/** The sign of the logo, drawn in the web SVG's 120 × 120 grid. */
@Composable
private fun LogoSign(modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.width / 120f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        val sun = Path().apply {
            arcTo(Rect(center = point(60f, 62f), radius = 34f * unit), 180f, 180f, forceMoveTo = true)
            close()
        }
        drawPath(sun, OasisColors.Sun)
        val stroke = Stroke(width = 7f * unit, cap = StrokeCap.Round)
        drawPath(wave(point(14f, 76f), 4, 23f * unit, 9f * unit), OasisColors.Spring, style = stroke)
        drawPath(wave(point(32f, 92f), 3, 19f * unit, 8f * unit), OasisColors.Spring.copy(alpha = 0.6f), style = stroke)
    }
}

/** `q (w/2) -depth w 0` repeated, like the SVG's `q … t …` wave. */
private fun wave(start: Offset, crests: Int, width: Float, depth: Float) = Path().apply {
    moveTo(start.x, start.y)
    for (index in 0 until crests) {
        val x = start.x + index * width
        val up = index % 2 == 0
        quadraticTo(x + width / 2, start.y + if (up) -depth else depth, x + width, start.y)
    }
}

/** „Du bist nicht allein – hier bekommst du Hilfe“ (x-oasis.support-contacts). */
@Composable
fun SupportContacts(modifier: Modifier = Modifier) {
    val links = TextLinkStyles(SpanStyle(color = OasisColors.SpringText, fontWeight = FontWeight.Bold))
    fun line(bold: String, before: String, vararg parts: Pair<String, String>, after: String = "") = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        append(before)
        parts.forEachIndexed { index, (text, url) ->
            withLink(LinkAnnotation.Url(url, links)) { append(text) }
            // Only "Nummer gegen Kummer" has two numbers: for children and for parents.
            if (index < parts.lastIndex) append(" (kostenlos, anonym), für Eltern: ")
        }
        append(after)
    }
    Column(
        modifier.fillMaxWidth().background(OasisColors.Dune, RoundedCornerShape(OasisRadius.XS.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Du bist nicht allein – hier bekommst du Hilfe", style = OasisType.BodyBold, color = OasisColors.Night)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val style = OasisType.Small.copy(color = OasisColors.Ink2)
            Text(
                line(
                    "Nummer gegen Kummer",
                    " für Kinder und Jugendliche: ",
                    "116 111" to "tel:116111",
                    "0800 111 0 550" to "tel:08001110550"
                ),
                style = style
            )
            Text(line("JUUUPORT", " – junge Menschen helfen bei Cybermobbing: ", "juuuport.de" to "https://www.juuuport.de"), style = style)
            Text(line("HateAid", " – Beratung bei Hass und digitaler Gewalt: ", "hateaid.org" to "https://hateaid.org"), style = style)
            Text(line("TelefonSeelsorge", ", rund um die Uhr: ", "0800 111 0 111" to "tel:08001110111"), style = style)
            Text(line("", "Bei akuter Gefahr: ", "110" to "tel:110"), style = style)
        }
    }
}

/** Opens a public website page (legal pages, help) in a Custom Tab in the Oasis colours; other schemes go to the system. */
fun openWebPage(context: Context, url: String) {
    val uri = url.toUri()
    if (uri.scheme != "http" && uri.scheme != "https") {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return
    }
    val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(OasisColors.Sand.toArgb()).build()
    val intent = CustomTabsIntent.Builder().setDefaultColorSchemeParams(colors).setShowTitle(true).build()
    intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { intent.launchUrl(context, uri) }
}
