package de.velwebsolutions.oasis.chat.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationPage
import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.describeChatError
import de.velwebsolutions.oasis.chat.core.lifetimeLabel
import de.velwebsolutions.oasis.chatcrypto.checkNumber
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/** A device link request with the check number both devices show. */
data class LinkRequestEntry(val request: DeviceLinkRequest, val check: String)

/** What the conversation list shows. */
data class ConversationListState(
    val conversations: List<ChatConversation> = emptyList(),
    val requests: List<ChatConversation> = emptyList(),
    val loaded: Boolean = false,
    val nextPage: Int? = null,
    val error: String? = null,
    /** Other devices asking to open the chat, with their check numbers. */
    val linkRequests: List<LinkRequestEntry> = emptyList(),
    /** The confirmation of the last action (toast). */
    val status: String? = null,
    /** The error of the last action (toast). */
    val actionError: String? = null
)

/**
 * Screen "list" (ui/conversation-list.js + the link requests of ui/device-links.js; iOS `ConversationListModel`):
 * conversations newest first, 30 per page, message requests in their own section, "Neu" for unread chats, and other
 * devices asking to be let in.
 */
@HiltViewModel
class ConversationListViewModel @Inject constructor(context: ChatContext, private val realtime: RealtimeService) : ChatViewModel(context) {
    private val mutableState = MutableStateFlow(ConversationListState())
    val state: StateFlow<ConversationListState> = mutableState.asStateFlow()

    val myId: String get() = context.userId
    val canParticipate: Boolean get() = context.canParticipate

    /** First page (and the link requests). */
    suspend fun load() = inScope {
        mutableState.update { it.copy(error = null) }
        try {
            val page = context.repository.conversations(1)
            mutableState.update { it.copy(conversations = emptyList(), requests = emptyList()) }
            apply(page)
            mutableState.update { it.copy(loaded = true) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
        refreshLinkRequests()
    }

    /** "Ältere laden". */
    suspend fun loadMore() = inScope {
        val next = state.value.nextPage ?: return@inScope
        try {
            apply(context.repository.conversations(next))
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    private fun apply(page: ConversationPage) = mutableState.update { current ->
        val known = (current.conversations + current.requests).map { it.id }.toMutableSet()
        val conversations = current.conversations.toMutableList()
        val requests = current.requests.toMutableList()
        for (conversation in page.data) {
            if (!known.add(conversation.id)) continue
            if (conversation.request == true) requests += conversation else conversations += conversation
        }
        current.copy(
            conversations = conversations,
            requests = requests,
            nextPage = if (page.currentPage < page.lastPage) page.currentPage + 1 else null
        )
    }

    /** Meta line: "Gruppe · 3 Menschen · löscht nach 7 Tagen · wartet auf neuen Schlüssel". */
    fun meta(conversation: ChatConversation): String {
        val parts = mutableListOf(
            if (conversation.isGroup) "Gruppe · ${conversation.members.size} Menschen" else "Zu zweit",
            "löscht nach ${lifetimeLabel(conversation.lifetimeDays)}"
        )
        if (conversation.rotationRequired) parts += "wartet auf neuen Schlüssel"
        return parts.joinToString(" · ")
    }

    /** The row's title: group name or the other people's names. */
    fun title(conversation: ChatConversation): String = conversation.displayTitle(context.userId)

    /** "Sperren". */
    fun lock() = vault.lock()

    /** The confirmation toast was shown. */
    fun dismissStatus() = mutableState.update { it.copy(status = null) }

    /** The error toast was shown. */
    fun dismissActionError() = mutableState.update { it.copy(actionError = null) }

    // region Device links (approving side)

    /** Polled every few seconds: only a request that came or went changes the state; check numbers are derived once. */
    suspend fun refreshLinkRequests() {
        if (!vault.unlocked) return
        val rows = runCatching { context.repository.deviceLinks() }.getOrNull() ?: return
        val known = state.value.linkRequests.associate { it.request to it.check }
        val fresh = rows.map { LinkRequestEntry(it, known[it] ?: context.crypto.checkNumber(it.publicKey)) }
        if (fresh != state.value.linkRequests) mutableState.update { it.copy(linkRequests = fresh) }
    }

    /** "Ja, gleiche Zahl – freigeben". */
    suspend fun approve(request: DeviceLinkRequest) = inScope {
        try {
            context.devices.approve(request)
            mutableState.update { it.copy(status = "„${request.label}“ ist freigegeben.") }
        } catch (error: Exception) {
            mutableState.update { it.copy(actionError = describeChatError(error)) }
        }
        mutableState.update { current -> current.copy(linkRequests = current.linkRequests.filter { it.request.id != request.id }) }
    }

    /** "Ablehnen". */
    suspend fun reject(request: DeviceLinkRequest) = inScope {
        try {
            context.repository.rejectDeviceLink(request.id)
            mutableState.update { current ->
                current.copy(status = "Anfrage abgelehnt.", linkRequests = current.linkRequests.filter { it.request.id != request.id })
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(actionError = describeChatError(error)) }
        }
    }

    // endregion

    /**
     * While the list is shown: reload on new chat activity (`private-inbox.{id}`, `InboxActivity` kind "chat"), poll while
     * offline, and refresh the link requests every few seconds (like the web).
     */
    suspend fun liveUpdates(): Nothing = coroutineScope {
        realtime.subscribe("private-inbox.${context.userId}").filter { it.string("kind") == "chat" }.onEach { load() }.launchIn(this)
        tick()
    }

    /** Every few seconds: the link requests, and the list itself every fifth time while offline. */
    private suspend fun tick(): Nothing {
        var ticks = 0
        while (true) {
            delay(4.seconds)
            ticks += 1
            refreshLinkRequests()
            if (!realtime.isConnected.value && ticks % 5 == 0) load()
        }
    }
}
