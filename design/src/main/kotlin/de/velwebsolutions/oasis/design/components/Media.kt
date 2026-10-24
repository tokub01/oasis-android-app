package de.velwebsolutions.oasis.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.format.RelativeTime
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import java.time.Instant

/** The API client of the running app, to resolve image URLs the API returns (null in previews). */
val LocalApiClient = staticCompositionLocalOf<ApiClient?> { null }

/** The colours members and oases choose (`AvatarColor` on the web: sun, spring, night, dune). */
object AvatarTint {
    /** The circle behind the initials (`AvatarColor::classes`), where dune is the darker dune line. */
    fun background(name: String): Color = when (name) {
        "sun" -> OasisColors.Sun
        "spring" -> OasisColors.Spring
        "night" -> OasisColors.Night
        else -> OasisColors.DuneLine
    }

    fun foreground(name: String): Color = when (name) {
        "night" -> OasisColors.OnNight
        "dune" -> OasisColors.Night
        else -> OasisColors.SunInk
    }

    /** An oasis colour surface (`bg-{color}`), where dune is the light sand tone. */
    fun surface(name: String): Color = if (name == "dune") OasisColors.Dune else background(name)
}

/**
 * A round avatar like `x-network.avatar`: the photo where the server sends one, otherwise the initials in the colour the
 * member chose. Decorative – the name is always written next to it.
 */
@Composable
fun AvatarView(initials: String, color: String, modifier: Modifier = Modifier, photoUrl: String? = null, size: Dp = 44.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (photoUrl == null) AvatarTint.background(color) else OasisColors.Dune)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        val fontSize = with(LocalDensity.current) { (size / 3).toSp() }
        Text(initials, style = OasisType.initials(fontSize), color = AvatarTint.foreground(color))
        if (photoUrl != null) {
            AuthorizedImage(
                photoUrl,
                contentDescription = null,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * Loads an image with the member's token (Tropfen images, banners, profile photos with restricted audience); accepts
 * absolute or root-relative URLs. The app's image loader shares the API client's OkHttp, so the headers are the same.
 */
@Composable
fun AuthorizedImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val client = LocalApiClient.current
    val absolute = client?.absoluteUrl(url)?.toString() ?: url
    SubcomposeAsyncImage(
        model = absolute,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        error = {
            Box(Modifier.background(OasisColors.Dune), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = OasisColors.Muted)
            }
        }
    )
}

/** "vor 5 Min." / "gestern" / "12. Okt." – relative time in German like the web's timestamps. */
@Composable
fun RelativeTimeText(
    instant: Instant,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = OasisType.Caption,
    color: Color = OasisColors.Muted
) {
    Text(
        RelativeTime.format(instant),
        style = style,
        color = color,
        modifier = modifier.semantics { contentDescription = GermanTime.format(instant, "d. MMMM yyyy, HH:mm") }
    )
}
