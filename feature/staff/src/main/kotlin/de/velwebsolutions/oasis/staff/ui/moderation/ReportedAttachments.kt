package de.velwebsolutions.oasis.staff.ui.moderation

import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.AuthorizedImage
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.media.VoicePlayer
import de.velwebsolutions.oasis.design.media.playFile
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.ModerationCaseDetail
import java.io.File

/** Below every file of a reported chat message, as on the web (partials/case-report). */
internal const val REPORTED_ATTACHMENT_NOTE =
    "Mit dem Schlüssel aus der Meldung geöffnet: Der Anhang ist echt und gehört zu dieser Nachricht."

/**
 * The files of a reported chat message (web: "Anhang 1", …): photos inline, voice messages with play/pause and videos
 * in the platform player, all loaded with the member's token. Voice messages and videos are loaded into a file first
 * ([onLoad]), because the platform players play files only.
 */
@Composable
internal fun ReportedAttachments(
    attachments: List<ModerationCaseDetail.ReportedAttachment>,
    files: Map<Int, File>,
    failed: Set<Int>,
    onLoad: suspend (Int) -> Unit
) {
    attachments.forEachIndexed { index, attachment ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("case.attachment.$index")) {
            Text("Anhang ${index + 1}", style = OasisType.SmallBold, color = OasisColors.Ink)
            when (attachment.kind) {
                "image" -> AuthorizedImage(
                    attachment.url,
                    contentDescription = "Gemeldetes Bild",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(OasisRadius.CHIP.dp))
                        .testTag("case.attachment.image"),
                    contentScale = ContentScale.Fit
                )

                else -> PlayableAttachment(attachment.kind == "video", files[index], index in failed) { onLoad(index) }
            }
            Text(REPORTED_ATTACHMENT_NOTE, style = OasisType.Small, color = OasisColors.Muted)
        }
    }
}

/** A voice message or video: "Laden" until the file is there, then the player; a failed load can be tried again. */
@Composable
private fun PlayableAttachment(isVideo: Boolean, file: File?, failed: Boolean, onLoad: suspend () -> Unit) {
    when {
        file == null -> AsyncOasisButton(
            when {
                failed -> "Erneut laden"
                isVideo -> "Video laden"
                else -> "Sprachnachricht laden"
            },
            onLoad,
            Modifier.testTag("case.attachment.load"),
            kind = OasisButtonKind.Secondary,
            fullWidth = false
        )

        isVideo -> ReportedVideo(file)

        else -> ReportedVoiceMessage(file)
    }
}

@Composable
private fun ReportedVoiceMessage(file: File) {
    val player = remember(file) { VoicePlayer(file) }
    DisposableEffect(player) { onDispose { player.release() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = player::toggle, modifier = Modifier.testTag("case.attachment.play")) {
            Icon(
                if (player.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (player.playing) "Sprachnachricht pausieren" else "Sprachnachricht abspielen",
                tint = OasisColors.Night
            )
        }
        Text("Sprachnachricht", style = OasisType.Small, color = OasisColors.Ink)
    }
    if (player.failed) Text("Die Sprachnachricht lässt sich nicht abspielen.", style = OasisType.Small, color = OasisColors.SunText)
}

@Composable
private fun ReportedVideo(file: File) {
    var unplayable by remember(file) { mutableStateOf(false) }
    AndroidView(
        factory = { context -> VideoView(context).apply { playFile(file) { unplayable = true } } },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(OasisRadius.CHIP.dp))
            .testTag("case.attachment.player")
    )
    if (unplayable) Text("Das Video lässt sich nicht abspielen.", style = OasisType.Small, color = OasisColors.SunText)
}
