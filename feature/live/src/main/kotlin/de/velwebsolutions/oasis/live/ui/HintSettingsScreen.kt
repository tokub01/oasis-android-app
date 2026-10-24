package de.velwebsolutions.oasis.live.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.SettingToggle
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.live.api.HintSettings
import de.velwebsolutions.oasis.live.api.PushDevice
import de.velwebsolutions.oasis.live.push.PushAuthorization
import de.velwebsolutions.oasis.live.push.PushRegistrar
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the settings form can do; the screen connects it to the view model. */
internal class HintSettingsActions(
    val toggle: ((HintSettings) -> HintSettings) -> Unit,
    val editQuietHours: ((HintSettings) -> HintSettings) -> Unit,
    val toggleChat: (key: String, on: Boolean) -> Unit,
    val pause: suspend (days: Int) -> Unit,
    val resume: suspend () -> Unit,
    val remove: suspend (PushDevice) -> Unit,
    val togglePush: suspend () -> Unit,
    val openSystemSettings: () -> Unit,
    val back: () -> Unit
)

/**
 * Hinweise einstellen (/app/hinweise/einstellungen): which hints arrive, the daily e-mail, quiet hours, a pause, push
 * hints on this device and the member's other devices, and how new chat messages are announced. Every change is saved
 * at once, like in the privacy centre. "Oasis als App" (install the web app) has no place in the native app.
 */
@Composable
internal fun HintSettingsScreen(viewModel: HintSettingsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAuthorization() }
    // Android 13+ asks for the permission before push can be switched on; the answer then switches it on.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.permissionAnswered(granted)
        scope.launch { viewModel.togglePushOnThisDevice() }
    }
    // The time fields save after a short pause, not on every change.
    val settings = ui.settings
    LaunchedEffect(settings?.quietFrom, settings?.quietUntil) {
        if (settings != null && viewModel.quietHoursChanged) {
            delay(800)
            viewModel.save()
        }
    }
    OasisScreen(
        title = "Hinweise einstellen",
        message = ui.message,
        error = ui.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        LoadableView(ui.state, onRetry = viewModel::load) {
            HintSettingsContent(
                ui,
                HintSettingsActions(
                    toggle = viewModel::toggle,
                    editQuietHours = viewModel::edit,
                    toggleChat = viewModel::toggleChat,
                    pause = viewModel::pause,
                    resume = viewModel::resume,
                    remove = viewModel::remove,
                    togglePush = {
                        val needsPrompt = !ui.isOnForThisDevice && ui.authorization != PushAuthorization.Authorized &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        if (needsPrompt) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.togglePushOnThisDevice()
                    },
                    openSystemSettings = viewModel::openSystemSettings,
                    back = { navigator.back() }
                )
            )
        }
    }
}

/** The form itself, without view model (Robolectric tests). */
@Composable
internal fun HintSettingsContent(ui: HintSettingsUiState, actions: HintSettingsActions) {
    val settings = ui.settings ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader(
                "Hinweise einstellen",
                eyebrow = "Hinweise",
                lead = "Du entscheidest, was in deiner Hinweis-Liste landet und ob du eine E-Mail bekommst. Jede Änderung gilt sofort."
            )
            Switches(settings, ui, actions)
            PauseSection(settings, actions)
            PushSection(settings, ui, actions)
            if (settings.chat.isNotEmpty()) ChatSection(settings, ui, actions)
            SideCard("In deinem Tempo", tone = PanelTone.Dune) {
                Text("Die E-Mail-Zusammenfassung ist standardmäßig aus. Gelesene Hinweise bleiben bis zu 90 Tage in deiner Liste.")
                OasisTextButton("Zurück zu deinen Hinweisen", actions.back, Modifier.heightIn(min = 44.dp))
            }
        }
    }
}

@Composable
private fun Switches(settings: HintSettings, ui: HintSettingsUiState, actions: HintSettingsActions) {
    val enabled = !ui.isSaving
    Panel(spacing = 20) {
        Group("In deiner Hinweis-Liste") {
            settings.kinds.forEach { kind ->
                SettingToggle(
                    kind.label,
                    kind.enabled,
                    onCheckedChange = { on ->
                        actions.toggle { current ->
                            current.copy(
                                kinds = current.kinds.map {
                                    if (it.key ==
                                        kind.key
                                    ) {
                                        it.copy(enabled = on)
                                    } else {
                                        it
                                    }
                                }
                            )
                        }
                    },
                    modifier = Modifier.heightIn(min = 44.dp).testTag("kind.${kind.key}"),
                    enabled = enabled
                )
            }
        }
        Group("E-Mail") {
            SettingToggle(
                "Eine tägliche Zusammenfassung am Abend",
                settings.digest,
                onCheckedChange = { on -> actions.toggle { it.copy(digest = on) } },
                modifier = Modifier.testTag("digest"),
                detail =
                    "Ab 18 Uhr, außerhalb deiner Ruhezeit. Nur wenn es neue ungelesene Hinweise gibt. Die E-Mail enthält eine Summe und einen Link, keine Tropfen-Texte.",
                enabled = enabled
            )
        }
        Group("Ruhezeiten") {
            SettingToggle(
                "Jeden Tag eine Ruhezeit einhalten",
                settings.quietEnabled,
                onCheckedChange = { on -> actions.toggle { it.copy(quietEnabled = on) } },
                modifier = Modifier.testTag("quiet"),
                detail =
                    "In dieser Zeit (deutsche Zeit) kommen keine E-Mails und keine Push-Hinweise. Deine Hinweise sammeln sich in der App und warten auf dich. Wichtige Mitteilungen zu Konto, Sicherheit und Moderation kommen trotzdem.",
                enabled = enabled
            )
            AnimatedVisibility(settings.quietEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TimeField(
                        "Von",
                        settings.quietFrom,
                        ui.fieldErrors["quiet_from"],
                        "quiet.from",
                        Modifier.weight(1f),
                        enabled
                    ) { time -> actions.editQuietHours { it.copy(quietFrom = time) } }
                    TimeField(
                        "Bis",
                        settings.quietUntil,
                        ui.fieldErrors["quiet_until"],
                        "quiet.until",
                        Modifier.weight(1f),
                        enabled
                    ) { time -> actions.editQuietHours { it.copy(quietUntil = time) } }
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = OasisType.heading(), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** A time like "22:00" (German time), picked in the clock dialog; stored as the web's `H:i` text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    value: String,
    error: String?,
    tag: String,
    modifier: Modifier,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(OasisRadius.XS.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = OasisType.SmallBold, color = OasisColors.Ink)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(OasisColors.Field, shape)
                .border(if (error == null) 1.dp else 2.dp, if (error == null) OasisColors.FieldLine else OasisColors.SunText, shape)
                .clickable(enabled = enabled, role = Role.Button) { picking = true }
                .padding(horizontal = 14.dp)
                .semantics { contentDescription = "$label $value" }
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(value, style = OasisType.Body, color = OasisColors.Ink, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = OasisColors.Night)
        }
        if (error != null) FieldError(error)
    }
    if (picking) {
        val parts = value.split(":").mapNotNull { it.toIntOrNull() }
        val state =
            rememberTimePickerState(initialHour = parts.getOrElse(0) { 0 }, initialMinute = parts.getOrElse(1) { 0 }, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                OasisTextButton("Übernehmen", {
                    picking = false
                    onChange(timeText(state.hour, state.minute))
                })
            },
            dismissButton = { OasisTextButton("Abbrechen", { picking = false }) },
            title = { Text(label, style = OasisType.heading(), color = OasisColors.Night) },
            text = {
                TimePicker(
                    state,
                    colors = TimePickerDefaults.colors(
                        selectorColor = OasisColors.Night,
                        timeSelectorSelectedContainerColor = OasisColors.Dune,
                        timeSelectorSelectedContentColor = OasisColors.Night
                    )
                )
            },
            containerColor = OasisColors.Paper
        )
    }
}

/** "07:05" from hour and minute – always Latin digits, whatever the device's language (the web's `H:i`). */
internal fun timeText(hour: Int, minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PauseSection(settings: HintSettings, actions: HintSettingsActions) {
    SectionCard("Pause") {
        val until = settings.pausedUntil
        if (until != null) {
            Text(
                buildAnnotatedString {
                    append("Pause bis ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(GermanTime.format(until, "EEEE, d. MMMM, HH:mm") + " Uhr") }
                    append(". Bis dahin keine E-Mails, keine Push-Hinweise und kein Punkt für neue Hinweise.")
                },
                style = OasisType.Body,
                color = OasisColors.Ink
            )
            AsyncOasisButton(
                "Pause beenden",
                actions.resume,
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                modifier = Modifier.testTag("pause.end")
            )
        } else {
            Text(
                "Mal ganz abschalten: keine E-Mails, keine Push-Hinweise und kein Punkt für neue Hinweise. Danach geht alles von selbst weiter.",
                style = OasisType.Body,
                color = OasisColors.Ink2
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.pauseDays.forEach { days ->
                    AsyncOasisButton(
                        HintSettingsViewModel.pauseLabel(days),
                        { actions.pause(days) },
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false,
                        modifier = Modifier.testTag("pause.$days")
                    )
                }
            }
        }
    }
}

@Composable
private fun PushSection(settings: HintSettings, ui: HintSettingsUiState, actions: HintSettingsActions) {
    SectionCard("Push-Hinweise") {
        when {
            ui.pushAvailable -> {
                Note(
                    "Ein kurzer Hinweis, wenn es Neues gibt, zum Beispiel „Jonas Weber · Hat auf deinen Tropfen geantwortet.“ – nie mit dem Text eines Tropfens oder einer Nachricht, höchstens alle fünf Minuten und nie in deiner Ruhezeit oder Pause. Ob Namen auch auf dem Sperrbildschirm stehen, stellst du in den Benachrichtigungen deines Android-Geräts ein („Sensible Inhalte“). Welche Hinweise kommen, stellst du oben unter „In deiner Hinweis-Liste“ ein."
                )
                ThisDevice(ui, actions)
            }

            settings.push.available -> Note(
                "Push-Hinweise gibt es in der Android-App noch nicht. Deine Hinweise findest du jederzeit in der App.",
                Modifier.testTag("push.unavailable")
            )

            else -> Note(
                "Push-Hinweise sind auf Oasis noch nicht eingerichtet. Deine Hinweise findest du jederzeit in der App.",
                Modifier.testTag("push.unavailable")
            )
        }
        if (settings.push.devices.isNotEmpty()) {
            Text(
                "Geräte mit Push-Hinweisen",
                style = OasisType.BodyBold,
                color = OasisColors.Night,
                modifier = Modifier.padding(top = 8.dp)
            )
            settings.push.devices.forEach { device ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(OasisColors.Sand, RoundedCornerShape(OasisRadius.TILE.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildAnnotatedString {
                            append(device.device)
                            withStyle(SpanStyle(fontSize = OasisType.Small.fontSize, color = OasisColors.Muted)) {
                                append(" · seit ${GermanTime.day(device.since)}")
                            }
                        },
                        style = OasisType.Body,
                        color = OasisColors.Ink,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    AsyncOasisButton(
                        "Entfernen",
                        { actions.remove(device) },
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false,
                        modifier = Modifier.testTag("push.remove.${device.listId}")
                    )
                }
            }
        }
    }
}

@Composable
private fun ThisDevice(ui: HintSettingsUiState, actions: HintSettingsActions) {
    if (ui.authorization == PushAuthorization.Denied && !ui.isOnForThisDevice) {
        Note(PushRegistrar.DENIED_TEXT)
        OasisButton("Android-Einstellungen öffnen", actions.openSystemSettings, kind = OasisButtonKind.Secondary, fullWidth = false)
    } else {
        Note(
            if (ui.isOnForThisDevice) "Auf diesem Gerät sind Push-Hinweise eingeschaltet." else "Auf diesem Gerät sind Push-Hinweise aus.",
            Modifier.testTag("push.state")
        )
        AsyncOasisButton(
            if (ui.isOnForThisDevice) "Auf diesem Gerät ausschalten" else "Auf diesem Gerät einschalten",
            actions.togglePush,
            fullWidth = false,
            modifier = Modifier.testTag("push.toggle")
        )
    }
}

/** The chat switches of the privacy settings, with the privacy centre's texts. */
@Composable
private fun ChatSection(settings: HintSettings, ui: HintSettingsUiState, actions: HintSettingsActions) {
    SectionCard("Neue Chat-Nachrichten") {
        settings.chat.forEachIndexed { index, chat ->
            if (index > 0) HorizontalDivider(color = OasisColors.PaperLine)
            SettingToggle(
                chat.title,
                chat.enabled,
                onCheckedChange = { on -> actions.toggleChat(chat.key, on) },
                modifier = Modifier.testTag("hints.${chat.key}"),
                detail = chat.help,
                enabled = !ui.isSaving
            )
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = OasisType.Small, color = OasisColors.Ink2, modifier = modifier)
}
