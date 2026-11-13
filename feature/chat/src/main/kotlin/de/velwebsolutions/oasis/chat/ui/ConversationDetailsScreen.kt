package de.velwebsolutions.oasis.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.chat.core.ChatBackground
import de.velwebsolutions.oasis.chat.core.lifetimeChoices
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisCheckbox
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.ScreenChrome
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/**
 * Screen "details" (partials/details.blade.php; iOS `ConversationDetailsView`): notifications (mute), lifetime, group
 * name, safety numbers, members, leaving. As a pane beside the conversation (wide screens) it has [onClose] instead of
 * a way back.
 */
@Composable
internal fun ConversationDetailsScreen(conversationId: Int, onClose: (() -> Unit)? = null) {
    val model = hiltViewModel<ConversationDetailsViewModel, ConversationDetailsViewModel.Factory>(
        key = "details-$conversationId",
        creationCallback = { it.create(conversationId.toString()) }
    )
    val state by model.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        model.load()
        model.loadLook()
    }
    // „Foto wählen“: the system's photo picker; the chat stays open while it is in front.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { model.choosePhoto(uri) }
    }
    ConversationDetailsFrame(
        onClose = onClose,
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
            },
            look = LookActions(
                onBackground = model::chooseBackground,
                onForAll = model::setForAll,
                onPickPhoto = {
                    model.context.vault.openExternalScreen {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        true
                    }
                },
                onRemovePhoto = model::removePhoto,
                onEffects = model::setEffects
            )
        )
    }
}

/**
 * The frame of the details: a pushed screen with its way back, or as a pane beside the conversation (wide screens) a
 * bar with a close button ([onClose]) and no back arrow, since the conversation stays beside it.
 */
@Composable
internal fun ConversationDetailsFrame(
    onClose: (() -> Unit)?,
    message: String? = null,
    error: String? = null,
    onDismissMessage: () -> Unit = {},
    onDismissError: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val chrome = if (onClose != null) ScreenChrome() else LocalScreenChrome.current
    CompositionLocalProvider(LocalScreenChrome provides chrome) {
        OasisScreen(
            title = "Details",
            actions = {
                if (onClose != null) {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("chat.details.close")) {
                        Icon(Icons.Outlined.Close, contentDescription = "Details schließen", tint = OasisColors.Night)
                    }
                }
            },
            message = message,
            error = error,
            onDismissMessage = onDismissMessage,
            onDismissError = onDismissError,
            content = content
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
    onLeave: () -> Unit,
    look: LookActions = LookActions.None
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
                LookSection(state, look)
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
                        "Vergleicht die Nummer persönlich oder am Telefon. Stimmt sie bei beiden überein, liest niemand dazwischen mit. Ändert sie sich, nutzt jemand ein neues Gerät oder hat die Chats neu eingerichtet – frag nach, bevor du Vertrauliches schreibst.",
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
                            hint = "Neue Mitglieder sehen nur Nachrichten ab jetzt. Danach wird der Chat dafür neu gesichert.",
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
        ConfirmDialog("@${row.username} aus dem Chat entfernen? Der Chat wird dafür neu gesichert.", "Entfernen", { onRemove(row) }) {
            pendingRemoval = null
        }
    }
}

/** „Hintergrund“ and „Effekte abspielen“: choosing, the own photo, the switches. */
class LookActions(
    val onBackground: suspend (ChatBackground) -> Unit,
    val onForAll: (Boolean) -> Unit,
    val onPickPhoto: () -> Unit,
    val onRemovePhoto: suspend () -> Unit,
    val onEffects: suspend (Boolean) -> Unit
) {
    companion object {
        /** Screens without the section's actions (previews). */
        val None = LookActions({}, {}, {}, {}, {})
    }
}

/**
 * „Hintergrund“ (docs/oasis-chat-client.md, "Backgrounds and effects"): seven swatches with their names, „Foto wählen“ /
 * „Foto entfernen“, „Für alle Chats verwenden“ and „Effekte abspielen“ – only on this device, only for oneself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookSection(state: ConversationDetailsState, look: LookActions) {
    val scope = rememberCoroutineScope()
    Panel(modifier = Modifier.testTag("chat.look")) {
        Text("Hintergrund", style = OasisType.SmallBold, color = OasisColors.Ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChatBackground.entries.forEach { background ->
                val selected = state.look.background == background
                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .selectable(selected, role = Role.RadioButton) {
                            if (background == ChatBackground.Photo) look.onPickPhoto() else scope.launch { look.onBackground(background) }
                        }
                        .testTag("chat.look.${background.key}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BackgroundSwatch(
                        background,
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(OasisRadius.CHIP.dp))
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) OasisColors.Night else OasisColors.PaperLine,
                                RoundedCornerShape(OasisRadius.CHIP.dp)
                            )
                    )
                    Text(background.label, style = OasisType.Caption, color = OasisColors.Ink, textAlign = TextAlign.Center)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OasisButton(
                "Foto wählen",
                look.onPickPhoto,
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                modifier = Modifier.testTag("chat.look.pick")
            )
            if (state.look.background == ChatBackground.Photo) {
                SmallAction("Foto entfernen", { scope.launch { look.onRemovePhoto() } }, Modifier.testTag("chat.look.remove"))
            }
        }
        state.lookError?.let { Banner(it) }
        LookSwitch("Für alle Chats verwenden", state.forAll, "chat.look.forAll") { look.onForAll(it) }
        LookSwitch("Effekte abspielen", state.effectsOn, "chat.look.effects") { scope.launch { look.onEffects(it) } }
        Text(
            "Nur auf diesem Gerät und nur für dich. Andere sehen deinen Hintergrund nicht; ist „Bewegung reduzieren“ eingeschaltet, spielen keine Effekte.",
            style = OasisType.Small,
            color = OasisColors.Muted
        )
    }
}

@Composable
private fun LookSwitch(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OasisCheckbox(checked, onCheckedChange = null)
        Text(label, style = OasisType.Body, color = OasisColors.Ink)
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Avatar and name open the member's profile.
            Row(
                Modifier
                    .weight(1f)
                    .opensProfile(row.profile, profileOpener(row.profile))
                    .testTag("chat.details.profile.${row.username}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ChatAvatarView(row.avatar, Modifier.testTag("chat.details.avatar.${row.username}"), size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(row.label, style = OasisType.BodyBold, color = OasisColors.Night)
                    row.status?.takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = OasisType.Small.copy(fontWeight = FontWeight.SemiBold),
                            color = OasisColors.Ink2,
                            modifier = Modifier.testTag("chat.details.status.${row.username}")
                        )
                    }
                }
            }
            if (row.removable) {
                SmallAction(
                    "Entfernen",
                    onRemove,
                    Modifier.semantics { contentDescription = "@${row.username} aus dem Chat entfernen" }
                )
            }
        }
        row.warning?.let { Text(it, style = OasisType.SmallBold, color = OasisColors.Danger) }
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
