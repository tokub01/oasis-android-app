package de.velwebsolutions.oasis.network.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.components.ConsentCheck
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.ui.components.rememberBaseUrl

/**
 * "Kurzer Hinweis: „…“ wirkt schnell herablassend" under a text field, linking the tone rules like the web
 * (`partials/profile-tone`, the composer).
 */
@Composable
fun ToneHintNote(hint: String, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val rules = rememberBaseUrl()?.resolve("/community-regeln#umgangston")?.toString()
    val text = buildAnnotatedString {
        append("Kurzer Hinweis: „$hint“ wirkt schnell herablassend. Bitte achte auf einen freundlichen Ton – siehe ")
        if (rules != null) {
            val style = TextLinkStyles(SpanStyle(color = OasisColors.Night, textDecoration = TextDecoration.Underline))
            withLink(LinkAnnotation.Url(rules, style) { navigator.open(WebRoute(rules)) }) { append("Regeln zum Umgangston") }
        } else {
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("Regeln zum Umgangston") }
        }
        append(".")
    }
    Text(
        text,
        style = OasisType.Small,
        color = OasisColors.Ink,
        modifier = modifier
            .fillMaxWidth()
            .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.CHIP.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("tone.hint")
    )
}

/** "Nochmal lesen?" after the server's tone check, with the switch to send or save anyway. */
@Composable
fun ToneQuestion(text: String, confirmation: String, isConfirmed: Boolean, onConfirmedChange: (Boolean) -> Unit, tag: String) {
    Column(
        Modifier.fillMaxWidth().background(OasisColors.Dune, RoundedCornerShape(OasisRadius.TILE.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text, style = OasisType.Small, color = OasisColors.Ink)
        ConsentCheck(isConfirmed, onConfirmedChange, Modifier.testTag(tag)) {
            Text(confirmation, style = OasisType.Small, color = OasisColors.Ink)
        }
    }
}
