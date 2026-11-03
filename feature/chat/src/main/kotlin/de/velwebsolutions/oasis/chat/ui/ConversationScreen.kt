package de.velwebsolutions.oasis.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.chat.api.ChatAvatar
import de.velwebsolutions.oasis.chat.core.ChatKeyring
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.formatDateTime
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screen "conversation" (partials/conversation.blade.php; iOS `ConversationView`): notices, request banner, safety
 * banner, messages with "Melden", and the composer with counter, tone hint and the sensitive-data warning.
 */
@Composable
internal fun ConversationScreen(conversationId: Int) {
    val model = hiltViewModel<ConversationViewModel, ConversationViewModel.Factory>(
        key = "conversation-$conversationId",
        creationCallback = { it.create(conversationId.toString()) }
    )
    val state by model.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ReportModel?>(null) }

    LaunchedEffect(Unit) { model.open() }
    DisposableEffect(Unit) { onDispose { model.close() } }
    LaunchedEffect(state.closed) { if (state.closed) navigator.back() }
    // Messages count as read only while the chat is on screen (not behind a picker or another app, not with the screen off).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.setVisible(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.setVisible(false) }
    // The chat in front again ends a screen it opened. The photo picker and "Teilen" only cover it (no ON_START on the way
    // back): without this, the next trip to the background would wait for them instead of locking at once.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.context.vault.cameToForeground() }

    val chrome = LocalScreenChrome.current
    OasisScreen(
        title = state.title,
        topBar = {
            ChatHeaderBar(
                state.conversation,
                model.context.userId,
                state.title,
                state.headerStatus,
                onBack = chrome.onBack,
                onDetails = { navigator.open(ChatRoute.Details(conversationId)) }
            )
        },
        message = state.status,
        error = state.actionError,
        onDismissMessage = model::dismissStatus,
        onDismissError = model::dismissActionError
    ) {
        ConversationContent(
            state = state,
            myId = model.context.userId,
            senderName = model::senderName,
            senderAvatar = model::senderAvatar,
            senderProfile = model::senderProfile,
            onLoadOlder = model::loadOlder,
            onDraftChange = model::setDraft,
            onSend = model::send,
            onReport = { report = model.reportModel(it) },
            onAccept = model::acceptRequest,
            onDecline = { block -> scope.launch { if (model.declineRequest(block)) navigator.back() } },
            onBlockFlagged = { senderId -> scope.launch { if (model.blockAndLeave(senderId)) navigator.back() } },
            onDismissFlagged = model::dismissFlagged,
            onNoticeSpoken = model::noticeSpoken,
            attachments = model.attachmentActions(),
            effects = EffectActions(onChoose = model::chooseEffect, onClear = model::clearEffect, onDone = model::effectDone),
            reactions = ReactionActions(
                onReact = model::react,
                onReply = model::startReply,
                onCancelReply = model::cancelReply,
                onQuote = model::quoteTarget,
                onPin = model::togglePin,
                onVote = model::vote,
                onClosePoll = model::closePoll
            ),
            polls = PollFormActions(
                onOpen = { model.showPollForm(PollForm()) },
                onEdit = model::editPoll,
                onCancel = { model.showPollForm(null) },
                onSend = model::sendPoll
            )
        )
    }
    report?.let { ReportSheet(it, onDismiss = { report = null }) }
}

/** The view model's attachment steps for the screen. */
internal fun ConversationViewModel.attachmentActions() = AttachmentActions(
    onPicked = ::addPicked,
    onRemove = ::removeDraft,
    onViewOnce = ::setViewOnce,
    recording = RecordingActions(onStart = ::startRecording, onFinish = ::finishRecording, onCancel = ::cancelRecording),
    onLoad = ::loadAttachment,
    onAnswer = ::answer,
    onOpenViewOnce = ::openViewOnce,
    onCloseViewOnce = ::closeViewOnce,
    onCancelViewOnce = ::cancelViewOnce,
    onExternalScreen = context.vault::openExternalScreen,
    text = TextActions(onTranscribe = ::transcribe, onHide = ::hideTranscript)
)

@Composable
internal fun ConversationContent(
    state: ConversationState,
    myId: String,
    senderName: (String) -> String,
    onLoadOlder: suspend () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: suspend () -> Unit,
    onReport: (ChatMessageItem) -> Unit,
    onAccept: suspend () -> Unit,
    onDecline: (block: Boolean) -> Unit,
    onBlockFlagged: (String) -> Unit,
    onDismissFlagged: () -> Unit,
    onNoticeSpoken: () -> Unit,
    attachments: AttachmentActions,
    /** The avatar beside someone else's messages. */
    senderAvatar: (String) -> ChatAvatar = { ChatAvatar.Unknown },
    senderProfile: (String) -> ProfileLink? = { null },
    effects: EffectActions = EffectActions.None,
    reactions: ReactionActions = ReactionActions.None,
    polls: PollFormActions = PollFormActions.None
) {
    val look = LookStyle.of(state.look)
    CompositionLocalProvider(LocalChatLook provides look) {
        Box(Modifier.fillMaxSize()) {
            ConversationColumn(
                state, myId, senderName, onLoadOlder, onDraftChange, onSend, onReport, onAccept, onDecline, onBlockFlagged,
                onDismissFlagged, onNoticeSpoken, attachments, senderAvatar, senderProfile, effects, reactions, polls
            )
            // Over everything, never in the way of a tap.
            state.playing?.let { EffectOverlay(it, effects.onDone) }
        }
    }
}

/** The actions of the send effects: choosing one, „✕“ on the chip, and the end of a playing. */
class EffectActions(val onChoose: suspend (String) -> Unit, val onClear: () -> Unit, val onDone: () -> Unit) {
    companion object {
        /** Screens without effects (previews). */
        val None = EffectActions({}, {}, {})
    }
}

/** One row of the message list: a day pill, the „Neue Nachrichten“ divider or a message with its gap above. */
@Composable
@Suppress("LongParameterList")
private fun TimelineRowView(
    row: TimelineRow,
    state: ConversationState,
    myId: String,
    now: Instant,
    senderName: (String) -> String,
    onReport: (ChatMessageItem) -> Unit,
    attachments: AttachmentActions,
    senderAvatar: (String) -> ChatAvatar,
    senderProfile: (String) -> ProfileLink?,
    reactions: ReactionActions,
    onJump: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    when (row) {
        is TimelineRow.Day -> DayPill(row.label)

        is TimelineRow.Unread -> UnreadDivider()

        // A message (the last kind of row).
        else -> {
            row as TimelineRow.Message
            val item = row.item
            val mine = item.senderId == myId
            Box(Modifier.padding(top = rowGap(row.run))) {
                MessageBubble(
                    item,
                    mine = mine,
                    senderName = senderName(item.senderId),
                    onReport = { onReport(item) },
                    // Beside someone else's messages: the avatar at the last bubble of each of their runs.
                    avatar = if (mine) null else senderAvatar(item.senderId),
                    profile = if (mine) null else senderProfile(item.senderId),
                    run = row.run,
                    showName = !mine && state.conversation?.isGroup == true &&
                        (row.run == ChatLayout.Run.First || row.run == ChatLayout.Run.Single),
                    time = row.sent?.let(ChatLayout::clockTime),
                    info = ChatLayout.infoLine(row.sent, item.expiresAt, now),
                    extras = BubbleExtras(
                        quote = state.quotes[item.signature],
                        chips = state.reactions[item.signature].orEmpty(),
                        own = state.ownReaction(item.signature),
                        canAct = state.canAct(item),
                        onReact = { emoji -> scope.launch { reactions.onReact(item, emoji) } },
                        onReply = { reactions.onReply(item) },
                        onQuote = { to ->
                            reactions.onQuote(to)?.let(onJump)
                        },
                        pinLabel = state.pinLabel(item.signature),
                        onPin = { scope.launch { reactions.onPin(item) } },
                        poll = state.polls[item.signature],
                        pollActions = PollActions(
                            onVote = { index -> scope.launch { reactions.onVote(item, index) } },
                            onClose = { scope.launch { reactions.onClosePoll(item) } }
                        )
                    )
                ) {
                    MessageAttachments(
                        item,
                        mine,
                        senderName(item.senderId),
                        state.attachmentViews,
                        attachments,
                        TranscriptionView(state.canTranscribe, state.transcripts)
                    )
                }
            }
        }
    }
}

/** How far from the newest message „Nach unten“ shows. */
private val SCROLL_DOWN_DISTANCE = 160.dp

/** The floating composer's corner radius. */
private val COMPOSER_RADIUS = 28.dp

@Composable
@Suppress("LongParameterList")
private fun ConversationColumn(
    state: ConversationState,
    myId: String,
    senderName: (String) -> String,
    onLoadOlder: suspend () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: suspend () -> Unit,
    onReport: (ChatMessageItem) -> Unit,
    onAccept: suspend () -> Unit,
    onDecline: (block: Boolean) -> Unit,
    onBlockFlagged: (String) -> Unit,
    onDismissFlagged: () -> Unit,
    onNoticeSpoken: () -> Unit,
    attachments: AttachmentActions,
    senderAvatar: (String) -> ChatAvatar,
    senderProfile: (String) -> ProfileLink?,
    effects: EffectActions,
    reactions: ReactionActions,
    polls: PollFormActions
) {
    val look = LocalChatLook.current
    val now = Instant.now()
    val photo = rememberLookPhoto(state.look.photo)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Reactions are no bubbles: they show as chips under their target.
    val items = state.bubbles
    var confirmDecline by remember { mutableStateOf(false) }
    var confirmBlockRequest by remember { mutableStateOf(false) }
    var confirmBlockFlagged by remember { mutableStateOf(false) }

    LaunchedEffect(state.spokenNotice) {
        if (state.spokenNotice != null) {
            delay(1_500)
            onNoticeSpoken()
        }
    }

    // „Nach unten“: shown while scrolled up more than 160 from the newest message; counts others' messages that came meanwhile.
    val density = LocalDensity.current
    val away = with(density) { SCROLL_DOWN_DISTANCE.toPx() }
    val awayFromBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            awayFromBottom(
                info.visibleItemsInfo.map {
                    it.index to it.offset + it.size
                },
                info.totalItemsCount,
                info.viewportEndOffset,
                away
            )
        }
    }
    var arrived by remember { mutableIntStateOf(0) }
    // The newest message stays in view: on opening (without motion), on sending and on receiving – unless the member
    // scrolled up to read: then others' new messages are counted on „Nach unten“. Older messages loaded above keep the
    // last id, so they appear in place.
    var shownLastId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(items.lastOrNull()?.id) {
        val last = items.lastOrNull() ?: return@LaunchedEffect
        val bottom = listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
        when (newest(first = shownLastId == null, away = awayFromBottom, fromOthers = last.senderId != myId)) {
            Newest.Jump -> listState.scrollToItem(bottom)
            Newest.Count -> arrived += 1
            Newest.Follow -> listState.animateScrollToItem(bottom)
        }
        shownLastId = last.id
    }
    LaunchedEffect(awayFromBottom) { if (!awayFromBottom) arrived = 0 }
    var composerHeight by remember { mutableIntStateOf(0) }
    val composerSpace = with(density) { composerHeight.toDp() } + 24.dp
    // The composer grew (measured, more lines, a reply bar): at the newest message, the list keeps it in view above it.
    LaunchedEffect(composerHeight) {
        val info = listState.layoutInfo
        if (atNewest(info.visibleItemsInfo.map { it.index }, info.totalItemsCount)) listState.scrollToItem(info.totalItemsCount - 1)
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            // The personal background fills the whole message area, also under the floating composer.
            modifier = Modifier.fillMaxSize().chatBackground(state.look.background, photo).testTag("chat.messages"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = composerSpace)
        ) {
            items(state.notices) { Box(Modifier.padding(top = 12.dp)) { Banner(it, kind = BannerKind.Info) } }
            state.pinBar?.let { pin ->
                item {
                    Box(Modifier.padding(top = 12.dp)) {
                        PinBarView(pin) {
                            reactions.onQuote(pin.to)?.let { target ->
                                scope.launch { listState.animateScrollToItem(state.leadingRows + target) }
                            }
                        }
                    }
                }
            }
            state.requestTitle?.let { title ->
                item {
                    Panel(tone = PanelTone.Dune, modifier = Modifier.padding(top = 12.dp).testTag("chat.request.banner")) {
                        Text(title, style = OasisType.BodyBold, color = OasisColors.Night)
                        Text(
                            "Du folgst dieser Person nicht und ihr seid nicht befreundet. Solange du die Anfrage nicht annimmst, bekommst du dazu keine Benachrichtigung, und sie sieht nicht, ob du ihre Nachricht gelesen hast. Antworten nimmt die Anfrage an.",
                            style = OasisType.Small,
                            color = OasisColors.Ink2
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AsyncOasisButton("Annehmen", onAccept, fullWidth = false, modifier = Modifier.testTag("chat.request.accept"))
                            OasisButton(
                                "Ablehnen und löschen",
                                { confirmDecline = true },
                                kind = OasisButtonKind.Secondary,
                                fullWidth = false,
                                modifier = Modifier.testTag("chat.request.decline")
                            )
                            OasisButton("Blockieren", { confirmBlockRequest = true }, kind = OasisButtonKind.Secondary, fullWidth = false)
                        }
                    }
                }
            }
            state.flagged?.let { flagged ->
                item {
                    Panel(tone = PanelTone.Warning, modifier = Modifier.padding(top = 12.dp).testTag("chat.safety.banner")) {
                        Text(flagged.title, style = OasisType.BodyBold, color = OasisColors.Night)
                        Text(flagged.text, style = OasisType.Small, color = OasisColors.Ink2)
                        OasisButton("Blockieren und Chat löschen", { confirmBlockFlagged = true }, fullWidth = false)
                        OasisButton("Nachricht melden", { onReport(flagged.item) }, kind = OasisButtonKind.Secondary, fullWidth = false)
                        SmallAction("Ist in Ordnung", onDismissFlagged, underline = true)
                        Text(
                            "Diese Prüfung läuft nur auf deinem Gerät. Oasis liest deine Chats nicht; gemeldet wird nur, was du meldest.",
                            style = OasisType.Caption,
                            color = OasisColors.Muted
                        )
                    }
                }
            }
            if (state.nextBeforeId != null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                        AsyncOasisButton(
                            "Ältere Nachrichten laden",
                            onLoadOlder,
                            kind = OasisButtonKind.Secondary,
                            fullWidth = false,
                            modifier = Modifier.lookPill(look)
                        )
                    }
                }
            }
            when {
                state.loadFailed != null -> item { Box(Modifier.padding(top = 12.dp)) { Banner(state.loadFailed) } }

                state.conversation == null -> item { Box(Modifier.fillMaxWidth().padding(vertical = 24.dp)) { LoadingView() } }

                items.isEmpty() -> item {
                    Text(
                        "Noch keine Nachrichten. Schreib die erste.",
                        style = OasisType.Body,
                        color = OasisColors.Ink2,
                        modifier = Modifier.padding(top = 12.dp).lookPill(look).testTag("chat.messages.empty")
                    )
                }
            }
            items(state.shownRows, key = { it.key }) { row ->
                TimelineRowView(
                    row, state, myId, now, senderName, onReport, attachments, senderAvatar, senderProfile, reactions,
                    onJump = { target -> scope.launch { listState.animateScrollToItem(state.leadingRows + target) } }
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .onSizeChanged { composerHeight = it.height }
        ) {
            if (awayFromBottom && items.isNotEmpty()) {
                ScrollDownButton(
                    arrived,
                    onClick = {
                        arrived = 0
                        scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                    },
                    modifier = Modifier.align(Alignment.End).padding(bottom = 12.dp)
                )
            }
            if (state.untrustedOwner) {
                // Nothing can be sealed for this chat (ChatKeyring.assertSealable): the reason stands where the composer was.
                Banner(
                    ChatKeyring.UNTRUSTED_OWNER,
                    Modifier
                        .shadow(8.dp, RoundedCornerShape(COMPOSER_RADIUS))
                        .background(OasisColors.Paper, RoundedCornerShape(COMPOSER_RADIUS))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("chat.composer.untrustedOwner")
                )
            } else {
                ConversationComposer(state, onDraftChange, onSend, attachments, effects, reactions.onCancelReply, polls)
            }
        }
        state.viewOnce?.let { ViewOnceViewer(it, attachments, onReport) }
        state.spokenNotice?.let { notice ->
            Box(
                Modifier.size(1.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = notice
                }
            )
        }
    }

    if (confirmBlockRequest) {
        ConfirmDialog("Diese Person blockieren und die Anfrage löschen?", "Blockieren", {
            onDecline(true)
        }, { confirmBlockRequest = false })
    }
    if (confirmDecline) {
        ConfirmDialog("Anfrage löschen? Die Person erfährt davon nichts.", "Löschen", { onDecline(false) }, { confirmDecline = false })
    }
    val flagged = state.flagged
    if (confirmBlockFlagged && flagged != null) {
        ConfirmDialog(
            "${senderName(flagged.item.senderId)} blockieren und diesen Chat löschen?",
            "Blockieren",
            { onBlockFlagged(flagged.item.senderId) },
            { confirmBlockFlagged = false }
        )
    }
}

/**
 * The composer with counter, tone hint and the sensitive-data warning; photos and videos from the photo picker, voice
 * messages with the microphone (asked for with an explanation first).
 */
@Composable
private fun ConversationComposer(
    state: ConversationState,
    onDraftChange: (String) -> Unit,
    onSend: suspend () -> Unit,
    attachments: AttachmentActions,
    effects: EffectActions,
    onCancelReply: () -> Unit,
    polls: PollFormActions
) {
    var effectMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FIELD_RADIUS)
    val focus = remember { FocusRequester() }
    // „Antworten“ (also by swiping a message) puts the cursor into the field.
    LaunchedEffect(state.replyBar != null) { if (state.replyBar != null) runCatching { focus.requestFocus() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var explainMicrophone by remember { mutableStateOf(false) }
    var microphoneDenied by remember { mutableStateOf(false) }
    // The pickers are screens of the system: opened through onExternalScreen, the chat stays open (and with it the draft)
    // while they are in front.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { attachments.onPicked(uri, false) }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { attachments.onPicked(uri, true) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneDenied = !granted
        if (granted) attachments.recording.onStart()
    }
    // A floating rounded bar over the messages (radius 28, paper, shadow-float, padding 6).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(COMPOSER_RADIUS))
            .background(OasisColors.Paper, RoundedCornerShape(COMPOSER_RADIUS))
            .padding(6.dp)
            .animateContentSize()
            .testTag("chat.composer"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedVisibility(state.hint != null) {
            Text(
                state.hint.orEmpty(),
                style = OasisType.Small,
                color = OasisColors.Ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.CHIP.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("chat.composer.hint")
            )
        }
        AnimatedVisibility(state.composerError != null) { FieldError(state.composerError.orEmpty()) }
        if (microphoneDenied) {
            Text(
                "Ohne Zugriff aufs Mikrofon geht keine Sprachnachricht. Du kannst ihn in den Einstellungen deines Geräts erlauben.",
                style = OasisType.Small,
                color = OasisColors.SunText,
                modifier = Modifier.testTag("chat.composer.microphoneDenied")
            )
        }
        state.pollForm?.let { PollFormView(it, polls) }
        state.replyBar?.let { ReplyBarView(it, onCancelReply) }
        DraftAttachments(state, attachments)
        state.effectChip?.let { chip -> EffectChip(chip, effects.onClear) }
        val seconds = state.recordingSeconds
        if (seconds != null) {
            RecordingBar(seconds, attachments)
        } else {
            // „+“ with the attachment actions, the field across the remaining width, and one round button: the
            // microphone while nothing is written or attached, „Senden“ as soon as something is (like iOS and the web).
            Box {
                // The effect menu opens at the round button, from „Senden“ or from „+“ (also with nothing written yet).
                Box(Modifier.align(Alignment.BottomEnd)) {
                    EffectMenu(effectMenu, onDismiss = { effectMenu = false }, onChoose = effects.onChoose)
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttachMenu(
                        enabled = state.canAttach,
                        onMedia = {
                            attachments.onExternalScreen {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                true
                            }
                        },
                        onFile = {
                            attachments.onExternalScreen {
                                documents.launch(arrayOf("*/*"))
                                true
                            }
                        },
                        onEffect = { effectMenu = true },
                        onPoll = polls.onOpen
                    )
                    ComposerField(state, onDraftChange, shape, focus)
                    if (state.showsSend) {
                        // Holding „Senden“ opens the effect menu.
                        SendButton(state.canSubmit, onSend, onLongPress = { effectMenu = true })
                    } else {
                        RoundAction(
                            Icons.Outlined.Mic,
                            "Sprachnachricht aufnehmen",
                            enabled = state.canAttach,
                            filled = true,
                            modifier = Modifier.testTag("chat.composer.record")
                        ) {
                            val granted =
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                            if (granted) attachments.recording.onStart() else explainMicrophone = true
                        }
                    }
                }
            }
        }
        Text(
            state.counter,
            style = OasisType.Caption,
            color = if (state.tooLong) OasisColors.SunText else OasisColors.Muted,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
    if (explainMicrophone) {
        AlertDialog(
            onDismissRequest = { explainMicrophone = false },
            title = { Text("Mikrofon für Sprachnachrichten", style = OasisType.heading(19), color = OasisColors.Night) },
            text = {
                Text(
                    "Oasis nutzt dein Mikrofon nur, solange du eine Sprachnachricht aufnimmst. Die Aufnahme wird auf deinem Gerät verschlüsselt, bevor sie verschickt wird – lesen und hören können sie nur die Menschen in diesem Chat.",
                    style = OasisType.Body,
                    color = OasisColors.Ink
                )
            },
            confirmButton = {
                OasisTextButton(
                    "Weiter",
                    {
                        explainMicrophone = false
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.testTag("chat.microphone.continue")
                )
            },
            dismissButton = { OasisTextButton("Nicht jetzt", { explainMicrophone = false }) },
            containerColor = OasisColors.Paper
        )
    }
}

/** The text field and "Senden". */
@Composable
private fun RowScope.ComposerField(
    state: ConversationState,
    onDraftChange: (String) -> Unit,
    shape: RoundedCornerShape,
    focus: FocusRequester
) {
    BasicTextField(
        value = state.draft,
        onValueChange = onDraftChange,
        enabled = state.canSend,
        textStyle = OasisType.Body.copy(color = OasisColors.Ink),
        cursorBrush = SolidColor(OasisColors.Night),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        minLines = 1,
        maxLines = 6,
        modifier = Modifier
            .weight(1f)
            .focusRequester(focus)
            .semantics { contentDescription = "Nachricht" }
            .testTag("chat.composer.field"),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = ROUND_BUTTON)
                    .background(OasisColors.Field, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (state.draft.isEmpty()) Text("Nachricht", style = OasisType.Body, color = OasisColors.Muted)
                inner()
            }
        }
    )
}

/** The size of the round „+“, microphone and send buttons and the field's least height. */
private val ROUND_BUTTON = 44.dp

/** „+“ as big as the other round buttons. */
private val ADD_BUTTON = 44.dp

/** The text field's corner radius. */
private val FIELD_RADIUS = 22.dp

/** „+“: a small menu with the attachment actions (photo or video, file). */
@Composable
private fun AttachMenu(enabled: Boolean, onMedia: () -> Unit, onFile: () -> Unit, onEffect: () -> Unit, onPoll: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundAction(
            Icons.Outlined.Add,
            "Anhang hinzufügen",
            enabled,
            filled = false,
            size = ADD_BUTTON,
            modifier = Modifier.padding(bottom = (ROUND_BUTTON - ADD_BUTTON) / 2).testTag("chat.composer.add")
        ) {
            open = true
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = OasisColors.Paper) {
            DropdownMenuItem(
                text = { Text("Foto oder Video anhängen", style = OasisType.Body, color = OasisColors.Ink) },
                leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, tint = OasisColors.Night) },
                onClick = {
                    open = false
                    onMedia()
                },
                modifier = Modifier.testTag("chat.composer.attach")
            )
            DropdownMenuItem(
                text = { Text("Datei anhängen", style = OasisType.Body, color = OasisColors.Ink) },
                leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = OasisColors.Night) },
                onClick = {
                    open = false
                    onFile()
                },
                modifier = Modifier.testTag("chat.composer.file")
            )
            DropdownMenuItem(
                text = { Text("Umfrage", style = OasisType.Body, color = OasisColors.Ink) },
                leadingIcon = { Icon(Icons.Outlined.Poll, contentDescription = null, tint = OasisColors.Night) },
                onClick = {
                    open = false
                    onPoll()
                },
                modifier = Modifier.testTag("chat.composer.poll")
            )
            DropdownMenuItem(
                text = { Text("Mit Effekt senden …", style = OasisType.Body, color = OasisColors.Ink) },
                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = OasisColors.Night) },
                onClick = {
                    open = false
                    onEffect()
                },
                modifier = Modifier.testTag("chat.composer.effect")
            )
        }
    }
}

/** „Mit Effekt senden“: the four effects; choosing one sends at once with something to send, otherwise keeps the chip. */
@Composable
private fun EffectMenu(expanded: Boolean, onDismiss: () -> Unit, onChoose: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = OasisColors.Paper) {
        Text(
            "Mit Effekt senden",
            style = OasisType.SmallBold,
            color = OasisColors.Ink,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        EffectNames.all.forEach { (key, name) ->
            DropdownMenuItem(
                text = { Text(name, style = OasisType.Body, color = OasisColors.Ink) },
                onClick = {
                    onDismiss()
                    scope.launch { onChoose(key) }
                },
                modifier = Modifier.testTag("chat.effect.$key")
            )
        }
    }
}

/** „Effekt: Konfetti“ with „✕“: the effect waits for the next message. */
@Composable
private fun EffectChip(text: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.CHIP.dp))
            .padding(start = 12.dp)
            .testTag("chat.composer.effectChip"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = OasisType.SmallBold, color = OasisColors.Ink)
        IconButton(onClick = onClear, modifier = Modifier.testTag("chat.composer.effectClear")) {
            Icon(Icons.Outlined.Close, contentDescription = "Effekt entfernen", tint = OasisColors.Night)
        }
    }
}

/** „Senden“ as a round button; it waits for the message to go out and cannot be pressed twice meanwhile. */
@Composable
private fun SendButton(enabled: Boolean, onSend: suspend () -> Unit, onLongPress: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    RoundAction(
        Icons.AutoMirrored.Filled.Send,
        "Senden",
        enabled = enabled && !sending,
        filled = true,
        modifier = Modifier.testTag("chat.composer.send"),
        onLongClick = onLongPress
    ) {
        scope.launch {
            sending = true
            try {
                onSend()
            } finally {
                sending = false
            }
        }
    }
}

/** A round icon button of the composer: outlined (+, microphone) or filled in Nachtwasser (Senden). */
@Composable
private fun RoundAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    filled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = ROUND_BUTTON,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val background = when {
        !filled -> Color.Transparent
        enabled -> OasisColors.Night
        else -> OasisColors.FieldLine
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onLongClickLabel = onLongClick?.let { "Mit Effekt senden" },
                onLongClick = onLongClick,
                onClick = onClick
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (filled) {
                OasisColors.OnNight
            } else if (enabled) {
                OasisColors.Night
            } else {
                OasisColors.Muted
            }
        )
    }
}

/**
 * One message (docs/oasis-chat-client.md, „Chat design and gestures“): own ones right on night, others left on paper
 * with their avatar at the last bubble of a run; corners and tail by the place in the run ([run]), the time inside, the
 * reactions overlapping the bottom edge. Swiping it right answers it; holding it opens the bar with the reactions,
 * „Antworten“, „Anheften“, „Melden“ and the [info] line with send time and expiry.
 */
@Composable
internal fun MessageBubble(
    item: ChatMessageItem,
    mine: Boolean,
    senderName: String,
    onReport: () -> Unit,
    avatar: ChatAvatar? = null,
    run: ChatLayout.Run = ChatLayout.Run.Single,
    showName: Boolean = false,
    time: String? = null,
    info: String = "",
    profile: ProfileLink? = null,
    extras: BubbleExtras,
    attachments: @Composable () -> Unit
) {
    SwipeToReply(extras.canAct, extras.onReply) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widest = minOf(maxWidth * 0.78f, 560.dp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, if (mine) Alignment.End else Alignment.Start),
                verticalAlignment = Alignment.Bottom
            ) {
                if (avatar != null) {
                    if (hasTail(run)) {
                        ChatAvatarView(
                            avatar,
                            Modifier.opensProfile(profile, profileOpener(profile)).testTag("chat.message.avatar"),
                            size = MESSAGE_AVATAR_SIZE
                        )
                    } else {
                        Spacer(Modifier.size(MESSAGE_AVATAR_SIZE))
                    }
                }
                BubbleColumn(
                    item, mine, senderName, onReport, run, showName, time, info, extras, attachments,
                    Modifier.widthIn(
                        max = widest
                    )
                )
            }
        }
    }
}

/** The avatar beside someone else's messages. */
private val MESSAGE_AVATAR_SIZE = 32.dp

/** Body text of a bubble: 16 with a line height of 1.45. */
private val BubbleText = OasisType.Body.copy(fontSize = 16.sp, lineHeight = (16 * 1.45).sp)

@Composable
private fun BubbleColumn(
    item: ChatMessageItem,
    mine: Boolean,
    senderName: String,
    onReport: () -> Unit,
    run: ChatLayout.Run,
    showName: Boolean,
    time: String?,
    info: String,
    extras: BubbleExtras,
    attachments: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var bar by remember { mutableStateOf(false) }
    val shape = bubbleShape(mine, run)
    val fill = if (mine) OasisColors.Night else OasisColors.Paper
    val reportable = !mine && item.state == ChatMessageItem.State.Readable
    Column(modifier = modifier, horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Box {
            Column(
                modifier = Modifier
                    .bubbleTail(hasTail(run), mine, fill)
                    // Holding the bubble opens the reaction bar; TalkBack offers it and the other steps as actions.
                    .then(if (extras.canAct || reportable) Modifier.holdOpens { bar = true } else Modifier)
                    .semantics {
                        val steps = buildList {
                            if (extras.canAct) {
                                add(
                                    CustomAccessibilityAction("Antworten") {
                                        extras.onReply()
                                        true
                                    }
                                )
                                add(
                                    CustomAccessibilityAction("Reagieren") {
                                        bar = true
                                        true
                                    }
                                )
                            }
                            if (reportable) {
                                add(
                                    CustomAccessibilityAction("Nachricht von $senderName melden") {
                                        onReport()
                                        true
                                    }
                                )
                            }
                        }
                        customActions = steps
                        if (info.isNotEmpty()) stateDescription = info
                    }
                    .background(fill, shape)
                    .then(
                        when {
                            // On „Nachtwasser“ the own (night) bubbles get an outline, so they stand out.
                            mine && LocalChatLook.current.dark -> Modifier.border(
                                1.dp,
                                LookColors.NightLine,
                                shape
                            ).testTag("chat.message.outlined")

                            !mine -> Modifier.border(1.dp, OasisColors.PaperLine, shape)

                            else -> Modifier
                        }
                    )
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
                    .testTag("chat.message.${item.signature}"),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val color = if (mine) OasisColors.OnNight else OasisColors.Ink
                if (showName) {
                    Text(
                        senderName,
                        style = OasisType.Small.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
                        color = OasisColors.SpringText,
                        modifier = Modifier.testTag("chat.message.sender")
                    )
                }
                extras.quote?.let { ReplyQuoteView(it, mine) { extras.onQuote(it.to) } }
                val (text, italic) = when (item.state) {
                    ChatMessageItem.State.Readable -> item.content.text to false
                    ChatMessageItem.State.NoKey -> "Für diese Nachricht hast du keinen Schlüssel." to true
                    ChatMessageItem.State.Unverifiable -> "Diese Nachricht ließ sich nicht prüfen und wird nicht angezeigt." to true
                }
                if (extras.poll != null) {
                    PollBubble(extras.poll, mine, extras.pollActions)
                } else if (text.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text,
                            style = BubbleText.copy(fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal),
                            color = color,
                            modifier = Modifier.testTag("chat.message.text")
                        )
                    }
                }
                attachments()
                time?.let {
                    Text(
                        it,
                        style = OasisType.Caption.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
                        color = if (mine) OasisColors.OnNightMuted else OasisColors.Muted,
                        modifier = Modifier.align(Alignment.End).testTag("chat.message.time")
                    )
                }
            }
            ReactionBar(bar, extras, info, onReport.takeIf { reportable }, onDismiss = { bar = false })
        }
        if (extras.chips.isNotEmpty()) {
            // The chips overlap the bubble's bottom edge by 10, 12 in from its inner edge.
            ReactionChips(extras.chips, Modifier.offset(y = (-10).dp).padding(horizontal = 12.dp))
        }
    }
}

/** A long-press – by touch, or TalkBack's action „Reaktion wählen“ – runs [onHold]; the bubble's content stays as it is. */
private fun Modifier.holdOpens(onHold: () -> Unit): Modifier = pointerInput(Unit) { detectTapGestures(onLongPress = { onHold() }) }
    .semantics {
        onLongClick("Reaktion wählen") {
            onHold()
            true
        }
    }
