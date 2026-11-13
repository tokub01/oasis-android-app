package de.velwebsolutions.oasis.chat.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.velwebsolutions.oasis.chat.api.AttachmentState
import de.velwebsolutions.oasis.chat.core.AndroidChatMedia
import de.velwebsolutions.oasis.chat.core.AttachmentKind
import de.velwebsolutions.oasis.chat.core.AttachmentTexts
import de.velwebsolutions.oasis.chat.core.AttachmentView
import de.velwebsolutions.oasis.chat.core.ChatAttachments
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.DraftAttachment
import de.velwebsolutions.oasis.chat.core.fileIntent
import de.velwebsolutions.oasis.chat.core.startOrFalse
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisCheckbox
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.media.ImageCodec
import de.velwebsolutions.oasis.design.media.SystemAudioFocus
import de.velwebsolutions.oasis.design.media.VoicePlayer
import de.velwebsolutions.oasis.design.media.VoiceSpeed
import de.velwebsolutions.oasis.design.media.playFile
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the screen does with attachments: the composer's files and recording, and the files in messages. */
class AttachmentActions(
    /** A picked photo or video, or under "Datei" ([asFile]) any file. */
    val onPicked: suspend (uri: Uri, asFile: Boolean) -> Unit,
    val onRemove: (Long) -> Unit,
    /** „Einmal ansehen“ of the „+“ sheet for every file of the next message. */
    val onViewOnce: (Boolean) -> Unit,
    val recording: RecordingActions,
    val onLoad: suspend (MessageAttachment, Boolean) -> Unit,
    val onAnswer: suspend (ChatMessageItem, MessageAttachment, Boolean) -> Unit,
    val onOpenViewOnce: suspend (ChatMessageItem, MessageAttachment) -> Unit,
    val onCloseViewOnce: suspend () -> Unit,
    val onCancelViewOnce: () -> Unit,
    /**
     * Opens a screen of another app with the start step (a picker, a file in another app) and says whether it opened;
     * the chat stays unlocked while it is in front ([de.velwebsolutions.oasis.chat.core.ChatVault.openExternalScreen]).
     */
    val onExternalScreen: (start: () -> Boolean) -> Boolean,
    val text: TextActions
)

/** A voice message as text (on the device only): „Als Text anzeigen“ and „Text ausblenden“. */
class TextActions(val onTranscribe: suspend (MessageAttachment) -> Unit, val onHide: (String) -> Unit)

/** Whether voice messages can be shown as text here, and the texts so far. */
data class TranscriptionView(val available: Boolean = false, val transcripts: Map<String, Transcript> = emptyMap())

/** The voice message in the composer: start recording, "Fertig" (it becomes a draft) and "Abbrechen". */
class RecordingActions(val onStart: () -> Unit, val onFinish: suspend () -> Unit, val onCancel: () -> Unit)

/** The warning before sending a view-once file (the same words on every platform). */
internal const val VIEW_ONCE_SENDER_NOTE =
    "Wird für alle gelöscht, sobald alle es angesehen haben. Bildschirmfotos und Aufnahmen kann niemand verhindern."

/** The warning before opening a view-once file. */
internal const val VIEW_ONCE_RECIPIENT_NOTE = "Du kannst es nur einmal ansehen, danach ist es weg."

/** "Foto · 1,4 MB" or "Sprachnachricht · 0:42". */
private fun label(kind: AttachmentKind, size: Long, durationMs: Long?, name: String?): String = when {
    kind == AttachmentKind.File -> AttachmentTexts.fileName(name) + " · " + AttachmentTexts.size(size)
    durationMs != null -> AttachmentTexts.title(kind) + " · " + AttachmentTexts.duration(durationMs)
    else -> AttachmentTexts.title(kind) + " · " + AttachmentTexts.size(size)
}

/** The composer's files: preview and remove (view-once ones say so); the upload progress while sending. */
@Composable
internal fun DraftAttachments(state: ConversationState, actions: AttachmentActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.drafts.forEach { draft -> DraftRow(draft, actions, enabled = state.uploadProgress == null) }
        if (state.drafts.any { it.viewOnce }) {
            Text(
                VIEW_ONCE_SENDER_NOTE,
                style = OasisType.Caption,
                color = OasisColors.Danger,
                modifier = Modifier.testTag("chat.composer.viewOnceNote")
            )
        }
        if (state.preparing) Text("Anhang wird vorbereitet …", style = OasisType.Caption, color = OasisColors.Muted)
        state.uploadProgress?.let { progress ->
            val percent = (progress * 100).toInt()
            LinearProgressIndicator(
                progress = { progress },
                color = OasisColors.Night,
                trackColor = OasisColors.Dune,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Anhänge werden hochgeladen, $percent Prozent" }
                    .testTag("chat.composer.progress")
            )
            Text("Wird verschlüsselt und hochgeladen … $percent %", style = OasisType.Caption, color = OasisColors.Muted)
        }
    }
}

@Composable
private fun DraftRow(draft: DraftAttachment, actions: AttachmentActions, enabled: Boolean) {
    if (draft.kind == AttachmentKind.Audio) {
        DraftVoiceMessage(draft, actions, enabled)
        return
    }
    val title = AttachmentTexts.title(draft.kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OasisColors.Field, RoundedCornerShape(OasisRadius.CHIP.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("chat.composer.draft"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val thumbnail = remember(draft.localId) { ImageCodec.thumbnail(draft.data, 96)?.asImageBitmap() }
        if (thumbnail != null) {
            Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp))
        } else {
            Icon(kindIcon(draft.kind), contentDescription = null, tint = OasisColors.Night)
        }
        Text(
            label(draft.kind, draft.data.size.toLong(), draft.durationMs, draft.name),
            style = OasisType.Small,
            color = OasisColors.Ink,
            modifier = Modifier.weight(1f)
        )
        DraftRemove(draft, actions, enabled)
    }
}

/** A recorded voice message in the tray: the same player as in the bubbles (from memory) and remove. */
@Composable
private fun DraftVoiceMessage(draft: DraftAttachment, actions: AttachmentActions, enabled: Boolean) {
    val context = LocalContext.current
    val player = remember(draft.localId) { VoicePlayer(draft.data, draft.durationMs ?: 0, SystemAudioFocus(context)) }
    DisposableEffect(player) { onDispose { player.release() } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OasisColors.Field, RoundedCornerShape(OasisRadius.CHIP.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("chat.composer.draft")
    ) {
        VoiceControls(player, OasisColors.Ink, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            DraftRemove(draft, actions, enabled)
        }
    }
}

@Composable
private fun DraftRemove(draft: DraftAttachment, actions: AttachmentActions, enabled: Boolean) {
    IconButton(onClick = {
        actions.onRemove(draft.localId)
    }, enabled = enabled, modifier = Modifier.testTag("chat.composer.removeDraft")) {
        Icon(Icons.Outlined.Close, contentDescription = "${AttachmentTexts.title(draft.kind)} entfernen", tint = OasisColors.Night)
    }
}

/**
 * While a voice message is recorded: a pulsing red dot, the running time („0:07“), the time left to the limit
 * („noch 2:53“), „Verwerfen“ and „Fertig“ (into the attachment tray). At the limit it stops by itself, as before.
 */
@Composable
internal fun RecordingBar(seconds: Int, actions: AttachmentActions) {
    val pulse = rememberInfiniteTransition(label = "recording")
    val alpha by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    val elapsed = seconds * 1000L
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(12.dp).graphicsLayer { this.alpha = alpha }.background(OasisColors.Sun, CircleShape))
        Text(
            AttachmentTexts.duration(elapsed),
            style = OasisType.BodyBold,
            color = OasisColors.Ink,
            modifier = Modifier.semantics { contentDescription = "Aufnahme läuft, ${AttachmentTexts.duration(elapsed)}" }
                .testTag("chat.composer.recording")
        )
        Text(
            "noch ${AttachmentTexts.duration((ChatAttachments.MAX_VOICE_MS - elapsed).coerceAtLeast(0))}",
            style = OasisType.Small,
            color = OasisColors.Muted,
            modifier = Modifier.weight(1f).testTag("chat.composer.remaining")
        )
        OasisTextButton("Verwerfen", actions.recording.onCancel, modifier = Modifier.testTag("chat.composer.cancelRecording"))
        AsyncOasisButton(
            "Fertig",
            actions.recording.onFinish,
            fullWidth = false,
            modifier = Modifier.testTag("chat.composer.finishRecording")
        )
    }
}

/** The files of one message, each as its state allows: consent first, then shown, view once, declined or deleted. */
@Composable
internal fun MessageAttachments(
    item: ChatMessageItem,
    mine: Boolean,
    senderName: String,
    views: Map<String, AttachmentView>,
    actions: AttachmentActions,
    transcription: TranscriptionView
) {
    val color = if (mine) OasisColors.OnNight else OasisColors.Ink
    item.content.attachments.forEach { attachment ->
        val state = item.state(attachment.id, mine)
        val kind = AttachmentTexts.kind(attachment)
        val label = label(kind, attachment.size, attachment.durationMs, attachment.name)
        val view = views[attachment.id]
        Column(Modifier.testTag("chat.message.attachment"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                // Named in the message, but not by the server's row of it: no actions (ChatMessageItem.listed).
                !item.listed(attachment.id, mine) -> Note(AndroidChatMedia.UNVERIFIABLE, color)

                !state.available -> Note(if (mine && state.viewOnce) "$label · Für alle gelöscht" else "$label · Gelöscht", color)

                // Own view-once files are never shown again; other own files come down like a recipient's.
                mine && state.viewOnce -> Note("$label · Einmal ansehen", color)

                state.status == AttachmentState.DECLINED -> Note("$label · Abgelehnt", color)

                state.status == AttachmentState.VIEWED -> Note("$label · Angesehen", color)

                state.status == AttachmentState.PENDING -> Consent(item, attachment, kind, state.viewOnce, senderName, actions)

                state.viewOnce -> {
                    Note("$label · Einmal ansehen", color)
                    Text(VIEW_ONCE_RECIPIENT_NOTE, style = OasisType.Small, color = OasisColors.Ink2)
                    AsyncOasisButton(
                        "Ansehen",
                        { actions.onOpenViewOnce(item, attachment) },
                        fullWidth = false,
                        modifier = Modifier.testTag("chat.attachment.openViewOnce")
                    )
                }

                else -> {
                    LaunchedEffect(attachment.id) { actions.onLoad(attachment, false) }
                    Shown(attachment, kind, label, view, color, actions, transcription)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(
        text,
        style = OasisType.Small.copy(fontStyle = FontStyle.Italic),
        color = color,
        modifier = Modifier.testTag("chat.attachment.note")
    )
}

/** "Mira möchte dir ein Video schicken (12 MB)" with "Annehmen" and "Ablehnen". */
@Composable
private fun Consent(
    item: ChatMessageItem,
    attachment: MessageAttachment,
    kind: AttachmentKind,
    viewOnce: Boolean,
    senderName: String,
    actions: AttachmentActions
) {
    val what = if (kind == AttachmentKind.File) {
        "eine Datei schicken: ${AttachmentTexts.fileName(attachment.name)} (${AttachmentTexts.size(attachment.size)})."
    } else {
        "${AttachmentTexts.withArticle(kind)} schicken (${AttachmentTexts.size(attachment.size)})."
    }
    Text(
        "$senderName möchte dir $what",
        style = OasisType.Small,
        color = OasisColors.Ink,
        modifier = Modifier.testTag("chat.attachment.consent")
    )
    if (kind == AttachmentKind.File) Text(FILE_TRUST_NOTE, style = OasisType.Small, color = OasisColors.Danger)
    if (viewOnce) Text("Einmal ansehen: $VIEW_ONCE_RECIPIENT_NOTE", style = OasisType.Small, color = OasisColors.Ink2)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AsyncOasisButton("Annehmen", {
            actions.onAnswer(item, attachment, true)
        }, fullWidth = false, modifier = Modifier.testTag("chat.attachment.accept"))
        AsyncOasisButton(
            "Ablehnen",
            { actions.onAnswer(item, attachment, false) },
            kind = OasisButtonKind.Secondary,
            fullWidth = false,
            modifier = Modifier.testTag("chat.attachment.decline")
        )
    }
}

/** An accepted file after decryption: the photo inline, the voice message with its player, the video's poster. */
@Composable
private fun Shown(
    attachment: MessageAttachment,
    kind: AttachmentKind,
    label: String,
    view: AttachmentView?,
    color: Color,
    actions: AttachmentActions,
    transcription: TranscriptionView
) {
    var fullScreen by remember { mutableStateOf(false) }
    when (view) {
        is AttachmentView.Failed -> {
            Note(view.message, color)
            if (view.retry) {
                val scope = rememberCoroutineScope()
                SmallAction("Noch einmal laden", { scope.launch { actions.onLoad(attachment, true) } }, color = color, underline = true)
            }
        }

        is AttachmentView.Photo -> {
            Image(
                view.image,
                contentDescription = "Foto, zum Vergrößern tippen",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = PHOTO_WIDTH)
                    .fillMaxWidth()
                    .aspectRatio(photoRatio(attachment) ?: (view.image.width.toFloat() / view.image.height))
                    .clickable(role = Role.Button) { fullScreen = true }
                    .testTag("chat.attachment.photo")
            )
            if (fullScreen) MediaDialog(secure = false, onClose = { fullScreen = false }) { FullMedia(view, kind) }
        }

        is AttachmentView.Document -> DocumentCard(view, label, color, actions)

        is AttachmentView.Playable -> if (kind == AttachmentKind.Audio) {
            VoiceMessage(view.file, attachment.durationMs ?: 0, color)
            if (transcription.available) VoiceText(attachment, transcription.transcripts[attachment.id], color, actions)
        } else {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9)
                    .background(Color.Black, RoundedCornerShape(OasisRadius.CHIP.dp))
                    .clickable(role = Role.Button) { fullScreen = true }
                    .semantics { contentDescription = "$label, abspielen" }
                    .testTag("chat.attachment.video"),
                contentAlignment = Alignment.Center
            ) {
                view.poster?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
            if (fullScreen) MediaDialog(secure = false, onClose = { fullScreen = false }) { FullMedia(view, kind) }
        }

        // Not loaded yet, or loading: a photo keeps its size from the start, so the chat does not jump once it shows.
        else -> {
            val ratio = photoRatio(attachment)?.takeIf { kind == AttachmentKind.Image }
            if (ratio == null) {
                Text("$label · wird entschlüsselt …", style = OasisType.Small, color = color)
            } else {
                Box(
                    Modifier
                        .widthIn(max = PHOTO_WIDTH)
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .background(color.copy(alpha = 0.08f), RoundedCornerShape(OasisRadius.CHIP.dp))
                        .testTag("chat.attachment.placeholder"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$label · wird entschlüsselt …", style = OasisType.Small, color = color)
                }
            }
        }
    }
}

/** How wide a photo shows in a bubble. */
private val PHOTO_WIDTH = 280.dp

/** A photo's width to height from the sender's message, or null when it names none. */
internal fun photoRatio(attachment: MessageAttachment): Float? {
    val width = attachment.width ?: return null
    val height = attachment.height ?: return null
    return if (width > 0 && height > 0) width.toFloat() / height else null
}

/** A voice message in a bubble or the view-once viewer: the player, or the error text when the file cannot be played. */
@Composable
internal fun VoiceMessage(file: File, durationMs: Long, color: Color) {
    val context = LocalContext.current
    val player = remember(file) { VoicePlayer(file, durationMs, SystemAudioFocus(context)) }
    DisposableEffect(player) { onDispose { player.release() } }
    VoiceControls(player, color)
}

/**
 * The voice message player (the same on the web and iOS): a round „Abspielen“ / „Pausieren“ button and a thin bar to tap
 * or drag to a position (TalkBack: the slider „Position“ in steps of 5 s); under the bar the time („0:12 / 0:45“, before
 * the first play and after the end only „0:45“) and the speed („1×“ → „1,5×“ → „2×“, for every voice message while the
 * app runs). A file that cannot be played shows the error text and no controls. At least 260 dp wide, so the bar is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceControls(player: VoicePlayer, color: Color, modifier: Modifier = Modifier) {
    if (player.failed) {
        Note("Die Sprachnachricht lässt sich nicht abspielen.", color)
        return
    }
    // The length is read from the file when the message does not name it.
    LaunchedEffect(player) { if (player.durationMs == 0L) player.load() }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.tick()
            delay(200)
        }
    }
    val position = player.positionMs
    val total = player.durationMs
    val end = total.coerceAtLeast(1).toFloat()
    val time = if (player.started) {
        "${AttachmentTexts.duration(position)} / ${AttachmentTexts.duration(total)}"
    } else {
        AttachmentTexts.duration(total)
    }
    Row(
        modifier = modifier.widthIn(min = 260.dp).testTag("chat.voice"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(role = Role.Button, onClick = player::toggle)
                .semantics { contentDescription = if (player.playing) "Pausieren" else "Abspielen" }
                .testTag("chat.attachment.play"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (player.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = null,
                tint = if (color == OasisColors.Ink) OasisColors.Paper else OasisColors.Night
            )
        }
        Column(Modifier.weight(1f)) {
            Slider(
                value = position.toFloat().coerceIn(0f, end),
                onValueChange = { player.seekTo(it.toLong()) },
                valueRange = 0f..end,
                // A thin rounded track, the played part in the bubble's ink, a small round thumb.
                thumb = { Box(Modifier.size(12.dp).background(color, CircleShape)) },
                track = { slider ->
                    val played = (slider.value - slider.valueRange.start) / (slider.valueRange.endInclusive - slider.valueRange.start)
                    Box(Modifier.fillMaxWidth().height(4.dp).background(color.copy(alpha = 0.25f), CircleShape)) {
                        Box(Modifier.fillMaxWidth(played).height(4.dp).background(color, CircleShape))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clearAndSetSemantics {
                        contentDescription = "Position"
                        stateDescription = time
                        testTag = "chat.voice.position"
                        progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat(), 0f..end)
                        // TalkBack moves in steps of 5 s, whatever step it asks for.
                        setProgress { target ->
                            player.seekTo(position + if (target > position) VOICE_STEP_MS else -VOICE_STEP_MS)
                            true
                        }
                    }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = OasisType.Small, color = color, modifier = Modifier.weight(1f).testTag("chat.voice.time"))
                Text(
                    VoiceSpeed.label(),
                    style = OasisType.SmallBold,
                    color = color,
                    modifier = Modifier
                        .clip(RoundedCornerShape(OasisRadius.CHIP.dp))
                        .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(OasisRadius.CHIP.dp))
                        .clickable(role = Role.Button, onClickLabel = "Geschwindigkeit ändern", onClick = VoiceSpeed::next)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("chat.voice.speed")
                )
            }
        }
    }
}

/**
 * Under a voice message, while German speech can be recognised on this device: „Als Text anzeigen“, then „Wird
 * umgewandelt …“, the text with „Text ausblenden“, or why it failed. Not for view-once voice messages (their viewer has
 * none), not in the tray.
 */
@Composable
private fun VoiceText(attachment: MessageAttachment, transcript: Transcript?, color: Color, actions: AttachmentActions) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("chat.voice.text")) {
        when (transcript) {
            Transcript.Working -> Text("Wird umgewandelt …", style = OasisType.Small, color = color)

            is Transcript.Shown -> {
                Text(transcript.text, style = OasisType.Body, color = color, modifier = Modifier.testTag("chat.voice.transcript"))
                OasisTextButton("Text ausblenden", { actions.text.onHide(attachment.id) }, color = color)
            }

            else -> {
                if (transcript is Transcript.Failed) Text(transcript.message, style = OasisType.Small, color = color)
                OasisTextButton(
                    "Als Text anzeigen",
                    { scope.launch { actions.text.onTranscribe(attachment) } },
                    color = color,
                    modifier = Modifier.testTag("chat.voice.transcribe")
                )
            }
        }
    }
}

/** How far TalkBack moves a voice message per step. */
private const val VOICE_STEP_MS = 5_000L

/** A full-screen window for a photo or video; [secure] blocks screenshots and recordings (view once). */
@Composable
internal fun MediaDialog(secure: Boolean, onClose: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit
        )
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp).testTag("chat.attachment.fullScreen")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                actions()
                OasisTextButton("Schließen", onClose, color = Color.White, modifier = Modifier.testTag("chat.attachment.close"))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** The file itself in the full-screen window: photo, video (platform player) or voice message. */
@Composable
internal fun FullMedia(view: AttachmentView, kind: AttachmentKind) {
    var unplayable by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (view is AttachmentView.Photo) {
            Image(view.image, contentDescription = "Foto", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
        } else {
            // The viewers only open shown files: a photo, or a voice message or video in the cache.
            val file = (view as AttachmentView.Playable).file
            if (kind == AttachmentKind.Video) {
                AndroidView(
                    factory = { context -> VideoView(context).apply { playFile(file) { unplayable = true } } },
                    modifier = Modifier.fillMaxWidth().testTag("chat.attachment.player")
                )
            } else {
                Text(AttachmentTexts.title(kind), style = OasisType.BodyBold, color = Color.White)
                VoiceMessage(file, 0, Color.White)
            }
        }
        if (unplayable) Text("Das Video lässt sich nicht abspielen.", style = OasisType.Small, color = Color.White)
    }
}

/**
 * The open view-once file: a photo, video or voice message full screen with FLAG_SECURE (no screenshots or recordings
 * on Android), no saving or sharing, and "Melden" while it is still open; closing deletes it. Any other file opens once
 * in another app ([ViewOnceDocument]), which Oasis cannot stop from keeping a copy.
 */
@Composable
internal fun ViewOnceViewer(viewer: ViewOnceViewer, actions: AttachmentActions, onReport: (ChatMessageItem) -> Unit) {
    val scope = rememberCoroutineScope()
    val view = viewer.view
    if (view is AttachmentView.Document) {
        ViewOnceDocument(viewer, view, actions)
        return
    }
    MediaDialog(
        secure = true,
        onClose = { scope.launch { actions.onCloseViewOnce() } },
        actions = {
            OasisTextButton("Melden", { onReport(viewer.item) }, color = Color.White, modifier = Modifier.testTag("chat.viewOnce.report"))
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FullMedia(viewer.view, AttachmentTexts.kind(viewer.attachment))
            Text(
                "Einmal ansehen: Nach dem Schließen ist es weg.",
                style = OasisType.Caption,
                color = OasisColors.OnNightMuted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("chat.viewOnce.note")
            )
        }
    }
}

/** The warning on every file someone sends. */
internal const val FILE_TRUST_NOTE = "Öffne Dateien nur von Menschen, denen du vertraust."

private fun kindIcon(kind: AttachmentKind): ImageVector = when (kind) {
    // Voice messages show their player instead (DraftVoiceMessage, VoiceMessage).
    AttachmentKind.File -> Icons.AutoMirrored.Outlined.InsertDriveFile

    else -> Icons.Outlined.Videocam
}

/**
 * A decrypted file: name, size and type, "Öffnen" and "Teilen" in other apps – never shown inside Oasis. The chat stays
 * unlocked while the other app reads it, so its copy is still there.
 */
@Composable
private fun DocumentCard(document: AttachmentView.Document, label: String, color: Color, actions: AttachmentActions) {
    val context = LocalContext.current
    var noApp by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("chat.attachment.file")
    ) {
        Icon(kindIcon(AttachmentKind.File), contentDescription = null, tint = color)
        Column(Modifier.weight(1f, fill = false)) {
            Text(label, style = OasisType.SmallBold, color = color)
            Text(document.mime, style = OasisType.Caption, color = color)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallAction("Öffnen", {
            noApp = !actions.onExternalScreen { context.startOrFalse(fileIntent(context, document, share = false)) }
        }, color = color, underline = true)
        SmallAction("Teilen", {
            noApp = !actions.onExternalScreen { context.startOrFalse(fileIntent(context, document, share = true)) }
        }, color = color, underline = true)
    }
    if (noApp) Note(ConversationViewModel.NO_APP, color)
}

/**
 * A view-once file: opened once in another app (no "Teilen"); when the member comes back to Oasis it counts as viewed
 * and its copy is deleted.
 */
@Composable
private fun ViewOnceDocument(viewer: ViewOnceViewer, document: AttachmentView.Document, actions: AttachmentActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember(viewer) { mutableStateOf(false) }
    LaunchedEffect(viewer) {
        if (actions.onExternalScreen { context.startOrFalse(fileIntent(context, document, share = false)) }) {
            opened = true
        } else {
            actions.onCancelViewOnce()
        }
    }
    // Oasis goes to the background while the other app shows the file; coming back closes it (once).
    var left by remember(viewer) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { left = opened }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (left) {
            left = false
            opened = false
            scope.launch { actions.onCloseViewOnce() }
        }
    }
}
