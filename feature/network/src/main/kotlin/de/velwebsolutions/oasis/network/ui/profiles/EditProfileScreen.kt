package de.velwebsolutions.oasis.network.ui.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.AuthorizedImage
import de.velwebsolutions.oasis.design.components.AvatarTint
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.OwnProfile
import de.velwebsolutions.oasis.network.ui.ImageConverter
import de.velwebsolutions.oasis.network.ui.components.ButtonFlow
import de.velwebsolutions.oasis.network.ui.components.ImagePicker
import de.velwebsolutions.oasis.network.ui.components.MemberAvatar
import de.velwebsolutions.oasis.network.ui.components.MemberRow
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.PaperBox
import de.velwebsolutions.oasis.network.ui.components.UnderlinedLink
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.rememberImagePicker
import de.velwebsolutions.oasis.network.ui.compose.ToneHintNote
import de.velwebsolutions.oasis.network.ui.compose.ToneQuestion
import de.velwebsolutions.oasis.network.ui.oases.ColorChoice
import kotlinx.coroutines.delay

/**
 * "Dein Profil" (/app/profil): bio with tone hints, location, the optional photo and banner and the colour of the
 * initials. Who may see the profile is a privacy setting (account area).
 */
@Composable
fun EditProfileScreen() {
    val viewModel = hiltViewModel<EditProfileViewModel>()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val photoPicker = rememberImagePicker { uris ->
        viewModel.setUploading(true)
        viewModel.launch { viewModel.uploadPhoto(ImageConverter.upload(context, uris.first(), "profilbild", maxEdge = 1024)) }
    }
    val bannerPicker = rememberImagePicker { uris ->
        viewModel.setUploading(true)
        viewModel.launch { viewModel.uploadBanner(ImageConverter.upload(context, uris.first(), "banner", maxEdge = 3000)) }
    }

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    LaunchedEffect(form.bio, form.location) {
        delay(500)
        viewModel.checkTone()
    }

    OasisScreen(title = "Dein Profil", message = status, onDismissMessage = viewModel::dismissStatus) {
        LoadableView(profile, onRetry = viewModel::load) { current ->
            NetworkPage {
                block("header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow("Profil")
                        Text(
                            "Dein Profil",
                            style = OasisType.display(28),
                            color = OasisColors.Night,
                            modifier = Modifier.semantics {
                                heading()
                            }
                        )
                        UnderlinedLink(
                            "So sehen dich andere: dein Profil ansehen.",
                            { navigator.open(NetworkRoute.Profile(current.member.username)) },
                            color = OasisColors.Ink2
                        )
                        UnderlinedLink(
                            "Name und Benutzername änderst du in den Einstellungen.",
                            { navigator.open(AccountRoute.Settings) },
                            color = OasisColors.Ink2
                        )
                    }
                }
                block("form") {
                    ProfileForm(current, form, error, viewModel, photoPicker, bannerPicker)
                }
                block("preview") {
                    SideCard("Vorschau") {
                        MemberRow(current.member, size = 56.dp)
                        Text(current.bio.ifEmpty { "Noch keine Beschreibung." }, style = OasisType.Small, color = OasisColors.Ink2)
                        ButtonFlow {
                            OasisButton("Deine Zahlen", {
                                navigator.open(NetworkRoute.Statistics)
                            }, kind = OasisButtonKind.Secondary, fullWidth = false)
                            OasisButton("Menschen einladen", {
                                navigator.open(NetworkRoute.Invitations)
                            }, kind = OasisButtonKind.Secondary, fullWidth = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileForm(
    current: OwnProfile,
    form: EditProfileState,
    error: ApiError?,
    viewModel: EditProfileViewModel,
    photoPicker: ImagePicker,
    bannerPicker: ImagePicker
) {
    val navigator = LocalNavigator.current
    PaperBox(spacing = 18) {
        val fields = listOf("bio", "location", "avatar_color", "photo")
        if (error != null && fields.none { error.message(it) != null }) Banner(error.userMessage)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OasisTextField(
                "Über dich (optional)",
                form.bio,
                viewModel::updateBio,
                error = error?.message("bio"),
                lines = 3..8,
                modifier = Modifier.testTag("profileEdit.bio")
            )
            AnimatedVisibility(form.toneHint != null) { form.toneHint?.let { ToneHintNote(it) } }
            AnimatedVisibility(form.toneNudge) {
                ToneQuestion(
                    text =
                        "Nochmal lesen? Deine Beschreibung klingt verletzend. Vielleicht findest du andere Worte. Ist sie deutlich " +
                            "beleidigend, hetzerisch oder herabsetzend, sehen andere sie erst, wenn das Moderationsteam sie geprüft hat.",
                    confirmation = "So speichern – ich meine es genau so.",
                    isConfirmed = form.toneConfirmed,
                    onConfirmedChange = viewModel::setToneConfirmed,
                    tag = "profileEdit.toneConfirmed"
                )
            }
            if (current.bioHeld) HeldBadge()
        }
        OasisTextField(
            "Ort (optional)",
            form.location,
            viewModel::updateLocation,
            error = error?.message("location"),
            hint = "Zum Beispiel deine Stadt. Lass es leer, wenn du das nicht zeigen möchtest.",
            modifier = Modifier.testTag("profileEdit.location")
        )
        Text(
            buildAnnotatedString {
                append("Wer dein Profil lesen und mit dir interagieren darf, wählst du in den ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) {
                    append("Privatsphäre-Einstellungen")
                }
                append(".")
            },
            style = OasisType.Body,
            color = OasisColors.Ink2,
            modifier = Modifier.clickable(role = Role.Button) { navigator.open(AccountRoute.Privacy) }
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionalLabel("Profilbild")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(current.member, size = 64.dp)
                OasisButton(
                    if (current.hasPhoto) "Anderes Bild wählen" else "Bild hochladen",
                    photoPicker.pick,
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false,
                    enabled = !form.uploading,
                    modifier = Modifier.testTag("profileEdit.photo")
                )
                if (current.hasPhoto) {
                    AsyncOasisButton(
                        "Entfernen",
                        viewModel::removePhoto,
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false
                    )
                }
            }
            photoPicker.takePhoto?.let { take -> UnderlinedLink("Foto aufnehmen", take, style = OasisType.Small) }
            if (form.uploading) Text("Bild wird hochgeladen …", style = OasisType.Small, color = OasisColors.Muted)
            Text(
                "Quadratisch zugeschnitten. Standort- und Kameradaten entfernen wir. Menschen, die Bilder von Fremden erst freigeben, " +
                    "sehen statt des Bildes deine Initialen. Bitte nur Bilder, die zu den Community-Regeln passen.",
                style = OasisType.Caption,
                color = OasisColors.Muted
            )
            error?.message("photo")?.let { FieldError(it) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionalLabel("Banner")
            current.bannerUrl?.let { url ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f)
                        .clip(RoundedCornerShape(OasisRadius.TILE.dp))
                        .background(AvatarTint.background(current.member.avatarColor))
                        .clearAndSetSemantics {}
                ) {
                    AuthorizedImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OasisButton(
                    if (current.bannerUrl != null) "Anderes Bild wählen" else "Bild hochladen",
                    bannerPicker.pick,
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false,
                    enabled = !form.uploading,
                    modifier = Modifier.testTag("profileEdit.banner")
                )
                if (current.bannerUrl != null) {
                    AsyncOasisButton(
                        "Entfernen",
                        viewModel::removeBanner,
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false,
                        modifier = Modifier.semantics { contentDescription = "Banner entfernen" }
                    )
                }
            }
            Text(
                "Breites Querformat, wir schneiden es auf 3:1 zu. Standort- und Kameradaten entfernen wir. Menschen, die Bilder von " +
                    "Fremden erst freigeben, sehen stattdessen deine Farbe. Bitte nur Bilder, die zu den Community-Regeln passen.",
                style = OasisType.Caption,
                color = OasisColors.Muted
            )
            error?.message("banner")?.let { FieldError(it) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Farbe deiner Initialen", style = OasisType.SmallBold, color = OasisColors.Ink)
            ColorChoice(current.colors, form.color, viewModel::updateColor)
            error?.message("avatar_color")?.let { FieldError(it) }
        }
        AsyncOasisButton("Profil speichern", viewModel::save, fullWidth = false, modifier = Modifier.testTag("profileEdit.save"))
    }
}

@Composable
private fun OptionalLabel(title: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$title ") }
            withStyle(SpanStyle(color = OasisColors.Muted)) { append("(freiwillig)") }
        },
        style = OasisType.Small,
        color = OasisColors.Ink
    )
}
