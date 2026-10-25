package de.velwebsolutions.oasis.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.formatDateTime
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
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

    OasisScreen(
        title = state.title,
        actions = {
            IconButton(
                onClick = { conversationId.let { navigator.open(ChatRoute.Details(it)) } },
                modifier = Modifier.testTag("chat.details")
            ) {
                Icon(Icons.Outlined.Info, contentDescription = "Details & Sicherheitsnummern", tint = OasisColors.Night)
            }
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
            onLoadOlder = model::loadOlder,
            onDraftChange = model::setDraft,
            onSend = model::send,
            onReport = { report = model.reportModel(it) },
            onAccept = model::acceptRequest,
            onDecline = { block -> scope.launch { if (model.declineRequest(block)) navigator.back() } },
            onBlockFlagged = { senderId -> scope.launch { if (model.blockAndLeave(senderId)) navigator.back() } },
            onDismissFlagged = model::dismissFlagged,
            onNoticeSpoken = model::noticeSpoken
        )
    }
    report?.let { ReportSheet(it, onDismiss = { report = null }) }
}

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
    onNoticeSpoken: () -> Unit
) {
    val listState = rememberLazyListState()
    val items = state.timeline.items
    var confirmDecline by remember { mutableStateOf(false) }
    var confirmBlockRequest by remember { mutableStateOf(false) }
    var confirmBlockFlagged by remember { mutableStateOf(false) }

    // The newest message stays in view: on opening (without motion), on sending and on receiving. Older messages loaded
    // above keep the last id, so they appear in place.
    var shownLastId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(items.lastOrNull()?.id) {
        val lastId = items.lastOrNull()?.id ?: return@LaunchedEffect
        val bottom = listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
        if (shownLastId == null) listState.scrollToItem(bottom) else listState.animateScrollToItem(bottom)
        shownLastId = lastId
    }
    LaunchedEffect(state.spokenNotice) {
        if (state.spokenNotice != null) {
            delay(1_500)
            onNoticeSpoken()
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat.messages"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        state.title,
                        style = OasisType.display(26),
                        color = OasisColors.Night,
                        modifier = Modifier.testTag("chat.conversation.title")
                    )
                    Text(state.lifetimeLine, style = OasisType.Small, color = OasisColors.Muted)
                }
            }
            items(state.notices) { Banner(it, kind = BannerKind.Info) }
            state.requestTitle?.let { title ->
                item {
                    Panel(tone = PanelTone.Dune, modifier = Modifier.testTag("chat.request.banner")) {
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
                    Panel(tone = PanelTone.Warning, modifier = Modifier.testTag("chat.safety.banner")) {
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
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AsyncOasisButton("Ältere Nachrichten laden", onLoadOlder, kind = OasisButtonKind.Secondary, fullWidth = false)
                    }
                }
            }
            when {
                state.loadFailed != null -> item { Banner(state.loadFailed) }

                state.conversation == null -> item { Box(Modifier.fillMaxWidth().padding(vertical = 24.dp)) { LoadingView() } }

                items.isEmpty() -> item {
                    Text(
                        "Noch keine Nachrichten. Schreib die erste.",
                        style = OasisType.Body,
                        color = OasisColors.Ink2,
                        modifier = Modifier.fillMaxWidth().testTag("chat.messages.empty")
                    )
                }
            }
            items(items, key = { it.id }) { item ->
                MessageBubble(item, mine = item.senderId == myId, senderName = senderName(item.senderId), onReport = { onReport(item) })
            }
        }
        ConversationComposer(state, onDraftChange, onSend)
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

/** The composer with counter, tone hint and the sensitive-data warning. */
@Composable
private fun ConversationComposer(state: ConversationState, onDraftChange: (String) -> Unit, onSend: suspend () -> Unit) {
    val shape = RoundedCornerShape(OasisRadius.XS.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .animateContentSize(),
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
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    .semantics { contentDescription = "Nachricht" }
                    .testTag("chat.composer.field"),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(OasisColors.Field, shape)
                            .border(1.dp, OasisColors.FieldLine, shape)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        if (state.draft.isEmpty()) Text("Nachricht", style = OasisType.Body, color = OasisColors.Muted)
                        inner()
                    }
                }
            )
            AsyncOasisButton(
                "Senden",
                onSend,
                fullWidth = false,
                enabled = state.canSend && state.draft.isNotBlank() && !state.tooLong,
                modifier = Modifier.testTag("chat.composer.send")
            )
        }
        Text(state.counter, style = OasisType.Caption, color = if (state.tooLong) OasisColors.SunText else OasisColors.Muted)
    }
}

/** One message bubble: own messages right on night, others left on paper; expiry and "Melden" below. */
@Composable
internal fun MessageBubble(item: ChatMessageItem, mine: Boolean, senderName: String, onReport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .background(if (mine) OasisColors.Night else OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val color = if (mine) OasisColors.OnNight else OasisColors.Ink
            Text(if (mine) "Du" else senderName, style = OasisType.SmallBold, color = color)
            val (text, italic) = when (item.state) {
                ChatMessageItem.State.Readable -> item.plaintext to false
                ChatMessageItem.State.NoKey -> "Für diese Nachricht hast du keinen Schlüssel." to true
                ChatMessageItem.State.Unverifiable -> "Diese Nachricht ließ sich nicht prüfen und wird nicht angezeigt." to true
            }
            SelectionContainer {
                Text(
                    text,
                    style = OasisType.Body.copy(fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal),
                    color = color,
                    modifier = Modifier.testTag("chat.message.text")
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item.expiresAt?.let { Text("löscht sich ${formatDateTime(it)}", style = OasisType.Caption, color = OasisColors.Muted) }
            if (!mine && item.state == ChatMessageItem.State.Readable) {
                Text(
                    "Melden",
                    style = OasisType.Caption.copy(textDecoration = TextDecoration.Underline),
                    color = OasisColors.Muted,
                    modifier = Modifier
                        .pressable(onReport)
                        .padding(vertical = 12.dp)
                        .semantics { contentDescription = "Nachricht von $senderName melden" }
                        .testTag("chat.message.report")
                )
            }
        }
    }
}
