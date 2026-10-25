package de.velwebsolutions.oasis.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.chat.api.PublicUser
import de.velwebsolutions.oasis.chat.core.lifetimeChoices
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/** Screen "new" (partials/new.blade.php; iOS `NewConversationView`): add people by exact username, 1:1 or group, lifetime. */
@Composable
internal fun NewConversationScreen(username: String?) {
    val model = hiltViewModel<NewConversationViewModel, NewConversationViewModel.Factory>(
        key = "new-conversation-${username.orEmpty()}",
        creationCallback = { it.create(username) }
    )
    val state by model.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { model.applyPreselection() }
    OasisScreen(title = "Neuer Chat", message = state.status, onDismissMessage = model::dismissStatus) {
        NewConversationContent(
            state = state,
            canParticipate = model.canParticipate,
            onKind = model::setKind,
            onUsername = model::setUsername,
            onTitle = model::setTitle,
            onLifetime = model::setLifetimeDays,
            onAdd = model::addFromInput,
            onRemove = model::remove,
            onCreate = {
                // The new chat replaces this screen.
                model.create()?.let { id ->
                    navigator.back()
                    navigator.open(ChatRoute.Conversation(id.toInt()))
                }
            }
        )
    }
}

@Composable
internal fun NewConversationContent(
    state: NewConversationState,
    canParticipate: Boolean,
    onKind: (ConversationKind) -> Unit,
    onUsername: (String) -> Unit,
    onTitle: (String) -> Unit,
    onLifetime: (Int) -> Unit,
    onAdd: suspend () -> Unit,
    onRemove: (PublicUser) -> Unit,
    onCreate: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    ChatPage {
        PageHeader(title = "Neuer Chat")
        if (!canParticipate) {
            Banner("Dein Konto ist gerade nur zum Lesen. Mit einer Mitgliedschaft kannst du wieder schreiben.", kind = BannerKind.Info)
        }
        Panel {
            Text("Art", style = OasisType.SmallBold, color = OasisColors.Ink)
            Segmented(
                options = listOf(ConversationKind.Direct to "Zu zweit", ConversationKind.Group to "Gruppe (bis 50 Menschen)"),
                selected = state.kind,
                onSelect = onKind,
                modifier = Modifier.testTag("chat.new.kind")
            )
            OasisTextField(
                "Benutzername",
                state.username,
                onUsername,
                hint = "Genau wie im Profil, ohne @. Nur wer den Chat schon eingerichtet hat, kann hinzugefügt werden.",
                // "Fertig" adds the person, like iOS' submit.
                keyboardOptions = OasisKeyboard.Username.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { scope.launch { onAdd() } }),
                modifier = Modifier.testTag("chat.new.username")
            )
            AsyncOasisButton(
                "Hinzufügen",
                onAdd,
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                modifier = Modifier.testTag("chat.new.add")
            )
            if (state.selected.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.semantics {
                        isTraversalGroup = true
                        contentDescription = "Ausgewählt"
                    }.testTag("chat.new.selected")
                ) {
                    state.selected.forEach { user ->
                        Row(
                            modifier = Modifier.background(OasisColors.Dune, CircleShape).padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("@${user.username}", style = OasisType.BodyBold, color = OasisColors.Night)
                            IconButton(onClick = { onRemove(user) }, modifier = Modifier.defaultMinSize(44.dp, 44.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "@${user.username} entfernen", tint = OasisColors.Night)
                            }
                        }
                    }
                }
            }
            if (state.kind == ConversationKind.Group) {
                OasisTextField(
                    "Name der Gruppe (optional)",
                    state.title,
                    onTitle,
                    hint = "Der Name ist nicht verschlüsselt. Schreib nichts Vertrauliches hinein.",
                    modifier = Modifier.testTag("chat.new.title")
                )
            }
            Text("Nachrichten löschen sich nach", style = OasisType.SmallBold, color = OasisColors.Ink)
            Segmented(options = lifetimeChoices, selected = state.lifetimeDays, onSelect = onLifetime)
            state.error?.let { FieldError(it) }
            AsyncOasisButton(
                "Chat starten",
                onCreate,
                fullWidth = false,
                enabled = canParticipate,
                modifier = Modifier.testTag("chat.new.submit")
            )
        }
    }
}

/** A segmented choice (SwiftUI's segmented Picker) in the Oasis colours. */
@Composable
internal fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = OasisColors.Night,
                    activeContentColor = OasisColors.OnNight,
                    inactiveContainerColor = OasisColors.Field,
                    inactiveContentColor = OasisColors.Night,
                    activeBorderColor = OasisColors.Night,
                    inactiveBorderColor = OasisColors.FieldLine
                ),
                icon = {}
            ) {
                Text(label, style = OasisType.Small)
            }
        }
    }
}
