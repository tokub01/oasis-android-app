package de.velwebsolutions.oasis.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.describeChatError
import de.velwebsolutions.oasis.core.share.PendingShare
import de.velwebsolutions.oasis.core.share.Share
import de.velwebsolutions.oasis.design.components.Banner
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
import de.velwebsolutions.oasis.design.theme.OasisType
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What "Teilen mit …" shows: the chats the member can write in. */
data class ShareTargetState(val conversations: List<ChatConversation> = emptyList(), val loaded: Boolean = false, val error: String? = null)

/**
 * Screen "Teilen mit …" (docs/mildcrowd-chat.md, "Sharing into Mildcrowd"): what another app shared and the member's chats; the
 * picked chat takes the share as drafts when it opens. Every chat of the first page is offered, message requests too
 * (iOS `ShareTargetModel`): nothing is sent before "Senden" there.
 */
@HiltViewModel
class ShareTargetViewModel @Inject constructor(context: ChatContext) : ChatViewModel(context) {
    private val mutableState = MutableStateFlow(ShareTargetState())
    val state: StateFlow<ShareTargetState> = mutableState.asStateFlow()

    /** The share waiting for a chat (null once taken or cancelled). */
    val share: StateFlow<Share?> = PendingShare.share

    val myId: String get() = context.userId

    /** The first page of chats, newest first. */
    suspend fun load() = inScope {
        try {
            val page = context.repository.conversations(1)
            mutableState.update { ShareTargetState(page.data, loaded = true) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    /** A chat was picked: it takes the share when it opens. */
    fun pick(conversation: ChatConversation) = PendingShare.target(conversation.id)

    /** "Abbrechen": the shared copies are deleted. */
    fun cancel() = PendingShare.discard()
}

@Composable
internal fun ShareTargetScreen() {
    val model: ShareTargetViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val share by model.share.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { model.load() }
    OasisScreen(title = "Teilen mit …") {
        ShareTargetContent(
            state = state,
            share = share,
            myId = model.myId,
            onPick = { conversation ->
                model.pick(conversation)
                navigator.back()
                navigator.open(ChatRoute.Conversation(conversation.id.toInt()))
            },
            onCancel = {
                model.cancel()
                navigator.back()
            }
        )
    }
}

@Composable
internal fun ShareTargetContent(
    state: ShareTargetState,
    share: Share?,
    myId: String,
    onPick: (ChatConversation) -> Unit,
    onCancel: () -> Unit
) {
    ChatPage(spacing = 14) {
        if (share == null) {
            Text(
                "Hier gibt es gerade nichts zu teilen.",
                style = OasisType.Body,
                color = OasisColors.Ink2,
                modifier = Modifier.testTag("chat.share.empty")
            )
            return@ChatPage
        }
        Panel(tone = PanelTone.Dune, modifier = Modifier.testTag("chat.share.summary")) {
            share.files.forEach { Text(it.name, style = OasisType.SmallBold, color = OasisColors.Night) }
            share.text?.let { Text("„${it.take(140)}“", style = OasisType.Small, color = OasisColors.Ink2) }
        }
        Text(
            "Wähl den Chat. Gesendet wird erst, wenn du dort auf „Senden“ tippst.",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
        when {
            state.error != null -> Banner(state.error)
            !state.loaded -> LoadingView()
            state.conversations.isEmpty() -> Text("Du hast noch keine Chats.", style = OasisType.Body, color = OasisColors.Ink2)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.conversations.forEach { conversation ->
                Text(
                    conversation.displayTitle(myId),
                    style = OasisType.BodyBold,
                    color = OasisColors.Night,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .pressable({ onPick(conversation) })
                        .padding(vertical = 12.dp)
                        .testTag("chat.share.conversation")
                )
            }
        }
        OasisButton(
            "Abbrechen",
            onCancel,
            kind = OasisButtonKind.Secondary,
            fullWidth = false,
            modifier = Modifier.testTag("chat.share.cancel")
        )
    }
}
