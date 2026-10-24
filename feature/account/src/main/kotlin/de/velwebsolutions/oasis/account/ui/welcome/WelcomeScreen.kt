package de.velwebsolutions.oasis.account.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.api.WelcomeTour
import de.velwebsolutions.oasis.account.ui.components.FlowChoices
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.AuthorizedImage
import de.velwebsolutions.oasis.design.components.AvatarTint
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Einstieg (/app/willkommen): about you, who sees you, oases, people. Every step can be skipped, the whole tour too
 * („Später“); finishing or skipping ends it for good, so the app stops opening it. A new step fades in at the top.
 */
@Composable
internal fun WelcomeScreen(viewModel: WelcomeViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // The live hint waits for a short pause in typing.
    LaunchedEffect(ui.bio, ui.location) {
        delay(500)
        viewModel.textChanged()
    }
    val finish: suspend () -> Unit = {
        // Opened again from the settings: back to where it came from. New members get the Quelle by themselves.
        if (viewModel.finish() == true) navigator.back()
    }
    // System back goes one step back first, like the „Zurück“ button.
    BackHandler(enabled = ui.step > 1, onBack = viewModel::back)
    OasisScreen(title = "Willkommen") {
        WelcomeContent(ui, viewModel, finish, navigator::open)
    }
}

@Composable
internal fun WelcomeContent(ui: WelcomeUiState, viewModel: WelcomeViewModel, finish: suspend () -> Unit, open: (AppRoute) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(ui.step) { scroll.animateScrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            LoadableView(ui.state, onRetry = viewModel::load) { tour ->
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Header(ui, tour, finish)
                    ui.error?.let { Banner(it) }
                    AnimatedContent(ui.stepKey, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step") { key ->
                        Column(
                            Modifier.fillMaxWidth().background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp)).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            when (key) {
                                "profil" -> ProfileStep(ui, tour, viewModel)
                                "sichtbarkeit" -> VisibilityStep(ui, tour, viewModel, open)
                                "oasen" -> OasesStep(tour, viewModel, open)
                                else -> PeopleStep(open)
                            }
                        }
                    }
                    Buttons(ui, tour, viewModel, finish)
                    Text(
                        "Jeder Schritt ist freiwillig. Alles findest du später auch in deinem Profil, den Privatsphäre-Einstellungen und unter Oasen.",
                        style = OasisType.Small,
                        color = OasisColors.Muted
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(ui: WelcomeUiState, tour: WelcomeTour, finish: suspend () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
            Eyebrow("Willkommen · Schritt ${ui.step} von ${tour.steps.size}", Modifier.align(Alignment.CenterVertically))
            LaterButton(finish)
        }
        Text(
            tour.steps.getOrNull(ui.step - 1)?.title.orEmpty(),
            style = OasisType.display(30),
            color = OasisColors.Night,
            modifier = Modifier.semantics { heading() }.testTag("welcome.title")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (index in 1..maxOf(tour.steps.size, 1)) {
                Box(Modifier.weight(1f).height(6.dp).background(if (index <= ui.step) OasisColors.Night else OasisColors.Dune, CircleShape))
            }
        }
    }
}

@Composable
private fun LaterButton(finish: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    OasisTextButton(
        "Später – direkt zur Quelle",
        {
            if (!running) {
                running = true
                scope.launch {
                    finish()
                    running = false
                }
            }
        },
        Modifier.testTag("welcome.later")
    )
}

@Composable
private fun ColumnScope.ProfileStep(ui: WelcomeUiState, tour: WelcomeTour, viewModel: WelcomeViewModel) {
    Text(
        "Ein paar Worte helfen anderen, dich einzuordnen. Alles ist freiwillig und lässt sich jederzeit ändern.",
        style = OasisType.Body,
        color = OasisColors.Ink2
    )
    OasisTextField(
        "Über dich (optional)",
        ui.bio,
        { value -> viewModel.edit { it.copy(bio = value) } },
        modifier = Modifier.testTag("welcome.bio"),
        prompt = "Zum Beispiel: Ich fotografiere analog und gärtnere auf dem Balkon.",
        error = ui.fieldErrors["bio"],
        lines = 3..6
    )
    OasisTextField(
        "Ort (optional)",
        ui.location,
        { value -> viewModel.edit { it.copy(location = value) } },
        prompt = "Zum Beispiel deine Stadt",
        error = ui.fieldErrors["location"]
    )
    ui.toneHint?.let { hint ->
        Text(
            "Kurzer Hinweis: „$hint“ wirkt schnell herablassend. Bitte achte auf einen freundlichen Ton – siehe Regeln zum Umgangston.",
            style = OasisType.Small,
            color = OasisColors.Ink,
            modifier = Modifier.fillMaxWidth().background(OasisColors.Dune, RoundedCornerShape(OasisRadius.CHIP.dp)).padding(12.dp)
        )
    }
    if (ui.toneQuestion) {
        Column(
            Modifier.fillMaxWidth().background(OasisColors.Dune, RoundedCornerShape(OasisRadius.TILE.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Nochmal lesen? Deine Beschreibung klingt verletzend. Vielleicht findest du andere Worte. Ist sie deutlich beleidigend, hetzerisch oder herabsetzend, sehen andere sie erst, wenn das Moderationsteam sie geprüft hat.",
                style = OasisType.Small,
                color = OasisColors.Ink
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "So speichern – ich meine es genau so.",
                    style = OasisType.Small,
                    color = OasisColors.Ink,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = ui.toneConfirmed,
                    onCheckedChange = { on -> viewModel.edit { it.copy(toneConfirmed = on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = OasisColors.Night, checkedThumbColor = OasisColors.OnNight),
                    modifier = Modifier.semantics {
                        contentDescription = "So speichern – ich meine es genau so."
                    }.testTag("welcome.toneConfirmed")
                )
            }
        }
    }
    Text("Farbe deines Profilbilds", style = OasisType.SmallBold, color = OasisColors.Ink)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        tour.colors.forEach { color ->
            val isSelected = ui.avatarColor == color.value
            Column(
                Modifier
                    .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                    .clip(RoundedCornerShape(OasisRadius.CHIP.dp))
                    .clickable(role = Role.RadioButton) { viewModel.edit { it.copy(avatarColor = color.value) } }
                    .semantics {
                        contentDescription = color.label
                        selected = isSelected
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(AvatarTint.background(color.value), CircleShape)
                        .then(if (isSelected) Modifier.border(3.dp, OasisColors.Night, CircleShape) else Modifier)
                )
                Text(color.label, style = OasisType.Caption, color = OasisColors.Ink)
            }
        }
    }
}

@Composable
private fun ColumnScope.VisibilityStep(ui: WelcomeUiState, tour: WelcomeTour, viewModel: WelcomeViewModel, open: (AppRoute) -> Unit) {
    Text(
        "Oasis startet zurückhaltend: Nur Mitglieder sehen dich. Du kannst das jetzt ändern oder später in Ruhe im Privatsphäre-Check.",
        style = OasisType.Body,
        color = OasisColors.Ink2
    )
    Text("Wer darf dein Profil sehen?", style = OasisType.BodyBold, color = OasisColors.Night)
    FlowChoices(tour.audiences, ui.profileAudience, tag = "welcome.audience") { value ->
        viewModel.edit { it.copy(profileAudience = value) }
    }
    ui.fieldErrors["profile_audience"]?.let { FieldError(it) }
    Text("Wer liest deine Tropfen normalerweise?", style = OasisType.BodyBold, color = OasisColors.Night)
    FlowChoices(tour.visibilities, ui.defaultPostVisibility, tag = "welcome.visibility") { value ->
        viewModel.edit { it.copy(defaultPostVisibility = value) }
    }
    ui.fieldErrors["default_post_visibility"]?.let { FieldError(it) }
    LinkText("Alle Einstellungen im Privatsphäre-Check", { open(AccountRoute.PrivacyCheckup) })
}

@Composable
private fun ColumnScope.OasesStep(tour: WelcomeTour, viewModel: WelcomeViewModel, open: (AppRoute) -> Unit) {
    Text(
        "Oasen sind Gruppen zu einem Thema. Folge ein paar davon, dann füllt sich deine Quelle. Ausgesucht von Menschen, nicht von einem Algorithmus.",
        style = OasisType.Body,
        color = OasisColors.Ink2
    )
    if (tour.oases.isEmpty()) {
        Text("Noch gibt es keine öffentlichen Oasen. Du kannst selbst eine gründen.", style = OasisType.Body, color = OasisColors.Ink2)
    }
    tour.oases.forEach { oasis ->
        Column(
            Modifier.fillMaxWidth().border(1.dp, OasisColors.PaperLine, RoundedCornerShape(OasisRadius.TILE.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val banner = oasis.bannerUrl
                if (banner != null) {
                    AuthorizedImage(
                        banner,
                        contentDescription = null,
                        modifier = Modifier.size(width = 72.dp, height = 36.dp).clip(RoundedCornerShape(OasisRadius.CHIP.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.size(36.dp).background(AvatarTint.surface(oasis.color), CircleShape))
                }
                Column {
                    Text(oasis.name, style = OasisType.BodyBold, color = OasisColors.Night)
                    Text(
                        "${oasis.membersCount} ${if (oasis.membersCount == 1) "Mitglied" else "Mitglieder"}",
                        style = OasisType.Small,
                        color = OasisColors.Muted
                    )
                }
            }
            Text(oasis.description, style = OasisType.Small, color = OasisColors.Ink2)
            AsyncOasisButton(
                if (oasis.joined) "Gefolgt ✓" else "Folgen",
                { viewModel.toggle(oasis) },
                modifier = Modifier.semantics { selected = oasis.joined }.testTag("welcome.oasis.${oasis.slug}"),
                kind = if (oasis.joined) OasisButtonKind.Primary else OasisButtonKind.Secondary,
                fullWidth = false
            )
        }
    }
    LinkText("Alle Oasen ansehen", { open(NetworkRoute.Oases) })
}

@Composable
private fun ColumnScope.PeopleStep(open: (AppRoute) -> Unit) {
    var search by remember { mutableStateOf("") }
    Text(
        "Deine Quelle zeigt, was Menschen teilen, denen du folgst – der Reihe nach und mit Ende. Such nach Menschen, die du kennst, oder lade sie ein.",
        style = OasisType.Body,
        color = OasisColors.Ink2
    )
    OasisTextField("Menschen suchen", search, {
        search = it
    }, prompt = "Name oder @benutzername", keyboardOptions = OasisKeyboard.NoCapitalization)
    OasisButton("Suchen", { open(NetworkRoute.Search(search.ifEmpty { null })) }, kind = OasisButtonKind.Secondary, fullWidth = false)
    OasisButton("Freund:innen einladen", { open(NetworkRoute.Invitations) }, kind = OasisButtonKind.Secondary, fullWidth = false)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(ui: WelcomeUiState, tour: WelcomeTour, viewModel: WelcomeViewModel, finish: suspend () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.step > 1) OasisButton("Zurück", viewModel::back, kind = OasisButtonKind.Secondary, fullWidth = false)
        if (ui.step < tour.steps.size) {
            AsyncOasisButton("Weiter", viewModel::next, modifier = Modifier.testTag("welcome.next"), fullWidth = false)
            OasisTextButton("Schritt überspringen", viewModel::skip, Modifier.align(Alignment.CenterVertically).testTag("welcome.skip"))
        } else {
            AsyncOasisButton("Los geht's", finish, modifier = Modifier.testTag("welcome.finish"), fullWidth = false)
        }
    }
}
