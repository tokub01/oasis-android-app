package de.velwebsolutions.oasis.network.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.velwebsolutions.oasis.design.components.AuthorizedImage
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.network.api.Post

/** Shows the images of a Tropfen full screen, starting with [start]: swipe between them, pinch or double-tap to zoom. */
@Composable
fun ImageViewer(images: List<Post.Image>, start: Post.Image, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(initialPage = images.indexOfFirst { it.id == start.id }.coerceAtLeast(0)) { images.size }
        Box(Modifier.fillMaxSize().background(OasisColors.Night)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { images[it].id }) { page ->
                ZoomableImage(images[page])
            }
            // The page dots of iOS' paged TabView, only with more than one image.
            if (images.size > 1) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                        .testTag("imageViewer.pages")
                        .clearAndSetSemantics { contentDescription = "Bild ${pager.currentPage + 1} von ${images.size}" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    images.indices.forEach { index ->
                        val color = if (index == pager.currentPage) OasisColors.OnNight else OasisColors.OnNightMuted
                        Box(Modifier.size(8.dp).background(color, CircleShape))
                    }
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(44.dp)
                    .background(OasisColors.NightLine, CircleShape)
                    .testTag("imageViewer.close")
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Schließen", tint = OasisColors.OnNight)
            }
        }
    }
}

/** The zoom of one image: a double tap toggles it, two fingers pinch, and one finger moves a zoomed image. */
private class Zoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun toggle() {
        if (scale > 1f) {
            scale = 1f
            offset = Offset.Zero
        } else {
            scale = 2.5f
        }
    }

    /** Pinching zooms, and a zoomed image moves with one finger; otherwise the pager gets the swipe. */
    suspend fun AwaitPointerEventScope.follow() {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.size > 1 || scale > 1f) {
                scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** One image that zooms with two fingers or a double tap and can be moved while zoomed in. */
@Composable
private fun ZoomableImage(image: Post.Image) {
    val zoom = remember { Zoom() }
    Box(
        Modifier
            .fillMaxSize()
            .testTag("imageViewer.image")
            .pointerInput(image.id) { detectTapGestures(onDoubleTap = { zoom.toggle() }) }
            .pointerInput(image.id) { awaitEachGesture { with(zoom) { follow() } } },
        contentAlignment = Alignment.Center
    ) {
        AuthorizedImage(
            image.url,
            contentDescription = image.alt,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                }
        )
    }
}
