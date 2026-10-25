package de.velwebsolutions.oasis.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/**
 * Screen "list" (resources/views/app/chat/partials/list.blade.php; iOS `ConversationListView`): conversations newest
 * first, message requests, other devices asking to open the chat, "Neuer Chat", "Sperren", settings.
 */
@Composable
internal fun ConversationListScreen() {
    val model: ConversationListViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(Unit) { model.liveUpdates() }
    OasisScreen(
        title = "Nachrichten",
        actions = { ConversationListActions(onLock = model::lock, onSettings = { navigator.open(ChatRoute.Settings) }) },
        message = state.status,
        error = state.actionError,
        onDismissMessage = model::dismissStatus,
        onDismissError = model::dismissActionError
    ) {
        ConversationListContent(
            state = state,
            canParticipate = model.canParticipate,
            title = model::title,
            meta = model::meta,
            onRefresh = model::load,
            onLoadMore = model::loadMore,
            onOpen = { conversation -> navigator.open(ChatRoute.Conversation(conversation.id.toInt())) },
            onNew = { navigator.open(ChatRoute.NewConversation) },
            onApprove = model::approve,
            onReject = model::reject
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationListContent(
    state: ConversationListState,
    canParticipate: Boolean,
    title: (ChatConversation) -> String,
    meta: (ChatConversation) -> String,
    onRefresh: suspend () -> Unit,
    onLoadMore: suspend () -> Unit,
    onOpen: (ChatConversation) -> Unit,
    onNew: () -> Unit,
    onApprove: suspend (DeviceLinkRequest) -> Unit,
    onReject: suspend (DeviceLinkRequest) -> Unit
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                onRefresh()
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("chat.list"),
            // Room below the last panel for the floating "Neuer Chat" button.
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = if (canParticipate) 96.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { PageHeader(eyebrow = "Ende-zu-Ende verschlüsselt", title = "Nachrichten") }
            state.error?.let { error -> item { Banner(error) } }
            if (state.linkRequests.isNotEmpty()) item { LinkRequests(state.linkRequests, onApprove, onReject) }
            if (state.requests.isNotEmpty()) {
                item {
                    Panel(tone = PanelTone.Dune, modifier = Modifier.testTag("chat.requests")) {
                        Text("Anfragen", style = OasisType.heading(19), color = OasisColors.Night)
                        Text(
                            "Von Menschen, denen du nicht folgst und mit denen du nicht befreundet bist. Sie lösen keine Benachrichtigung aus, und niemand sieht, ob du sie gelesen hast. Antworten oder Annehmen macht daraus einen normalen Chat.",
                            style = OasisType.Small,
                            color = OasisColors.Ink2
                        )
                        state.requests.forEach { ConversationRow(it, title(it), meta(it), onOpen) }
                    }
                }
            }
            items(state.conversations, key = { it.id }) { conversation ->
                ConversationRow(conversation, title(conversation), meta(conversation), onOpen)
            }
            if (state.loaded && state.conversations.isEmpty()) {
                item {
                    DashedNote(highlighted = true, modifier = Modifier.testTag("chat.list.empty")) {
                        Text(
                            "Noch keine Unterhaltungen. Starte einen Chat mit jemandem, dessen Benutzernamen du kennst.",
                            style = OasisType.Body,
                            color = OasisColors.Ink2
                        )
                    }
                }
            } else if (!state.loaded && state.error == null) {
                item { Box(Modifier.fillMaxWidth().padding(vertical = 24.dp)) { LoadingView() } }
            }
            if (state.nextPage != null) {
                item { AsyncOasisButton("Ältere laden", onLoadMore, kind = OasisButtonKind.Secondary, fullWidth = false) }
            }
            item {
                Panel {
                    Text("Ende-zu-Ende verschlüsselt", style = OasisType.BodyBold, color = OasisColors.Night)
                    Bullets(
                        listOf(
                            "Nachrichten löschen sich nach 1, 7 oder 30 Tagen von selbst.",
                            "Vergleicht die Sicherheitsnummern in den Details, um vertauschte Schlüssel auszuschließen.",
                            "Meldest du eine Nachricht, sieht die Moderation nur diese eine."
                        )
                    )
                }
            }
        }
        // The main action of the list (iOS and the web: "Neuer Chat" beside the heading) as Android's floating button.
        if (canParticipate) {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                text = { Text("Neuer Chat", style = OasisType.Button) },
                containerColor = OasisColors.Night,
                contentColor = OasisColors.OnNight,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("chat.new")
            )
        }
    }
}

/**
 * The web's buttons beside the heading, in the top app bar: "Sperren" and the chat settings (below the list on the web).
 * "Neuer Chat" is the floating button of the list.
 */
@Composable
internal fun ConversationListActions(onLock: () -> Unit, onSettings: () -> Unit) {
    IconButton(onClick = onLock, modifier = Modifier.testTag("chat.lock")) {
        Icon(Icons.Outlined.Lock, contentDescription = "Sperren", tint = OasisColors.Night)
    }
    IconButton(onClick = onSettings, modifier = Modifier.testTag("chat.settings")) {
        Icon(Icons.Outlined.Settings, contentDescription = "Chat-Einstellungen", tint = OasisColors.Night)
    }
}

@Composable
private fun ConversationRow(conversation: ChatConversation, title: String, meta: String, onOpen: (ChatConversation) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
            .pressable({ onOpen(conversation) })
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("chat.conversation.${conversation.id}")
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = OasisType.BodyBold, color = OasisColors.Night, modifier = Modifier.weight(1f, fill = false))
            if (conversation.unread == true) Pill("Neu", tone = PillTone.Sun)
            if (conversation.muted == true) {
                Text(
                    "Stumm",
                    style = OasisType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = OasisColors.Muted,
                    modifier = Modifier.testTag("chat.list.muted")
                )
            }
            Box(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = OasisColors.Muted)
        }
        Text(meta, style = OasisType.Small, color = OasisColors.Muted)
    }
}

@Composable
private fun LinkRequests(
    entries: List<LinkRequestEntry>,
    onApprove: suspend (DeviceLinkRequest) -> Unit,
    onReject: suspend (DeviceLinkRequest) -> Unit
) {
    Panel(tone = PanelTone.Warning) {
        Text("Ein Gerät möchte sich verbinden", style = OasisType.heading(19), color = OasisColors.Night)
        Text(
            "Gib nur frei, wenn du das selbst gerade angefragt hast und dieselbe Zahl siehst. Das Gerät kann danach deine Chats lesen.",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
        entries.forEach { entry ->
            Column(
                modifier = Modifier.fillMaxWidth().background(OasisColors.Paper, RoundedCornerShape(OasisRadius.TILE.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "„${entry.request.label}“ möchte deinen Chat öffnen. Steht auf dem anderen Gerät dieselbe Prüfzahl?",
                    style = OasisType.Body,
                    color = OasisColors.Ink
                )
                Text(
                    entry.check,
                    style = OasisType.Digits.copy(fontSize = 24.sp, letterSpacing = 3.sp),
                    color = OasisColors.Night,
                    modifier = Modifier.testTag("chat.link.request.check")
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncOasisButton(
                        "Ja, gleiche Zahl – freigeben",
                        { onApprove(entry.request) },
                        fullWidth = false,
                        modifier = Modifier.testTag("chat.link.approve")
                    )
                    AsyncOasisButton(
                        "Ablehnen",
                        { onReject(entry.request) },
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false,
                        modifier = Modifier.testTag("chat.link.reject")
                    )
                }
            }
        }
    }
}
