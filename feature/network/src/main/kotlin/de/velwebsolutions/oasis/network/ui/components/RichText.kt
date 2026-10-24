package de.velwebsolutions.oasis.network.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import de.velwebsolutions.oasis.design.navigation.DeepLink
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.TextSegment
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Builds the text of a Tropfen from the server's segments (`RichText`): @mentions and #topics link to the app's profile
 * and topic screens (as web URLs, which [openNetworkLink] maps to routes), external links show their host and open in
 * the browser.
 */
object RichTextBuilder {
    private val LinkStyle = TextLinkStyles(SpanStyle(color = OasisColors.Night, textDecoration = TextDecoration.Underline))

    fun annotated(segments: List<TextSegment>, baseUrl: HttpUrl?, onLink: ((String) -> Unit)? = null): AnnotatedString =
        buildAnnotatedString {
            segments.forEach { segment ->
                val url = link(segment, baseUrl)
                if (url == null) {
                    append(segment.text)
                } else {
                    withLink(LinkAnnotation.Url(url, LinkStyle, onLink?.let { open -> { open(url) } })) { append(segment.text) }
                }
            }
        }

    fun link(segment: TextSegment, baseUrl: HttpUrl?): String? = when (segment.type) {
        TextSegment.Kind.Text -> null

        TextSegment.Kind.Link -> segment.url?.takeIf { it.toHttpUrlOrNull() != null }

        TextSegment.Kind.Mention -> segment.username?.let { name ->
            baseUrl?.newBuilder()?.addPathSegment("profil")?.addPathSegment(name)?.build()?.toString()
        }

        TextSegment.Kind.Tag -> segment.tag?.let { tag ->
            baseUrl?.newBuilder()?.addPathSegment("themen")?.addPathSegment(tag)?.build()?.toString()
        }
    }
}

/** Opens a link to an Oasis web page (profile, topic, Tropfen …) as an app screen; everything else in the browser. */
fun openNetworkLink(url: String, navigator: Navigator, baseUrl: HttpUrl?) {
    val host = url.toHttpUrlOrNull()?.host
    val route = if (baseUrl != null && host == baseUrl.host) DeepLink.route(url) else null
    if (route == null) {
        navigator.open(WebRoute(url))
        return
    }
    if (navigator.sheet != null) navigator.dismissSheet()
    navigator.open(route)
}

/** Rich text of a Tropfen; links to Oasis pages open inside the app. The text can be selected. */
@Composable
fun RichTextView(
    segments: List<TextSegment>,
    modifier: Modifier = Modifier,
    style: TextStyle = OasisType.Body,
    color: Color = OasisColors.Ink,
    maxLines: Int = Int.MAX_VALUE
) {
    val navigator = LocalNavigator.current
    val baseUrl = rememberBaseUrl()
    val text = RichTextBuilder.annotated(segments, baseUrl) { openNetworkLink(it, navigator, baseUrl) }
    if (maxLines == Int.MAX_VALUE) {
        SelectionContainer(modifier.fillMaxWidth()) { Text(text, style = style, color = color) }
    } else {
        Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier.fillMaxWidth())
    }
}
