package de.velwebsolutions.oasis.network.ui.oases

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.AvatarTint
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.ErrorStateView
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.Choice
import de.velwebsolutions.oasis.network.ui.ImageConverter
import de.velwebsolutions.oasis.network.ui.components.ChoiceList
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.PaperBox
import de.velwebsolutions.oasis.network.ui.components.SectionTitle
import de.velwebsolutions.oasis.network.ui.components.UnderlinedLink
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.rememberImagePicker

/**
 * Found an oasis (/app/oasen/neu) or edit it as host (/oasen/{slug}/bearbeiten): texts, house rules, audience, colour,
 * the optional banner (stored at once when editing, with "Oase eröffnen" when founding), closing and roles.
 */
@Composable
fun OasisFormScreen(
    slug: String?,
    viewModel: OasisFormViewModel =
        hiltViewModel<OasisFormViewModel, OasisFormViewModel.Factory>(key = "oasisForm:$slug", creationCallback = { it.create(slug) })
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val editing = slug != null
    val picker = rememberImagePicker { uris ->
        viewModel.launch { viewModel.pickBanner(ImageConverter.upload(context, uris.first(), "banner", maxEdge = 3000)) }
    }

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    LaunchedEffect(state.outcome) {
        when (val outcome = state.outcome) {
            OasisFormOutcome.Close -> navigator.back()

            is OasisFormOutcome.Open -> {
                navigator.back()
                navigator.open(NetworkRoute.Oasis(outcome.slug))
            }

            null -> return@LaunchedEffect
        }
        viewModel.consumeOutcome()
    }

    OasisScreen(
        title = if (editing) "Oase bearbeiten" else "Oase eröffnen",
        message = status,
        onDismissMessage = viewModel::dismissStatus
    ) {
        val loadError = state.loadError
        val options = state.options
        when {
            loadError != null -> ErrorStateView(loadError, onRetry = viewModel::load)

            options == null -> LoadingView()

            else -> NetworkPage {
                block("header") {
                    PageHeader(
                        if (editing) "${state.edit?.oasis?.name.orEmpty()} bearbeiten" else "Oase eröffnen",
                        lead = if (editing) {
                            null
                        } else {
                            "Du wirst Gastgeber:in: Du achtest auf die Hausregeln und kannst Tropfen entfernen, die dagegen verstoßen."
                        }
                    )
                }
                block("form") {
                    PaperBox(spacing = 18) {
                        val failure = error
                        if (failure != null && OasisFormViewModel.FIELDS.none { failure.message(it) != null }) Banner(failure.userMessage)
                        OasisTextField(
                            "Name",
                            state.input.name,
                            { value -> viewModel.updateInput { it.copy(name = value) } },
                            error = failure?.message("name"),
                            modifier = Modifier.testTag("oasisForm.name")
                        )
                        OasisTextField(
                            "Worum geht es?",
                            state.input.description,
                            { value -> viewModel.updateInput { it.copy(description = value) } },
                            error = failure?.message("description"),
                            hint = "Ein Satz, der neugierig macht.",
                            lines = 1..4,
                            modifier = Modifier.testTag("oasisForm.description")
                        )
                        OasisTextField(
                            "Hausregeln",
                            state.input.houseRules,
                            { value -> viewModel.updateInput { it.copy(houseRules = value) } },
                            error = failure?.message("house_rules"),
                            hint = "Gelten zusätzlich zu den Community-Regeln von Oasis.",
                            lines = 4..10,
                            modifier = Modifier.testTag("oasisForm.rules")
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Wer darf die Oase lesen?", style = OasisType.SmallBold, color = OasisColors.Ink)
                            ChoiceList(options.visibilities, state.input.visibility, { value ->
                                viewModel.updateInput { it.copy(visibility = value) }
                            })
                            failure?.message("visibility")?.let { FieldError(it) }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Farbe", style = OasisType.SmallBold, color = OasisColors.Ink)
                            ColorChoice(options.colors, state.input.color) { value -> viewModel.updateInput { it.copy(color = value) } }
                            failure?.message("color")?.let { FieldError(it) }
                        }
                        BannerSection(state, editing, failure?.message("banner"), onPick = picker.pick, onRemove = viewModel::removeBanner)
                        AsyncOasisButton(
                            if (editing) "Speichern" else "Oase eröffnen",
                            viewModel::save,
                            fullWidth = false,
                            modifier = Modifier.testTag("oasisForm.save")
                        )
                    }
                }
                val edit = state.edit
                if (edit != null && slug != null) {
                    block("close") {
                        val navigator = LocalNavigator.current
                        PaperBox(border = OasisColors.SunText) {
                            SectionTitle("Oase schließen")
                            Text(
                                "Die Oase verschwindet für alle. Die Tropfen bleiben bei den Menschen, die sie geschrieben haben.",
                                style = OasisType.Body,
                                color = OasisColors.Ink2
                            )
                            OasisButton(
                                "Oase schließen …",
                                { navigator.open(NetworkRoute.CloseOasis(slug)) },
                                kind = OasisButtonKind.Secondary,
                                fullWidth = false,
                                modifier = Modifier.testTag("oasisForm.close")
                            )
                        }
                    }
                    block("roles") {
                        PaperBox {
                            SectionTitle("Gastgeber:innen")
                            error?.message("role")?.let { FieldError(it) }
                            edit.members.forEach { member ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        buildAnnotatedString {
                                            append(member.displayName)
                                            withStyle(SpanStyle(color = OasisColors.Muted)) { append(" @${member.username}") }
                                            append(" · ${member.roleLabel}")
                                        },
                                        style = OasisType.Small,
                                        color = OasisColors.Ink,
                                        modifier = Modifier.weight(1f)
                                    )
                                    val host = member.role == "host"
                                    UnderlinedLink(
                                        if (host) "Zu Mitglied machen" else "Zu Gastgeber:in machen",
                                        { viewModel.launch { viewModel.setRole(member.id, if (host) "member" else "host") } },
                                        style = OasisType.Small
                                    )
                                }
                            }
                        }
                    }
                }
                block("side.rules") {
                    SideCard("Gute Hausregeln", tone = PanelTone.Dune) {
                        Bullets(
                            listOf(
                                "Kurz und konkret: drei bis fünf Punkte reichen.",
                                "Sag, was erwünscht ist – nicht nur, was verboten ist.",
                                "Die Community-Regeln von Oasis gelten immer zusätzlich."
                            )
                        )
                    }
                }
                block("side.host") {
                    SideCard("Als Gastgeber:in", tone = PanelTone.Night) {
                        Text(
                            "Du kannst Tropfen aus der Oase entfernen, die gegen die Hausregeln verstoßen, und weitere Gastgeber:innen " +
                                "ernennen. Rechtswidriges meldet ihr dem Moderationsteam."
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerSection(state: OasisFormState, editing: Boolean, error: String?, onPick: () -> Unit, onRemove: suspend () -> Unit) {
    val shape = RoundedCornerShape(OasisRadius.TILE.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Banner ") }
                withStyle(SpanStyle(color = OasisColors.Muted)) { append("(freiwillig)") }
            },
            style = OasisType.Small,
            color = OasisColors.Ink
        )
        val picked = state.banner
        val current = state.edit?.oasis
        if (picked != null) {
            val bitmap = remember(picked) { BitmapFactory.decodeByteArray(picked.data, 0, picked.data.size)?.asImageBitmap() }
            if (bitmap != null) {
                Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f).clip(shape)
                )
            }
            if (!editing) Text("Ausgewählt: ${picked.filename}", style = OasisType.Small, color = OasisColors.Ink2)
        } else if (current?.bannerUrl != null) {
            OasisBanner(current, Modifier.clip(shape), height = 110.dp)
        }
        val hasBanner = picked != null || current?.bannerUrl != null
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OasisButton(
                if (hasBanner) "Anderes Bild wählen" else "Bild hochladen",
                onPick,
                kind = OasisButtonKind.Secondary,
                fullWidth = false
            )
            if (hasBanner) {
                AsyncOasisButton("Entfernen", onRemove, kind = OasisButtonKind.Secondary, fullWidth = false)
            }
        }
        Text(
            "Breites Querformat, wir schneiden es auf 3:1 zu. Standort- und Kameradaten entfernen wir. Bitte nur Bilder, die zu den Community-Regeln passen.",
            style = OasisType.Caption,
            color = OasisColors.Muted
        )
        error?.let { FieldError(it) }
    }
}

/** The four colours with their German names (Sonne, Quelle, Nacht, Düne). */
@Composable
fun ColorChoice(choices: List<Choice>, selection: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        choices.forEach { choice ->
            val selected = selection == choice.value
            Column(
                Modifier
                    .defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelect(choice.value) }
                    .semantics { contentDescription = choice.label },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier.size(40.dp).border(3.dp, if (selected) OasisColors.Night else Color.Transparent, CircleShape).padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(32.dp).background(AvatarTint.background(choice.value), CircleShape))
                }
                Text(choice.label, style = OasisType.Caption, color = OasisColors.Ink)
            }
        }
    }
}
