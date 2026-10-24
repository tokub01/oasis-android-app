package de.velwebsolutions.oasis.account.ui.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.api.PrivacyCounts
import de.velwebsolutions.oasis.account.api.PrivacySection
import de.velwebsolutions.oasis.account.api.PrivacySettingItem
import de.velwebsolutions.oasis.account.api.PrivacyValue
import de.velwebsolutions.oasis.account.ui.components.AccountPage
import de.velwebsolutions.oasis.account.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.account.ui.components.EmptyLine
import de.velwebsolutions.oasis.account.ui.components.FlowChoices
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.SettingToggle
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/**
 * One privacy setting, drawn from what the API says about it (livewire/privacy/partials/setting): a switch for
 * `kind == "switch"`, otherwise the audience choices. A change shows at once and is saved right away; the controls wait
 * while it is saved.
 */
@Composable
internal fun PrivacySettingRow(setting: PrivacySettingItem, error: String?, saving: Boolean, onChange: (PrivacyValue) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (setting.isSwitch) {
            SettingToggle(
                setting.title,
                setting.value.flag,
                onCheckedChange = { onChange(PrivacyValue.Flag(it)) },
                modifier = Modifier.testTag("privacy.${setting.key}"),
                detail = setting.help,
                enabled = !setting.limited && !saving
            )
        } else {
            Text(setting.title, style = OasisType.BodyBold, color = OasisColors.Night, modifier = Modifier.semantics { heading() })
            Text(setting.help, style = OasisType.Small, color = OasisColors.Ink2)
            FlowChoices(setting.choices, setting.value.choice, tag = "privacy.${setting.key}", enabled = !saving) {
                onChange(PrivacyValue.Choice(it))
            }
        }
        if (setting.limited) {
            Text(
                "Unter 18 gilt hier eine strengere Grenze. Ab deinem 18. Geburtstag kannst du mehr freigeben.",
                style = OasisType.Caption,
                color = OasisColors.Ink2
            )
        }
        AnimatedVisibility(error != null) { FieldError(error.orEmpty()) }
    }
}

/** The settings of one section, separated by lines. */
@Composable
internal fun PrivacySectionCard(section: PrivacySection, ui: PrivacyUiState, onChange: (PrivacySettingItem, PrivacyValue) -> Unit) {
    SectionCard(section.title) {
        section.settings.forEachIndexed { index, setting ->
            if (index > 0) HorizontalDivider(color = OasisColors.PaperLine)
            PrivacySettingRow(setting, ui.errors[setting.key], ui.isSaving) { onChange(setting, it) }
        }
    }
}

/** Privatsphäre-Center (/app/privatsphaere). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivacyCenterScreen(viewModel: PrivacyViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    // The number of public Tropfen while „Tropfen begrenzen“ asks for confirmation.
    var limitCount by remember { mutableStateOf<Int?>(null) }
    OasisScreen(
        title = "Privatsphäre",
        message = ui.message,
        error = ui.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    viewModel.load()
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            PrivacyCenterContent(ui, onRetry = viewModel::load, onChange = viewModel::change, onLimit = {
                limitCount = it
            }, open = navigator::open)
        }
        limitCount?.let { count ->
            ConfirmDialog(
                "Alle $count öffentlichen Tropfen nur noch für Mitglieder sichtbar machen? Das lässt sich nicht auf einmal rückgängig machen.",
                "Tropfen begrenzen",
                onConfirm = { scope.launch { viewModel.limitPastPosts() } },
                onDismiss = { limitCount = null }
            )
        }
    }
}

@Composable
internal fun PrivacyCenterContent(
    ui: PrivacyUiState,
    onRetry: suspend () -> Unit,
    onChange: (PrivacySettingItem, PrivacyValue) -> Unit,
    onLimit: (Int) -> Unit,
    open: (AppRoute) -> Unit
) {
    AccountPage {
        PageHeader(
            "Deine Privatsphäre",
            eyebrow = "Privatsphäre",
            lead = "Du entscheidest, wer dich sieht und wer mit dir in Kontakt kommt. Jede Änderung gilt sofort."
        )
        LoadableView(ui.state, onRetry = onRetry) { centre ->
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(OasisColors.Night, RoundedCornerShape(OasisRadius.M.dp))
                        .pressable({ open(AccountRoute.PrivacyCheckup) })
                        .padding(20.dp)
                        .testTag("privacy.checkup"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Privatsphäre-Check", style = OasisType.heading(19), color = OasisColors.OnNight)
                        Text(
                            "In ${centre.sections.count { it.inCheckup }} kurzen Schritten durch die wichtigsten Einstellungen.",
                            style = OasisType.Small,
                            color = OasisColors.OnNight2
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = OasisColors.OnNight)
                }
                centre.sections.forEach { section -> PrivacySectionCard(section, ui, onChange) }
                MoreControls(centre.counts, onLimit, open)
            }
        }
    }
}

@Composable
private fun MoreControls(counts: PrivacyCounts, onLimit: (Int) -> Unit, open: (AppRoute) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Noch mehr Kontrolle", style = OasisType.heading(21), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
        val affected = when (counts.publicPosts) {
            0 -> "Du hast gerade keine öffentlichen Tropfen."
            1 -> "Betrifft 1 Tropfen."
            else -> "Betrifft ${counts.publicPosts} Tropfen."
        }
        SectionCard(
            "Frühere Tropfen begrenzen",
            detail =
                "Macht alle deine öffentlichen Tropfen auf einmal nur für Mitglieder sichtbar – Gäste und Suchmaschinen sehen sie dann nicht mehr. $affected"
        ) {
            if (counts.publicPosts > 0) {
                OasisButton("Tropfen begrenzen", { onLimit(counts.publicPosts) }, kind = OasisButtonKind.Secondary, fullWidth = false)
            }
        }
        val words = when (counts.hiddenWords) {
            0 -> "Noch keine Wörter."
            1 -> "1 Wort verborgen."
            else -> "${counts.hiddenWords} Wörter verborgen."
        }
        LinkCard(
            "Verborgene Wörter",
            "Tropfen, Antworten und Hinweise mit bestimmten Wörtern ausblenden. $words",
            "privacy.words"
        ) { open(AccountRoute.HiddenWords) }
        LinkCard(
            "Blockiert, stumm, eingeschränkt",
            "${counts.blocked} blockiert · ${counts.muted} stumm · ${counts.restricted} eingeschränkt. Einschränken startest du im Menü „⋯“ eines Tropfens oder auf einem Profil.",
            "privacy.safety"
        ) { open(AccountRoute.Safety) }
        LinkCard("Geräte und Anmeldungen", "Sehen, wo du angemeldet bist, und fremde Geräte abmelden.", "privacy.devices") {
            open(AccountRoute.Devices)
        }
        LinkCard(
            "Hinweise einstellen",
            "Welche Hinweise du bekommst und wie wir dir neue Chat-Nachrichten melden – per Push oder E-Mail.",
            "privacy.hints"
        ) { open(LiveRoute.Settings) }
        LinkCard("Deine Daten herunterladen und Konto löschen", "In den Einstellungen.", "privacy.settings") { open(AccountRoute.Settings) }
        Text("Blockieren wirkt immer, egal was hier eingestellt ist.", style = OasisType.Small, color = OasisColors.Muted)
    }
}

@Composable
private fun LinkCard(title: String, detail: String, tag: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp))
            .pressable(onClick)
            .padding(20.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = OasisType.BodyBold, color = OasisColors.Night, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = OasisColors.Night)
        }
        Text(detail, style = OasisType.Small, color = OasisColors.Ink2)
    }
}

/** Privatsphäre-Check (/app/privatsphaere/check): one section per step, then the summary. */
@Composable
internal fun PrivacyCheckupScreen(viewModel: PrivacyViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OasisScreen(
        title = "Privatsphäre-Check",
        message = ui.message,
        error = ui.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        AccountPage {
            LoadableView(ui.state, onRetry = viewModel::load) {
                PrivacyCheckupContent(ui, viewModel::change, viewModel::next, viewModel::back, navigator::open) {
                    if (!navigator.back()) navigator.dismissSheet()
                }
            }
        }
    }
}

@Composable
internal fun PrivacyCheckupContent(
    ui: PrivacyUiState,
    onChange: (PrivacySettingItem, PrivacyValue) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    open: (AppRoute) -> Unit,
    onDone: () -> Unit
) {
    val total = ui.checkupSections.size
    val section = ui.checkupSection
    // System back goes one step back first, like the „Zurück“ button; on the first step it leaves the check.
    BackHandler(enabled = ui.step > 1, onBack = onBack)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("Privatsphäre-Check")
            Text(
                section?.title ?: "Alles im Blick",
                style = OasisType.display(30),
                color = OasisColors.Night,
                modifier = Modifier.semantics { heading() }.testTag("checkup.title")
            )
            Text(
                if (section != null) {
                    "Schritt ${ui.step} von $total – jede Auswahl gilt sofort."
                } else {
                    "So sind deine Einstellungen jetzt. Ändern kannst du sie jederzeit."
                },
                style = OasisType.Body,
                color = OasisColors.Ink2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (index in 1..maxOf(total, 1)) {
                    Box(
                        Modifier.weight(1f).height(6.dp).background(
                            if (index <=
                                ui.step
                            ) {
                                OasisColors.Night
                            } else {
                                OasisColors.Dune
                            },
                            CircleShape
                        )
                    )
                }
            }
        }
        if (section != null) {
            PrivacySectionCard(section, ui, onChange)
        } else {
            SectionCard("Zusammenfassung") {
                ui.state.valueOrNull?.sections?.flatMap { it.settings }?.forEach { setting ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Text(setting.title, style = OasisType.Small, color = OasisColors.Ink2, modifier = Modifier.weight(1f))
                        Text(
                            setting.valueLabel,
                            style = OasisType.SmallBold,
                            color = OasisColors.Night,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                    }
                }
                HorizontalDivider(color = OasisColors.PaperLine)
                LinkText("Blockiert, stumm & eingeschränkt", { open(AccountRoute.Safety) })
                LinkText("Verborgene Wörter", { open(AccountRoute.HiddenWords) })
                LinkText("Geräte und Anmeldungen", { open(AccountRoute.Devices) })
                LinkText("Deine Daten herunterladen und Konto löschen", { open(AccountRoute.Settings) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.step > 1) OasisButton("Zurück", onBack, kind = OasisButtonKind.Secondary, fullWidth = false)
            if (section != null) {
                OasisButton(
                    if (ui.step == total) "Zusammenfassung" else "Weiter",
                    onNext,
                    modifier = Modifier.testTag("checkup.next"),
                    fullWidth = false
                )
            } else {
                OasisButton("Fertig", onDone, modifier = Modifier.testTag("checkup.done"), fullWidth = false)
            }
        }
    }
}

/** Verborgene Wörter (/app/privatsphaere/woerter). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HiddenWordsScreen(viewModel: HiddenWordsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    OasisScreen(
        title = "Verborgene Wörter",
        message = ui.message,
        error = ui.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        AccountPage {
            PageHeader(
                "Verborgene Wörter",
                eyebrow = "Privatsphäre",
                lead =
                    "Tropfen, Antworten und Hinweise mit diesen Wörtern bekommst du nicht mehr zu sehen. Groß- und Kleinschreibung spielt keine Rolle."
            )
            LoadableView(ui.state, onRetry = viewModel::load) { words ->
                SectionCard("Deine Wörter (${words.words.size} von ${words.max})") {
                    OasisTextField(
                        "Wort oder Ausdruck hinzufügen",
                        ui.word,
                        viewModel::setWord,
                        modifier = Modifier.testTag("words.field"),
                        error = ui.wordError,
                        hint = "2 bis 40 Zeichen. Auch Teile von Wörtern werden erkannt: „spoiler“ verbirgt auch „Spoileralarm“.",
                        keyboardOptions = OasisKeyboard.NoCapitalization
                    )
                    AsyncOasisButton("Hinzufügen", viewModel::add, modifier = Modifier.testTag("words.add"), fullWidth = false)
                    if (words.words.isEmpty()) {
                        EmptyLine("Du verbirgst noch keine Wörter.")
                    } else {
                        HorizontalDivider(color = OasisColors.PaperLine)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            words.words.forEach { word ->
                                Row(
                                    Modifier
                                        .background(OasisColors.Field, CircleShape)
                                        .border(1.dp, OasisColors.DuneLine, CircleShape)
                                        .padding(start = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(word, style = OasisType.Small, color = OasisColors.Ink)
                                    IconButton(onClick = { scope.launch { viewModel.remove(word) } }, modifier = Modifier.size(44.dp)) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = "„$word“ nicht mehr verbergen",
                                            tint = OasisColors.Muted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            SideCard("Was passiert genau?", tone = PanelTone.Dune) {
                Bullets(
                    listOf(
                        "In deiner Quelle, in Oasen, Suche und Antworten fehlen Tropfen mit diesen Wörtern – deine eigenen bleiben.",
                        "Antworten mit diesen Wörtern unter deinen Tropfen sieht nur noch, wer sie geschrieben hat.",
                        "Du bekommst keine Hinweise zu Tropfen mit diesen Wörtern.",
                        "Niemand erfährt, welche Wörter du verbirgst."
                    )
                )
            }
        }
    }
}
