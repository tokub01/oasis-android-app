package de.velwebsolutions.oasis.design.components

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

/**
 * The Mildcrowd logo like `x-oasis.logo`: the sign – a lowercase "m" made of two people, white on a rounded tile in the
 * accent colour – with the word mark "mildcrowd" next to it. [size] is the sign, the word mark follows at 65 % of it.
 */
@Composable
fun OasisLogo(modifier: Modifier = Modifier, size: Dp = 38.dp, wordmark: Boolean = true) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Mildcrowd" },
        horizontalArrangement = Arrangement.spacedBy(size * 0.25f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogoSign(Modifier.size(size))
        if (wordmark) {
            val fontSize = with(LocalDensity.current) { (size * 0.65f).toSp() }
            Text(
                "mildcrowd",
                style = OasisType.display().copy(fontSize = fontSize, lineHeight = fontSize, letterSpacing = (-0.02).em),
                color = OasisColors.Night,
                maxLines = 1
            )
        }
    }
}

/** The accent blue of the tile (`sun` in light mode): the sign keeps its colours in dark mode, like on the web. */
private val SignTile = Color(0xFF5560E0)

/** The sign of the logo, drawn in the web SVG's 64 × 64 grid. */
@Composable
private fun LogoSign(modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.width / 64f
        drawRoundRect(SignTile, cornerRadius = CornerRadius(15f * unit))
        // Two arches that share the middle leg: the bodies of the two people.
        val bodies = Path().apply {
            listOf(17.48f, 32f).forEach { left ->
                moveTo(left * unit, 47.18f * unit)
                lineTo(left * unit, 35.3f * unit)
                arcTo(Rect(center = Offset((left + 7.26f) * unit, 35.3f * unit), radius = 7.26f * unit), 180f, 180f, forceMoveTo = false)
                lineTo((left + 14.52f) * unit, 47.18f * unit)
            }
        }
        drawPath(bodies, Color.White, style = Stroke(width = 5.94f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
        listOf(24.74f, 39.26f).forEach { centre ->
            drawCircle(Color.White, radius = 4.29f * unit, center = Offset(centre * unit, 18.14f * unit))
        }
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

/**
 * Opens a public website page (legal pages, help) in a Custom Tab in the Mildcrowd colours; other schemes go to the system.
 * The tab opens in a browser that shows Custom Tabs: without one named, Android would hand a page of the app's own
 * verified App Links (an invoice, the data export, a reset link while signed in) straight back to the app, which would
 * open the tab again, without end. For the same reason the app's own `mildcrowd://` scheme is never handed to the system:
 * the app is its only handler.
 */
fun openWebPage(context: Context, url: String) {
    val uri = url.toUri()
    if (uri.scheme == "mildcrowd") return
    if (uri.scheme != "http" && uri.scheme != "https") {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return
    }
    val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(OasisColors.Sand.toArgb()).build()
    val intent = CustomTabsIntent.Builder().setDefaultColorSchemeParams(colors).setShowTitle(true).build()
    intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    customTabsBrowser(context)?.let { intent.intent.setPackage(it) }
    runCatching { intent.launchUrl(context, uri) }
}

/**
 * The browser for a Custom Tab: the default browser if it shows them, otherwise the first one that does; null without
 * any (the `<queries>` of this module's manifest make them visible). The flags overload of `queryIntentActivities` needs
 * Android 13; the deprecated one works on every version the app supports.
 */
@Suppress("DEPRECATION")
private fun customTabsBrowser(context: Context): String? {
    val browsers = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, "http://".toUri()), 0)
        .map { it.activityInfo.packageName }
    return CustomTabsClient.getPackageName(context, browsers)
}
