package de.velwebsolutions.oasis.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ChatSuggestion
import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisButton
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
 * first, message requests, other devices asking to open the chat, the backup row, "Neuer Chat", „Chats sperren“, settings;
 * without chats „Noch keine Chats“ with people to write first.
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
            rows = ConversationRowActions(
                preview = model::preview,
                activity = model::activity,
                canLeave = model::canLeave,
                onMarkRead = model::markRead,
                onToggleMute = model::toggleMute,
                onLeave = model::leave
            ),
            onRefresh = model::load,
            onLoadMore = model::loadMore,
            onOpen = { conversation -> navigator.open(ChatRoute.Conversation(conversation.id.toInt())) },
            onNew = { navigator.open(ChatRoute.NewConversation) },
            onApprove = model::approve,
            onReject = model::reject,
            myId = model.myId,
            backup = BackupRowActions(onOpen = { navigator.open(ChatRoute.Settings) }, onSnooze = model::snoozeBackupRow),
            onWrite = { person -> navigator.open(ChatRoute.NewConversationWith(person.username)) }
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
    rows: ConversationRowActions = ConversationRowActions.None,
    onRefresh: suspend () -> Unit,
    onLoadMore: suspend () -> Unit,
    onOpen: (ChatConversation) -> Unit,
    onNew: () -> Unit,
    onApprove: suspend (DeviceLinkRequest) -> Unit,
    onReject: suspend (DeviceLinkRequest) -> Unit,
    /** The signed-in member, left out of the conversation avatars. */
    myId: String = "",
    backup: BackupRowActions = BackupRowActions.None,
    /** „Schreiben“ beside a suggested person of the empty list. */
    onWrite: (ChatSuggestion) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    // The one row whose actions are open (a swipe left); a tap elsewhere or a swipe right closes it.
    var openRow by remember { mutableStateOf<String?>(null) }
    val gutter = LocalPaneInset.current ?: 16.dp
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
            // Page gutter 16 (a pane's own inset beside the conversation); rows 8 apart and 24 between the page head,
            // notices, the requests and the list (8 + 16).
            contentPadding = PaddingValues(start = gutter, top = 16.dp, end = gutter, bottom = if (canParticipate) 96.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Section { PageHeader(eyebrow = "Ende-zu-Ende verschlüsselt", title = "Nachrichten") } }
            state.error?.let { error -> item { Section { Banner(error) } } }
            if (state.backupRow) item { Section { BackupRow(backup) } }
            if (state.linkRequests.isNotEmpty()) item { Section { LinkRequests(state.linkRequests, onApprove, onReject) } }
            if (state.requests.isNotEmpty()) {
                item {
                    Panel(tone = PanelTone.Dune, modifier = Modifier.padding(bottom = SECTION_GAP).testTag("chat.requests")) {
                        Text("Anfragen", style = OasisType.heading(19), color = OasisColors.Night)
                        Text(
                            "Von Menschen, denen du nicht folgst und mit denen du nicht befreundet bist. Sie lösen keine Benachrichtigung aus, und niemand sieht, ob du sie gelesen hast. Antworten oder Annehmen macht daraus einen normalen Chat.",
                            style = OasisType.Small,
                            color = OasisColors.Ink2
                        )
                        state.requests.forEach { ConversationRow(it, myId, title(it), meta(it), rows, hasActions = false) { onOpen(it) } }
                    }
                }
            }
            items(state.conversations, key = { it.id }) { conversation ->
                SwipeRow(conversation, rows, openRow == conversation.id, onOpenChange = { open ->
                    openRow =
                        if (open) conversation.id else null
                }) {
                    // A tap closes open actions (of this or another row) instead of opening the chat.
                    ConversationRow(conversation, myId, title(conversation), meta(conversation), rows, hasActions = true) {
                        if (openRow != null) openRow = null else onOpen(conversation)
                    }
                }
            }
            if (state.empty) {
                item { EmptyChats(state.suggestions, canParticipate, onWrite, onNew) }
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
                            "Vergleicht die Sicherheitsnummern in den Details, dann weißt du, dass niemand dazwischen mitliest.",
                            "Meldest du eine Nachricht, sieht die Moderation nur diese eine."
                        )
                    )
                }
            }
        }
        // The main action of the list (iOS and the web: "Neuer Chat" beside the heading) as Android's floating button; the
        // empty list has its own large one.
        if (canParticipate && !state.empty) {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                text = { Text("Neuer Chat", style = OasisType.Button) },
                containerColor = OasisColors.Night,
                contentColor = OasisColors.OnNight,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(gutter)
                    .testTag("chat.new")
            )
        }
    }
}

/**
 * The web's buttons beside the heading, in the top app bar: „Chats sperren“ and the chat settings (below the list on the web).
 * "Neuer Chat" is the floating button of the list.
 */
@Composable
internal fun ConversationListActions(onLock: () -> Unit, onSettings: () -> Unit) {
    IconButton(onClick = onLock, modifier = Modifier.testTag("chat.lock")) {
        Icon(Icons.Outlined.Lock, contentDescription = "Chats sperren", tint = OasisColors.Night)
    }
    IconButton(onClick = onSettings, modifier = Modifier.testTag("chat.settings")) {
        Icon(Icons.Outlined.Settings, contentDescription = "Chat-Einstellungen", tint = OasisColors.Night)
    }
}

/**
 * One chat in the list (docs/oasis-chat-client.md, „Conversation list“): avatar 48, name and last activity, line 2 with
 * what this device decrypted (else „Verschlüsselte Nachricht“) and the markers; a „⋯“ menu and TalkBack actions do what
 * the swipes do. Requests have no actions ([hasActions] false).
 */
@Composable
private fun ConversationRow(
    conversation: ChatConversation,
    myId: String,
    title: String,
    meta: String,
    rows: ConversationRowActions,
    hasActions: Boolean,
    onTap: () -> Unit
) {
    val unread = conversation.unread == true
    val muted = conversation.muted == true
    var menu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val steps = if (!hasActions) {
        emptyList()
    } else {
        rowSteps(conversation, rows, onLeave = {
            confirmLeave = true
        }) { step -> scope.launch { step() } }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
            .pressable(onTap)
            .semantics {
                // The former meta line stays for screen readers („Gruppe · 3 Menschen · löscht nach 7 Tagen“).
                stateDescription = meta
                customActions = steps.map { (label, run) ->
                    CustomAccessibilityAction(label) {
                        run()
                        true
                    }
                }
            }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (hasActions) 4.dp else 16.dp)
            .testTag("chat.conversation.${conversation.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ConversationAvatar(conversation, myId, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = OasisType.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
                    color = OasisColors.Night,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (unread) Pill("Neu", tone = PillTone.Sun)
                Text(
                    rows.activity(conversation),
                    style = OasisType.Caption.copy(fontSize = 12.sp, fontWeight = if (unread) FontWeight.ExtraBold else FontWeight.Normal),
                    color = if (unread) OasisColors.SunText else OasisColors.Muted,
                    modifier = Modifier.testTag("chat.list.activity")
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    rows.preview(conversation),
                    style = OasisType.Small.copy(fontSize = 14.sp),
                    color = OasisColors.Ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("chat.list.preview")
                )
                conversation.endedBadge?.let { badge ->
                    Text(
                        badge,
                        style = OasisType.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = OasisColors.Ink2,
                        modifier = Modifier.background(OasisColors.Dune, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                if (muted) {
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        contentDescription = "stumm",
                        tint = OasisColors.Muted,
                        modifier = Modifier.size(16.dp).testTag("chat.list.muted")
                    )
                }
                if (unread) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(OasisColors.Sun, CircleShape)
                            .semantics { contentDescription = "ungelesen" }
                            .testTag("chat.list.unread")
                    )
                }
            }
        }
        if (hasActions) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(CHAT_TARGET).testTag("chat.list.menu.${conversation.id}")) {
                    Icon(Icons.Outlined.MoreHoriz, contentDescription = "Aktionen für $title", tint = OasisColors.Night)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = OasisColors.Paper) {
                    steps.forEach { (label, run) ->
                        DropdownMenuItem(
                            text = { Text(label, style = OasisType.Body, color = OasisColors.Ink) },
                            onClick = {
                                menu = false
                                run()
                            },
                            modifier = Modifier.testTag("chat.list.action.$label")
                        )
                    }
                }
            }
        }
    }
    if (confirmLeave) {
        ConfirmDialog(
            "Chat verlassen? Er verschwindet aus deiner Liste.",
            "Chat verlassen",
            { scope.launch { rows.onLeave(conversation) } },
            { confirmLeave = false }
        )
    }
}

/**
 * A list row with its swipes (docs/oasis-chat-client.md, „Gestures“): left past 64 it snaps open by 160 and shows
 * „Stummschalten“/„Ton an“ and „Chat verlassen“; right past 64 (at most 96) on spring marks an unread chat read. Only
 * clearly horizontal drags count; without motion the row does not follow the finger, the actions still happen.
 */
@Composable
private fun SwipeRow(
    conversation: ChatConversation,
    rows: ConversationRowActions,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    val unread = conversation.unread == true
    val moves = motionAllowed(LocalContext.current)
    val scope = rememberCoroutineScope()
    // Read only in layout and drawing (offset { }, graphicsLayer { }): a swipe composes the row once, not per pixel.
    val drag = remember { mutableFloatStateOf(0f) }
    val leftward by remember { derivedStateOf { drag.floatValue < 0f } }
    var confirmLeave by remember { mutableStateOf(false) }
    fun settle(target: Float) {
        scope.launch {
            if (moves) {
                animate(drag.floatValue, target, animationSpec = tween(SETTLE_MS)) { value, _ -> drag.floatValue = value }
            } else {
                drag.floatValue =
                    target
            }
        }
    }
    // A swipe starts where the row is: open by 160, or in place.
    val start = if (open) -ChatSwipe.ROW_ACTIONS else 0f
    val onMove: (SwipeTracker) -> Unit = { tracker -> drag.floatValue = ChatSwipe.rowOffset(start + tracker.dx, unread) }
    val onEnd: (SwipeTracker) -> Unit = { tracker ->
        val release = ChatSwipe.release(start + tracker.dx, unread)
        onOpenChange(release == RowRelease.Open)
        settle(if (release == RowRelease.Open) -ChatSwipe.ROW_ACTIONS else 0f)
        if (release == RowRelease.Read) scope.launch { rows.onMarkRead(conversation) }
    }
    LaunchedEffect(open) { settle(if (open) -ChatSwipe.ROW_ACTIONS else 0f) }
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(OasisRadius.S.dp))) {
        if (unread) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = if (drag.floatValue > 0f) 1f else 0f }
                    .background(OasisColors.Spring)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("Als gelesen markieren", style = OasisType.SmallBold, color = OasisColors.SpringInk)
            }
        }
        if (open || leftward) {
            Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                RowAction(
                    if (conversation.muted == true) "Ton an" else "Stummschalten",
                    OasisColors.Dune,
                    OasisColors.Night
                ) {
                    onOpenChange(false)
                    scope.launch { rows.onToggleMute(conversation) }
                }
                if (rows.canLeave(conversation)) {
                    RowAction("Chat verlassen", OasisColors.Danger, OasisColors.OnDanger) {
                        onOpenChange(false)
                        confirmLeave = true
                    }
                }
            }
        }
        Box(
            Modifier
                .offset { rowOffset(if (moves || open) drag.floatValue else 0f, density) }
                .pointerInput(conversation.id, unread, open) { swipes({ true }, onMove, onEnd) }
        ) {
            content()
        }
    }
    if (confirmLeave) {
        ConfirmDialog(
            "Chat verlassen? Er verschwindet aus deiner Liste.",
            "Chat verlassen",
            { scope.launch { rows.onLeave(conversation) } },
            { confirmLeave = false }
        )
    }
}

/** What a section of the list adds below itself to the rows' 8: 24 between sections. */
private val SECTION_GAP = 16.dp

/** A section of the list (page head, notices) with the room below it. */
@Composable
private fun Section(content: @Composable () -> Unit) {
    Box(Modifier.padding(bottom = SECTION_GAP)) { content() }
}

/** One of the two 80 wide buttons behind a row opened by a swipe left. */
@Composable
private fun RowAction(label: String, background: Color, foreground: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(background)
            .pressable(onClick)
            .padding(horizontal = 6.dp)
            .testTag("chat.list.swipe.$label"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = OasisType.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = foreground,
            textAlign = TextAlign.Center
        )
    }
}

/** The steps of a row in the order of its menu: „Als gelesen markieren“ while unread, „Stummschalten“/„Ton an“, leave. */
internal fun rowSteps(
    conversation: ChatConversation,
    rows: ConversationRowActions,
    onLeave: () -> Unit,
    launch: (suspend () -> Unit) -> Unit
): List<Pair<String, () -> Unit>> = buildList {
    if (conversation.unread == true) add("Als gelesen markieren" to { launch { rows.onMarkRead(conversation) } })
    add((if (conversation.muted == true) "Ton an" else "Stummschalten") to { launch { rows.onToggleMute(conversation) } })
    if (rows.canLeave(conversation)) add("Chat verlassen" to onLeave)
}

/** What the list's rows show and do besides opening a chat. */
class ConversationRowActions(
    val preview: (ChatConversation) -> String,
    val activity: (ChatConversation) -> String,
    val canLeave: (ChatConversation) -> Boolean,
    val onMarkRead: suspend (ChatConversation) -> Unit,
    val onToggleMute: suspend (ChatConversation) -> Unit,
    val onLeave: suspend (ChatConversation) -> Unit
) {
    companion object {
        /** Previews without a view model. */
        val None = ConversationRowActions({ ENCRYPTED_PREVIEW }, { "" }, { true }, {}, {}, {})
    }
}

/** What the backup row does: open the backup-passphrase setup, or „Später“. */
class BackupRowActions(val onOpen: () -> Unit, val onSnooze: suspend () -> Unit) {
    companion object {
        /** Lists without a view model (previews, tests of other parts). */
        val None = BackupRowActions({}, {})
    }
}

/**
 * The quiet row on top while the account has no backup passphrase (docs/oasis-chat-client.md, „C5 Setup with one
 * button“): a tap opens the backup-passphrase setup in the chat settings, „Später“ hides it for 14 days on this device.
 */
@Composable
private fun BackupRow(actions: BackupRowActions) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.S.dp))
            .padding(start = 4.dp, end = 8.dp)
            .testTag("chat.backupRow"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = CHAT_TARGET)
                .pressable(actions.onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(20.dp))
            Text("Sichere deine Chats, falls du dein Gerät verlierst", style = OasisType.SmallBold, color = OasisColors.Night)
        }
        SmallAction("Später", { scope.launch { actions.onSnooze() } }, Modifier.testTag("chat.backupRow.later"))
    }
}

/**
 * „Noch keine Chats“ (docs/oasis-chat-client.md, „C6 Empty chat list“): up to two people to write first, each with
 * „Schreiben“, and a large „Neuer Chat“ button.
 */
@Composable
private fun EmptyChats(people: List<ChatSuggestion>, canParticipate: Boolean, onWrite: (ChatSuggestion) -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("chat.list.empty"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Noch keine Chats", style = OasisType.heading(21), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
            Text("Schreib jemandem aus deinen Crowds.", style = OasisType.Body, color = OasisColors.Ink2)
        }
        people.forEach { person -> SuggestionRow(person, canParticipate, onWrite) }
        if (canParticipate) {
            OasisButton("Neuer Chat", onNew, modifier = Modifier.heightIn(min = 52.dp).testTag("chat.list.empty.new"))
        }
    }
}

/** A suggested person: avatar, name, @username, why, and „Schreiben“. */
@Composable
private fun SuggestionRow(person: ChatSuggestion, canParticipate: Boolean, onWrite: (ChatSuggestion) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("chat.suggestion.${person.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ChatAvatarView(person.avatar, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                person.displayName,
                style = OasisType.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
                color = OasisColors.Night,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("@${person.username}", style = OasisType.Small.copy(fontSize = 14.sp), color = OasisColors.Muted)
            Text(person.reason, style = OasisType.Small.copy(fontSize = 14.sp), color = OasisColors.Ink2)
        }
        if (canParticipate) {
            OasisButton(
                "Schreiben",
                { onWrite(person) },
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                modifier = Modifier.semantics { contentDescription = "${person.displayName} schreiben" }.testTag("chat.suggestion.write")
            )
        }
    }
}

@Composable
private fun LinkRequests(
    entries: List<LinkRequestEntry>,
    onApprove: suspend (DeviceLinkRequest) -> Unit,
    onReject: suspend (DeviceLinkRequest) -> Unit
) {
    Panel(tone = PanelTone.Warning) {
        Text("Dein neues Gerät möchte deine Chats öffnen", style = OasisType.heading(19), color = OasisColors.Night)
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
                        "Erlauben",
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
