package de.velwebsolutions.oasis.chat.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.chat.core.lifetimeChoices
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/**
 * Screen "details" (partials/details.blade.php; iOS `ConversationDetailsView`): notifications (mute), lifetime, group
 * name, safety numbers, members, leaving.
 */
@Composable
internal fun ConversationDetailsScreen(conversationId: Int) {
    val model = hiltViewModel<ConversationDetailsViewModel, ConversationDetailsViewModel.Factory>(
        key = "details-$conversationId",
        creationCallback = { it.create(conversationId.toString()) }
    )
    val state by model.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { model.load() }
    OasisScreen(
        title = "Details",
        message = state.status,
        error = state.error,
        onDismissMessage = model::dismissStatus,
        onDismissError = model::dismissError
    ) {
        ConversationDetailsContent(
            state = state,
            canParticipate = model.context.canParticipate,
            onMuteDuration = model::setMuteDuration,
            onMute = model::mute,
            onUnmute = model::unmute,
            onLifetime = model::setLifetimeDays,
            onSaveLifetime = model::saveLifetime,
            onTitle = model::setTitle,
            onSaveTitle = model::saveTitle,
            onUsername = model::setUsername,
            onAddMember = model::addMember,
            onRemove = { row -> scope.launch { model.remove(row) } },
            onLeave = {
                scope.launch {
                    // Back to the list: the details and the chat itself are gone.
                    if (model.leave()) navigator.popToRoot()
                }
            }
        )
    }
}

@Composable
internal fun ConversationDetailsContent(
    state: ConversationDetailsState,
    canParticipate: Boolean,
    onMuteDuration: (String) -> Unit,
    onMute: suspend () -> Unit,
    onUnmute: suspend () -> Unit,
    onLifetime: (Int) -> Unit,
    onSaveLifetime: suspend () -> Unit,
    onTitle: (String) -> Unit,
    onSaveTitle: suspend () -> Unit,
    onUsername: (String) -> Unit,
    onAddMember: suspend () -> Unit,
    onRemove: (MemberRow) -> Unit,
    onLeave: () -> Unit
) {
    var confirmLeave by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<MemberRow?>(null) }
    ChatPage {
        PageHeader(title = "Details")
        when {
            state.loadFailed != null -> Banner(state.loadFailed)

            state.conversation == null -> LoadingView()

            else -> {
                MuteSection(state, onMuteDuration, onMute, onUnmute)
                Panel {
                    Text("Neue Nachrichten löschen sich nach", style = OasisType.SmallBold, color = OasisColors.Ink)
                    Segmented(lifetimeChoices, state.lifetimeDays, onLifetime, enabled = state.isOwner)
                    Text(
                        "Gilt für neue Nachrichten. Ändern kann das nur, wer den Chat gestartet hat.",
                        style = OasisType.Small,
                        color = OasisColors.Muted
                    )
                    if (state.isOwner) {
                        AsyncOasisButton(
                            "Speichern",
                            onSaveLifetime,
                            fullWidth = false,
                            modifier = Modifier.testTag("chat.details.lifetime.save")
                        )
                    }
                }
                if (state.isGroup && state.isOwner) {
                    Panel {
                        OasisTextField(
                            "Name der Gruppe (optional)",
                            state.title,
                            onTitle,
                            hint = "Der Name ist nicht verschlüsselt. Schreib nichts Vertrauliches hinein.",
                            modifier = Modifier.testTag("chat.details.title")
                        )
                        AsyncOasisButton(
                            "Namen speichern",
                            onSaveTitle,
                            kind = OasisButtonKind.Secondary,
                            fullWidth = false,
                            modifier = Modifier.testTag("chat.details.title.save")
                        )
                    }
                }
                Panel {
                    Text("Sicherheitsnummern", style = OasisType.heading(), color = OasisColors.Night)
                    Text(
                        "Vergleicht die Nummer persönlich oder am Telefon. Stimmt sie bei beiden überein, liest niemand dazwischen mit. Ändert sie sich, hat jemand neue Schlüssel – frag nach, bevor du Vertrauliches schreibst.",
                        style = OasisType.Body,
                        color = OasisColors.Ink2
                    )
                    state.members.forEach { row ->
                        MemberRowView(row, onRemove = { pendingRemoval = row })
                        HorizontalDivider(color = OasisColors.PaperLine)
                    }
                }
                if (state.isGroup && state.isOwner) {
                    Panel {
                        OasisTextField(
                            "Mitglied hinzufügen",
                            state.username,
                            onUsername,
                            error = state.addError,
                            hint = "Neue Mitglieder sehen nur Nachrichten ab jetzt. Danach bekommt der Chat einen neuen Schlüssel.",
                            keyboardOptions = OasisKeyboard.Username
                        )
                        AsyncOasisButton("Hinzufügen", onAddMember, fullWidth = false, enabled = canParticipate)
                    }
                }
                if (state.canLeave) {
                    OasisButton(
                        "Chat verlassen",
                        { confirmLeave = true },
                        kind = OasisButtonKind.Destructive,
                        fullWidth = false,
                        modifier = Modifier.testTag("chat.details.leave")
                    )
                }
            }
        }
    }
    if (confirmLeave) ConfirmDialog("Chat verlassen? Er verschwindet aus deiner Liste.", "Verlassen", onLeave) { confirmLeave = false }
    pendingRemoval?.let { row ->
        ConfirmDialog("@${row.username} aus dem Chat entfernen? Der Chat bekommt einen neuen Schlüssel.", "Entfernen", { onRemove(row) }) {
            pendingRemoval = null
        }
    }
}

/** Muting only for oneself: while muted, "Wieder benachrichtigen" comes first and the picker changes the duration. */
@Composable
private fun MuteSection(
    state: ConversationDetailsState,
    onMuteDuration: (String) -> Unit,
    onMute: suspend () -> Unit,
    onUnmute: suspend () -> Unit
) {
    Panel {
        Text("Mitteilungen", style = OasisType.SmallBold, color = OasisColors.Ink)
        Text(state.muteState, style = OasisType.Body, color = OasisColors.Ink2, modifier = Modifier.testTag("chat.details.mute.state"))
        MenuPicker(
            label = "Wie lange stumm",
            options = ConversationDetailsViewModel.muteChoices,
            selected = state.muteDuration,
            onSelect = onMuteDuration,
            modifier = Modifier.testTag("chat.details.mute.duration")
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.isMuted) {
                AsyncOasisButton("Wieder benachrichtigen", onUnmute, fullWidth = false, modifier = Modifier.testTag("chat.details.unmute"))
                AsyncOasisButton(
                    "Dauer ändern",
                    onMute,
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false,
                    modifier = Modifier.testTag("chat.details.mute")
                )
            } else {
                AsyncOasisButton("Stummschalten", onMute, fullWidth = false, modifier = Modifier.testTag("chat.details.mute"))
            }
        }
        Text(
            "Nur für dich: Du bekommst für diesen Chat keine Push-Hinweise und E-Mails. Nachrichten kommen trotzdem an.",
            style = OasisType.Small,
            color = OasisColors.Muted
        )
    }
}

@Composable
private fun MemberRowView(row: MemberRow, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, style = OasisType.BodyBold, color = OasisColors.Night, modifier = Modifier.weight(1f))
            if (row.removable) {
                Spacer(Modifier.width(8.dp))
                SmallAction(
                    "Entfernen",
                    onRemove,
                    Modifier.semantics { contentDescription = "@${row.username} aus dem Chat entfernen" }
                )
            }
        }
        row.warning?.let { Text(it, style = OasisType.SmallBold, color = OasisColors.SunText) }
        row.safetyNumber?.let { number ->
            SelectionContainer {
                Text(
                    number,
                    style = OasisType.Digits,
                    color = OasisColors.Ink,
                    modifier = Modifier
                        .semantics { contentDescription = "Sicherheitsnummer mit ${row.username}: $number" }
                        .testTag("chat.details.safety.${row.username}")
                )
            }
        }
    }
}

/** A menu choice (SwiftUI's menu Picker): the current label with a chevron, the options in a dropdown. */
@Composable
internal fun <T> MenuPicker(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 44.dp)
                .border(1.dp, OasisColors.FieldLine, RoundedCornerShape(OasisRadius.XS.dp))
                .pressable({ expanded = true }, enabled)
                .semantics { contentDescription = label }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), style = OasisType.BodyBold, color = OasisColors.Night)
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = OasisColors.Night)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = OasisColors.Paper) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text, style = OasisType.Body, color = OasisColors.Ink) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}
