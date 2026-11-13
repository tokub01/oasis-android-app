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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.chat.api.ChatAvatar
import de.velwebsolutions.oasis.chat.core.AttachmentView
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
import de.velwebsolutions.oasis.design.components.OasisSwitch
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Screen "conversation" (partials/conversation.blade.php; iOS `ConversationView`): notices, request banner, safety
 * banner, messages with "Melden", and the composer with counter, tone hint and the sensitive-data warning. While its
 * details stand beside it ([detailsOpen], wide screens), „Details“ closes them again.
 */
@Composable
internal fun ConversationScreen(conversationId: Int, detailsOpen: Boolean = false) {
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
    // Made once per screen: new action objects on every keystroke would compose every bubble again.
    val actions = remember(model) { ScreenActions(model) }
    // The header's „Details“ opens and closes the details pane; a quiet line only opens it.
    val openDetails = { navigator.openDetails(conversationId, detailsOpen) }
    val toggleDetails = { navigator.toggleDetails(conversationId, detailsOpen) }
    OasisScreen(
        title = state.title,
        topBar = {
            ChatHeaderBar(
                state.conversation,
                model.context.userId,
                state.title,
                state.headerStatus,
                onBack = chrome.onBack,
                onDetails = toggleDetails
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
            attachments = actions.attachments,
            hint = SwipeHintActions(onDismiss = model::dismissSwipeHint, onNudged = model::nudged),
            onOpenDetails = openDetails,
            effects = actions.effects,
            reactions = actions.reactions,
            polls = actions.polls
        )
    }
    report?.let { ReportSheet(it, onDismiss = { report = null }) }
}

/** The steps of the conversation screen, made once per view model. */
private class ScreenActions(model: ConversationViewModel) {
    val attachments = model.attachmentActions()
    val effects = EffectActions(onChoose = model::chooseEffect, onClear = model::clearEffect, onDone = model::effectDone)
    val reactions = ReactionActions(
        onReact = model::react,
        onReply = model::startReply,
        onCancelReply = model::cancelReply,
        onQuote = model::quoteTarget,
        onPin = model::togglePin,
        onVote = model::vote,
        onClosePoll = model::closePoll,
        onSwipeReply = model::swipedReply
    )
    val polls = PollFormActions(
        onOpen = { model.showPollForm(PollForm()) },
        onEdit = model::editPoll,
        onCancel = { model.showPollForm(null) },
        onSend = model::sendPoll
    )
}

/** The view model's attachment steps for the screen. */
internal fun ConversationViewModel.attachmentActions() = AttachmentActions(
    onPicked = ::addPicked,
    onRemove = ::removeDraft,
    onViewOnce = ::setViewOnceNext,
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
    polls: PollFormActions = PollFormActions.None,
    hint: SwipeHintActions = SwipeHintActions.None,
    /** A tap on a quiet line: the details with the safety numbers. */
    onOpenDetails: () -> Unit = {}
) {
    val look = LookStyle.of(state.look)
    CompositionLocalProvider(LocalChatLook provides look) {
        Box(Modifier.fillMaxSize()) {
            ConversationColumn(
                state, myId, senderName, onLoadOlder, onDraftChange, onSend, onReport, onAccept, onDecline, onBlockFlagged,
                onDismissFlagged, onNoticeSpoken, attachments, senderAvatar, senderProfile, effects, reactions, polls, hint,
                onOpenDetails
            )
            // Over everything, never in the way of a tap.
            state.playing?.let { EffectOverlay(it, effects.onDone) }
        }
    }
}

/** The swipe hint: „Verstanden“, and the end of the bubble's nudge. */
class SwipeHintActions(val onDismiss: suspend () -> Unit, val onNudged: () -> Unit) {
    companion object {
        /** Screens without the hint (previews). */
        val None = SwipeHintActions({}, {})
    }
}

/** The actions of the send effects: choosing one, „✕“ on the chip, and the end of a playing. */
class EffectActions(val onChoose: suspend (String) -> Unit, val onClear: () -> Unit, val onDone: () -> Unit) {
    companion object {
        /** Screens without effects (previews). */
        val None = EffectActions({}, {}, {})
    }
}

/** What a bubble can do, made once per screen so rows that did not change skip composing. */
@Stable
internal class RowActions(
    val reactions: ReactionActions,
    val onReport: (ChatMessageItem) -> Unit,
    /** A tap on a quiet line. */
    val onNotice: () -> Unit,
    /** The nudge of the swipe hint played. */
    val onNudged: () -> Unit
)

/** One row of the message list: a day pill, the „Neue Nachrichten“ divider or a message with its gap above. */
@Composable
private fun TimelineRowView(
    row: RowModel,
    attachmentViews: Map<String, AttachmentView>,
    transcription: TranscriptionView,
    attachments: AttachmentActions,
    actions: RowActions,
    /** This is the bubble the swipe hint moves right and back once. */
    nudge: Boolean,
    onJump: (Int) -> Unit
) {
    when (row) {
        is RowModel.Day -> DayPill(row.label)

        is RowModel.Unread -> UnreadDivider()

        is RowModel.Notice -> QuietLine(row.text, actions.onNotice)

        // A message (the last kind of row).
        else -> {
            val model = (row as RowModel.Bubble).model
            val item = model.item
            val scope = rememberCoroutineScope()
            val reactions = actions.reactions
            val extras = remember(model, actions) {
                BubbleExtras(
                    quote = model.quote,
                    chips = model.chips,
                    own = model.own,
                    canAct = model.canAct,
                    onReact = { emoji -> scope.launch { reactions.onReact(item, emoji) } },
                    onReply = { reactions.onReply(item) },
                    onQuote = { to -> reactions.onQuote(to)?.let(onJump) },
                    pinLabel = model.pinLabel,
                    onPin = { scope.launch { reactions.onPin(item) } },
                    poll = model.poll,
                    pollActions = PollActions(
                        onVote = { index -> scope.launch { reactions.onVote(item, index) } },
                        onClose = { scope.launch { reactions.onClosePoll(item) } }
                    )
                )
            }
            Box(Modifier.padding(top = rowGap(model.run, model.afterSeparator))) {
                MessageBubble(
                    item,
                    mine = model.mine,
                    senderName = model.senderName,
                    onReport = { actions.onReport(item) },
                    // Beside someone else's messages: the avatar at the last bubble of each of their runs.
                    avatar = model.avatar,
                    profile = model.profile,
                    run = model.run,
                    showName = model.showName,
                    time = model.time,
                    info = model.info,
                    extras = extras,
                    onSwipeReply = { scope.launch { reactions.onSwipeReply(item) } },
                    nudge = if (nudge) actions.onNudged else null
                ) {
                    MessageAttachments(item, model.mine, model.senderName, attachmentViews, attachments, transcription)
                }
            }
        }
    }
}

/** The timeline's inset left and right: 12 on phones, 16 from a width of 640; beside other panes the [pane]'s own inset. */
internal fun timelineInset(width: Dp, pane: Dp? = null): Dp = pane ?: if (width >= 640.dp) 16.dp else 12.dp

/** The floating composer's distance to the left and right edge: 12, beside other panes the [pane]'s own inset. */
internal fun composerInset(pane: Dp?): Dp = pane ?: 12.dp

/** How often bubbles were composed – read by the tests that keep scrolling and typing cheap. */
internal object ChatCompositions {
    @Volatile
    var bubbles = 0
}

/** The composer field's greatest height in a chat area of [area]: a third of it (at least one line), without a limit before it is measured. */
internal fun fieldMax(area: Dp): Dp = if (area > 0.dp) max(area / 3, ROUND_BUTTON) else Dp.Unspecified

/**
 * „Nach unten“ shows: scrolled [away] from the newest message, and the button (44 and its gap of 12) fits between the
 * composer and the top of the chat area with as much room above it, so it never covers the topmost message.
 */
internal fun showsScrollDown(away: Boolean, area: Dp, composerSpace: Dp): Boolean = away && area - composerSpace >= (44.dp + 12.dp) * 2

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
    polls: PollFormActions,
    hint: SwipeHintActions,
    onOpenDetails: () -> Unit
) {
    val look = LocalChatLook.current
    val photo = rememberLookPhoto(state.look.photo)
    val scope = rememberCoroutineScope()
    var confirmDecline by remember { mutableStateOf(false) }
    var confirmBlockRequest by remember { mutableStateOf(false) }
    var confirmBlockFlagged by remember { mutableStateOf(false) }

    // The rows with everything a bubble shows, made once per change of the timeline and what belongs to it – not on
    // every keystroke, so typing composes no bubble again.
    val rows =
        remember(
            state.shownRows,
            state.reactions,
            state.quotes,
            state.polls,
            state.canSend,
            state.pinBar,
            state.canPin,
            state.conversation,
            state.quietNotices
        ) {
            rowModels(state, myId, senderName, senderAvatar, senderProfile, Instant.now())
        }
    val transcription = remember(state.canTranscribe, state.transcripts) { TranscriptionView(state.canTranscribe, state.transcripts) }
    val rowActions = remember(reactions, onReport, onOpenDetails, hint) { RowActions(reactions, onReport, onOpenDetails, hint.onNudged) }
    // The newest message is the list's first item, drawn at the bottom: a chat opens there without a jump, older
    // messages load above without moving what is shown, and the keyboard keeps the newest message in view.
    val listState = rememberLazyListState()

    LaunchedEffect(state.spokenNotice) {
        if (state.spokenNotice != null) {
            delay(1_500)
            onNoticeSpoken()
        }
    }

    val density = LocalDensity.current
    val away = with(density) { SCROLL_DOWN_DISTANCE.toPx() }
    // „Nach unten“: shown while scrolled up more than 160 from the newest message; counts others' messages that came meanwhile.
    val awayFromBottom by remember {
        derivedStateOf { awayFromBottom(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, away) }
    }
    var arrived by remember { mutableIntStateOf(0) }
    val newestMessage = rows.lastOrNull { it is RowModel.Bubble } as RowModel.Bubble?
    // A new newest message: followed only at the bottom (and always the own one); scrolled up, others' are counted.
    var shownLastId by remember { mutableStateOf<String?>(null) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(newestMessage?.model?.item?.id) {
        val last = newestMessage?.model ?: return@LaunchedEffect
        when (newest(first = shownLastId == null, away = awayFromBottom, fromOthers = !last.mine)) {
            Newest.Jump -> placeAtUnread(listState, rows)
            Newest.Count -> arrived += 1
            Newest.Follow -> listState.animateScrollToItem(0)
        }
        shownLastId = last.item.id
        placed = true
    }
    LaunchedEffect(awayFromBottom) { if (!awayFromBottom) arrived = 0 }
    var composerHeight by remember { mutableIntStateOf(0) }
    val composerSpace = with(density) { composerHeight.toDp() } + 24.dp
    // The room above the keyboard: a long draft takes at most a third of it (the field scrolls), so messages stay in view.
    var area by remember { mutableStateOf(0.dp) }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding().onSizeChanged { area = (it.height / density.density).dp }) {
        // The same inset for own and others' bubbles: 12 on phones, 16 from 640, a pane's own inset beside other panes
        // (docs/oasis-chat-client.md, „Spacing“).
        val paneInset = LocalPaneInset.current
        val inset = timelineInset(maxWidth, paneInset)
        LazyColumn(
            state = listState,
            reverseLayout = true,
            // The personal background fills the whole message area, also under the floating composer.
            modifier = Modifier
                .fillMaxSize()
                .chatBackground(state.look.background, photo)
                // Until the first message is placed (at the newest one or the divider) nothing is drawn: no jump.
                .graphicsLayer { alpha = if (placed || rows.none { it is RowModel.Bubble }) 1f else 0f }
                .testTag("chat.messages"),
            // The first item brings its own 12 (a run's start), so the top adds nothing.
            contentPadding = PaddingValues(start = inset, end = inset, top = 0.dp, bottom = composerSpace)
        ) {
            items(rows.asReversed(), key = { it.key }, contentType = { it.contentType }) { row ->
                // Only a row with files gets the files' views, so loading one composes no other row again.
                val views = if (row.hasFiles) state.attachmentViews else emptyMap()
                TimelineRowView(row, views, transcription, attachments, rowActions, nudge = row.key == state.nudge) { target ->
                    scope.launch { listState.animateScrollToItem(rows.size - 1 - target) }
                }
            }
            emptyNote(state, rows.isEmpty(), look)
            state.flagged?.let { flagged ->
                item(key = "flagged", contentType = "banner") {
                    Panel(tone = PanelTone.Warning, modifier = Modifier.padding(top = 12.dp).testTag("chat.safety.banner")) {
                        Text(flagged.title, style = OasisType.BodyBold, color = OasisColors.Night)
                        Text(flagged.text, style = OasisType.Small, color = OasisColors.Ink2)
                        OasisButton("Blockieren und Chat löschen", { confirmBlockFlagged = true }, fullWidth = false)
                        OasisButton("Nachricht melden", { onReport(flagged.item) }, kind = OasisButtonKind.Secondary, fullWidth = false)
                        SmallAction("Ist in Ordnung", onDismissFlagged, underline = true)
                        Text(
                            "Diese Prüfung läuft nur auf deinem Gerät. Mildcrowd liest deine Chats nicht; gemeldet wird nur, was du meldest.",
                            style = OasisType.Caption,
                            color = OasisColors.Muted
                        )
                    }
                }
            }
            state.requestTitle?.let { title ->
                item(key = "request", contentType = "banner") {
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
            state.pinBar?.let { pin ->
                item(key = "pin", contentType = "pin") {
                    Box(Modifier.padding(top = 12.dp)) {
                        PinBarView(pin) {
                            reactions.onQuote(pin.to)?.let { target ->
                                scope.launch { listState.animateScrollToItem(rows.size - 1 - target) }
                            }
                        }
                    }
                }
            }
            items(state.notices.asReversed(), contentType = {
                "notice"
            }) { Box(Modifier.padding(top = 12.dp)) { Banner(it, kind = BannerKind.Info) } }
            if (state.nextBeforeId != null) {
                item(key = "older", contentType = "older") {
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
        }
        // The floating composer; its height is the list's room at the bottom.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = composerInset(paneInset), vertical = 12.dp)
                .onSizeChanged { composerHeight = it.height }
        ) {
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
                WithSwipeHint(state.swipeHint, hint.onDismiss) {
                    ConversationComposer(state, onDraftChange, onSend, attachments, effects, reactions.onCancelReply, polls, fieldMax(area))
                }
            }
        }
        // Outside the measured composer, so its showing does not change the list's room; only where the list above the
        // composer has room for it, so it never covers the topmost message.
        if (rows.isNotEmpty() && showsScrollDown(awayFromBottom, area, composerSpace)) {
            ScrollDownButton(
                arrived,
                onClick = {
                    arrived = 0
                    scope.launch { listState.animateScrollToItem(0) }
                },
                // 12 above the composer, 4 in from the right edge (docs/oasis-chat-client.md, „Spacing“).
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = composerSpace)
            )
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

/** The composer, with the swipe hint above it while that shows. */
@Composable
private fun WithSwipeHint(shows: Boolean, onDismiss: suspend () -> Unit, composer: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (shows) SwipeHintPill(onDismiss)
        composer()
    }
}

/** In place of messages: why they cannot load, the loading spinner, or „Noch keine Nachrichten“. */
private fun LazyListScope.emptyNote(state: ConversationState, empty: Boolean, look: LookStyle) {
    when {
        state.loadFailed != null -> item(contentType = "note") { Box(Modifier.padding(top = 12.dp)) { Banner(state.loadFailed) } }

        state.conversation == null -> item(contentType = "note") {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp)) { LoadingView() }
        }

        empty -> item(contentType = "note") {
            Text(
                "Noch keine Nachrichten. Schreib die erste.",
                style = OasisType.Body,
                color = OasisColors.Ink2,
                modifier = Modifier.padding(top = 12.dp).lookPill(look).testTag("chat.messages.empty")
            )
        }
    }
}

/**
 * Opening: with a „Neue Nachrichten“ divider the list shows it at the top (the new messages below it); otherwise the
 * list already starts at the newest message.
 */
private suspend fun placeAtUnread(listState: LazyListState, rows: List<RowModel>) {
    val divider = rows.indexOf(RowModel.Unread).takeIf { it >= 0 } ?: return
    // Once the list knows its height: the divider first at the bottom, then three quarters of the height towards the
    // newest message, so the divider stands near the top with the new messages below it.
    val height = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
    listState.scrollToItem(rows.size - 1 - divider)
    listState.scrollBy(-height * DIVIDER_PLACE)
}

/** How far up the „Neue Nachrichten“ divider stands when a chat opens (of the list's height). */
private const val DIVIDER_PLACE = 0.75f

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
    polls: PollFormActions,
    /** The field's greatest height ([fieldMax]). */
    fieldMax: Dp
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
        state.effectChip?.let { chip ->
            EffectChip(chip, "Effekt entfernen", effects.onClear, "chat.composer.effectChip", "chat.composer.effectClear")
        }
        if (state.viewOnceNext) {
            EffectChip(
                "Einmal ansehen",
                "Einmal ansehen ausschalten",
                { attachments.onViewOnce(false) },
                "chat.composer.viewOnceChip",
                "chat.composer.viewOnceClear"
            )
        }
        val seconds = state.recordingSeconds
        if (seconds != null) {
            RecordingBar(seconds, attachments)
        } else {
            // „+“ (everything besides writing, in its sheet), the field across the remaining width, and one round button: the
            // microphone while nothing is written or attached, „Senden“ as soon as something is (like iOS and the web).
            Box {
                // The effect menu opens at the round button, from „Senden“ or from „+“ (also with nothing written yet).
                Box(Modifier.align(Alignment.BottomEnd)) {
                    EffectMenu(effectMenu, onDismiss = { effectMenu = false }, onChoose = effects.onChoose)
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoreSheet(
                        enabled = state.canSend,
                        canAttach = state.canAttach,
                        viewOnce = state.viewOnceNext,
                        onViewOnce = attachments.onViewOnce,
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
                    ComposerField(state, onDraftChange, shape, focus, fieldMax)
                    if (state.showsSend) {
                        // Holding „Senden“ opens the effect menu.
                        SendButton(state.canSubmit, onSend, label = state.sendLabel, onLongPress = { effectMenu = true })
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
        if (state.counter.isNotEmpty()) {
            Text(
                state.counter,
                style = OasisType.Caption,
                color = if (state.atLimit) OasisColors.SunText else OasisColors.Muted,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("chat.composer.counter")
            )
        }
    }
    if (explainMicrophone) {
        AlertDialog(
            onDismissRequest = { explainMicrophone = false },
            title = { Text("Mikrofon für Sprachnachrichten", style = OasisType.heading(19), color = OasisColors.Night) },
            text = {
                Text(
                    "Mildcrowd nutzt dein Mikrofon nur, solange du eine Sprachnachricht aufnimmst. Die Aufnahme wird auf deinem Gerät verschlüsselt, bevor sie verschickt wird – lesen und hören können sie nur die Menschen in diesem Chat.",
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

/**
 * The text field. It holds what is typed itself, so every key press – also a held key repeating, several spaces – lands
 * at once and is never written back from the view model; the view model hears of each change and writes only its own
 * changes (sent, shared in, left) into it ([ConversationState.draftRevision]). It takes at most the limit ([DraftLimit]).
 */
@Composable
private fun RowScope.ComposerField(
    state: ConversationState,
    onDraftChange: (String) -> Unit,
    shape: RoundedCornerShape,
    focus: FocusRequester,
    maxHeight: Dp
) {
    val field = rememberTextFieldState(state.draft)
    val limit by rememberUpdatedState(state.limit)
    val draftChange by rememberUpdatedState(onDraftChange)
    LaunchedEffect(state.draftRevision) { if (field.text.toString() != state.draft) field.setTextAndPlaceCursorAtEnd(state.draft) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.drop(1).collect { draftChange(it) } }
    val keepWithinLimit = remember { InputTransformation { limit.applyTo(this) } }
    BasicTextField(
        state = field,
        enabled = state.canSend,
        inputTransformation = keepWithinLimit,
        textStyle = OasisType.Body.copy(color = OasisColors.Ink),
        cursorBrush = SolidColor(OasisColors.Night),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
        modifier = Modifier
            .weight(1f)
            .heightIn(max = maxHeight)
            .focusRequester(focus)
            .semantics { contentDescription = state.placeholder }
            .testTag("chat.composer.field"),
        decorator = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = ROUND_BUTTON)
                    .background(OasisColors.Field, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (field.text.isEmpty()) Text(state.placeholder, style = OasisType.Body, color = OasisColors.Muted)
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

/**
 * „+“ (label „Mehr“, docs/oasis-chat-client.md, „C7 Composer“): a sheet with everything besides writing, in this order:
 * „Foto oder Video“, „Datei“, „Umfrage“, „Effekt“ and the switch „Einmal ansehen“ for the files of the next message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun MoreSheet(
    enabled: Boolean,
    canAttach: Boolean,
    viewOnce: Boolean,
    onViewOnce: (Boolean) -> Unit,
    onMedia: () -> Unit,
    onFile: () -> Unit,
    onEffect: () -> Unit,
    onPoll: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    RoundAction(
        Icons.Outlined.Add,
        "Mehr",
        enabled,
        filled = false,
        size = ADD_BUTTON,
        modifier = Modifier.padding(bottom = (ROUND_BUTTON - ADD_BUTTON) / 2).testTag("chat.composer.add")
    ) {
        open = true
    }
    if (!open) return
    val close = { open = false }
    ModalBottomSheet(onDismissRequest = close, containerColor = OasisColors.Paper, modifier = Modifier.testTag("chat.composer.more")) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SheetItem("Foto oder Video", Icons.Outlined.AddPhotoAlternate, canAttach, Modifier.testTag("chat.composer.attach")) {
                close()
                onMedia()
            }
            SheetItem("Datei", Icons.Outlined.AttachFile, canAttach, Modifier.testTag("chat.composer.file")) {
                close()
                onFile()
            }
            SheetItem("Umfrage", Icons.Outlined.Poll, true, Modifier.testTag("chat.composer.poll")) {
                close()
                onPoll()
            }
            SheetItem("Effekt", Icons.Outlined.AutoAwesome, true, Modifier.testTag("chat.composer.effect")) {
                close()
                onEffect()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(viewOnce, role = Role.Switch, onValueChange = onViewOnce)
                    .padding(horizontal = 24.dp)
                    .testTag("chat.composer.viewOnce"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(Icons.Outlined.Visibility, contentDescription = null, tint = OasisColors.Night)
                Column(Modifier.weight(1f)) {
                    Text("Einmal ansehen", style = OasisType.Body, color = OasisColors.Ink)
                    Text("Für die Fotos, Videos und Dateien der nächsten Nachricht", style = OasisType.Caption, color = OasisColors.Muted)
                }
                OasisSwitch(viewOnce, onCheckedChange = null)
            }
        }
    }
}

/** One row of the „+“ sheet. */
@Composable
private fun SheetItem(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) OasisColors.Night else OasisColors.Muted)
        Text(label, style = OasisType.Body, color = if (enabled) OasisColors.Ink else OasisColors.Muted)
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

/** A chip above the field with „✕“: „Effekt: Konfetti“ (the effect waits for the next message) or „Einmal ansehen“. */
@Composable
private fun EffectChip(text: String, clearLabel: String, onClear: () -> Unit, tag: String, clearTag: String) {
    Row(
        modifier = Modifier.testTag(tag).background(OasisColors.Dune, RoundedCornerShape(OasisRadius.CHIP.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = OasisType.SmallBold, color = OasisColors.Ink)
        IconButton(onClick = onClear, modifier = Modifier.testTag(clearTag)) {
            Icon(Icons.Outlined.Close, contentDescription = clearLabel, tint = OasisColors.Night)
        }
    }
}

/**
 * „Senden“ as a round button; it waits for the message to go out and cannot be pressed twice meanwhile. With a [label]
 * („Trotzdem senden“, „Noch 27 s“) it widens to show it.
 */
@Composable
private fun SendButton(enabled: Boolean, onSend: suspend () -> Unit, onLongPress: () -> Unit, label: String? = null) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    RoundAction(
        Icons.AutoMirrored.Filled.Send,
        label ?: "Senden",
        enabled = enabled && !sending,
        filled = true,
        modifier = Modifier.testTag("chat.composer.send"),
        label = label,
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
    /** Text instead of the icon (the button widens to a pill). */
    label: String? = null,
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
            .height(size)
            .widthIn(min = size)
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
        if (label != null) {
            Text(
                label,
                style = OasisType.SmallBold,
                color = OasisColors.OnNight,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
        } else {
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
    /** Swiping the bubble right: answers it, like „Antworten“, and ends the swipe hint. */
    onSwipeReply: () -> Unit,
    /** The swipe hint's nudge for this bubble, called when it played; null for every other bubble. */
    nudge: (() -> Unit)?,
    attachments: @Composable () -> Unit
) {
    SwipeToReply(extras.canAct, onSwipeReply, nudge) {
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
    ChatCompositions.bubbles++
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
                    ChatMessageItem.State.NoKey -> "Diese Nachricht kannst du auf diesem Gerät nicht lesen." to true
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
