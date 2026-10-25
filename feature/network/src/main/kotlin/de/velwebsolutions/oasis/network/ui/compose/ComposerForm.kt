package de.velwebsolutions.oasis.network.ui.compose

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.ConsentCheck
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.ExternalLink
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.PublishResult
import de.velwebsolutions.oasis.network.ui.ImageConverter
import de.velwebsolutions.oasis.network.ui.components.ChoiceMenu
import de.velwebsolutions.oasis.network.ui.components.UnderlinedLink
import de.velwebsolutions.oasis.network.ui.components.rememberImagePicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the composer form can ask its view model for. */
class ComposerCallbacks(
    val onBody: (String) -> Unit,
    val onVisibility: (String) -> Unit,
    val onKind: (String) -> Unit,
    val onLinkRead: (Boolean) -> Unit,
    val onToneConfirmed: (Boolean) -> Unit,
    /** A picked image, or null when it could not be read. */
    val onAddImage: (ImageUpload?) -> Unit,
    val onRemoveImage: (Int) -> Unit,
    val onAlt: (Int, String) -> Unit,
    val checkTone: suspend () -> Unit,
    val publish: suspend () -> PublishResult?
)

/** The form's callbacks wired to this view model. */
fun ComposeViewModel.callbacks() = ComposerCallbacks(
    onBody = ::updateBody,
    onVisibility = ::setVisibility,
    onKind = ::setKind,
    onLinkRead = ::setLinkRead,
    onToneConfirmed = ::setToneConfirmed,
    onAddImage = ::add,
    onRemoveImage = ::removeImage,
    onAlt = ::updateAlt,
    checkTone = ::checkTone,
    publish = ::publish
)

/**
 * The composer form (`livewire/network/compose`): text with live tone hints, "Nochmal lesen?" with "So senden", the
 * read-before-carry confirmation, up to four images with required descriptions, audience and kind.
 */
@Composable
fun ComposerForm(
    state: ComposeUiState,
    callbacks: ComposerCallbacks,
    modifier: Modifier = Modifier,
    showsImages: Boolean = true,
    /** Focuses the text field once ("Antworten" on a card opens the reply field ready to type). */
    focusRequest: Boolean = false,
    onFocusHandled: () -> Unit = {},
    /** Called after a successful publish. */
    onPublished: (PublishResult) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var publishing by remember { mutableStateOf(false) }
    var loadingImages by remember { mutableStateOf(false) }

    LaunchedEffect(state.body) {
        delay(400)
        callbacks.checkTone()
    }
    LaunchedEffect(focusRequest) {
        if (focusRequest) {
            runCatching { focus.requestFocus() }
            onFocusHandled()
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.generalError?.let { Banner(it) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OasisTextField(
                label = state.bodyLabel,
                value = state.body,
                onValueChange = callbacks.onBody,
                prompt = if (state.isReply) {
                    "Antworten …"
                } else if (state.isCarry) {
                    null
                } else {
                    "Was möchtest du teilen?"
                },
                error = state.fieldError("body"),
                lines = 3..10,
                modifier = Modifier.focusRequester(focus).testTag("compose.body")
            )
            Text(
                "${state.length}/${state.maxLength}",
                style = OasisType.Caption,
                color = OasisColors.Muted,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${state.length} von ${state.maxLength} Zeichen" }
            )
            AnimatedVisibility(state.toneHint != null) { state.toneHint?.let { ToneHintNote(it) } }
        }
        AnimatedVisibility(state.toneNudge) {
            ToneQuestion(
                text = "Nochmal lesen? Dein Tropfen klingt verletzend. Vielleicht findest du andere Worte. Ist er deutlich beleidigend, " +
                    "hetzerisch oder herabsetzend, halten wir ihn nach dem Senden zurück, bis das Moderationsteam ihn geprüft hat.",
                confirmation = "So senden – ich meine es genau so.",
                isConfirmed = state.toneConfirmed,
                onConfirmedChange = callbacks.onToneConfirmed,
                tag = "compose.toneConfirmed"
            )
        }
        val links = state.context.valueOrNull?.links.orEmpty()
        if (links.isNotEmpty()) LinkConfirmation(links, state, callbacks)
        if (showsImages) {
            ImagesSection(state, callbacks, loadingImages, onLoading = { loadingImages = it })
        }
        Options(state, callbacks)
        OasisButton(
            text = state.submitLabel,
            // While it publishes the button shows its spinner and takes no further taps.
            onClick = {
                publishing = true
                scope.launch {
                    try {
                        callbacks.publish()?.let(onPublished)
                    } finally {
                        publishing = false
                    }
                }
            },
            fullWidth = false,
            enabled = !loadingImages,
            loading = publishing || state.isPublishing,
            modifier = Modifier.testTag("compose.submit")
        )
    }
}

@Composable
private fun LinkConfirmation(links: List<ExternalLink>, state: ComposeUiState, callbacks: ComposerCallbacks) {
    val navigator = LocalNavigator.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Hast du den Link gelesen? Weitertragen heißt: Du stehst dafür ein.",
            style = OasisType.BodyBold,
            color = OasisColors.Ink,
            modifier = Modifier.semantics { heading() }
        )
        links.forEach { link -> UnderlinedLink(link.host, { navigator.open(WebRoute(link.url)) }) }
        ConsentCheck("Ich habe den Inhalt gelesen", state.linkRead, callbacks.onLinkRead, Modifier.testTag("compose.linkRead"))
        state.fieldError("link_read")?.let { FieldError(it) }
    }
}

@Composable
private fun ImagesSection(state: ComposeUiState, callbacks: ComposerCallbacks, loadingImages: Boolean, onLoading: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val max = state.maxImages
    val remaining = maxOf(0, max - state.images.size)
    val picker = rememberImagePicker(maxItems = maxOf(1, remaining)) { uris: List<Uri> ->
        scope.launch {
            onLoading(true)
            try {
                uris.forEachIndexed { offset, uri ->
                    callbacks.onAddImage(ImageConverter.upload(context, uri, "bild-${state.images.size + offset + 1}"))
                }
            } finally {
                onLoading(false)
            }
        }
    }
    val shape = RoundedCornerShape(OasisRadius.TILE.dp)
    Column(
        Modifier.fillMaxWidth().border(1.dp, OasisColors.DuneLine, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTextButton(
                "Bilder hinzufügen",
                Icons.Outlined.Add,
                enabled = remaining > 0,
                tag = "compose.addImages",
                onClick = picker.pick
            )
            picker.takePhoto?.let {
                IconTextButton("Foto aufnehmen", Icons.Outlined.PhotoCamera, enabled = remaining > 0, tag = "compose.camera", onClick = it)
            }
        }
        Text("bis zu $max, JPG, PNG, WebP oder GIF", style = OasisType.Caption, color = OasisColors.Muted)
        if (loadingImages) Text("Bilder werden hochgeladen …", style = OasisType.Small, color = OasisColors.Muted)
        state.fieldError("images")?.let { FieldError(it) }
        state.images.forEachIndexed { index, picked ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                val thumbnail = remember(picked.id) { ImageConverter.thumbnail(picked.upload.data)?.asImageBitmap() }
                if (thumbnail != null) {
                    Image(
                        thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(OasisRadius.CHIP.dp))
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OasisTextField(
                        label = "Bildbeschreibung ${index + 1}",
                        value = picked.alt,
                        onValueChange = { callbacks.onAlt(index, it) },
                        error = state.fieldError("alts.$index") ?: state.fieldError("images.$index"),
                        modifier = Modifier.testTag("compose.alt.$index")
                    )
                    UnderlinedLink("Bild ${index + 1} entfernen", { callbacks.onRemoveImage(index) }, style = OasisType.Small)
                }
            }
        }
        Text("Wir entfernen beim Hochladen Standort und Kameradaten aus den Bildern.", style = OasisType.Caption, color = OasisColors.Muted)
    }
}

@Composable
private fun IconTextButton(title: String, icon: ImageVector, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(20.dp))
        OasisTextButton(title, onClick, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun Options(state: ComposeUiState, callbacks: ComposerCallbacks) {
    val context = state.context.valueOrNull ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Wer darf lesen?", style = OasisType.SmallBold, color = OasisColors.Ink)
            ChoiceMenu(
                "Wer darf lesen?",
                context.visibilities,
                state.visibility,
                callbacks.onVisibility,
                Modifier.testTag("compose.visibility")
            )
            // Under 18 "Öffentlich" is not offered (the server leaves it out), so the hint only shows where it applies.
            if (context.visibilities.any { it.value == "public" }) {
                Text("Öffentlich heißt: auch ohne Konto lesbar.", style = OasisType.Caption, color = OasisColors.Muted)
            }
        }
        if (context.kinds.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Art des Tropfens", style = OasisType.SmallBold, color = OasisColors.Ink)
                ChoiceMenu("Art des Tropfens", context.kinds, state.kind, callbacks.onKind, Modifier.testTag("compose.kind"))
            }
        }
        (state.fieldError("visibility") ?: state.fieldError("kind"))?.let { FieldError(it) }
    }
}
