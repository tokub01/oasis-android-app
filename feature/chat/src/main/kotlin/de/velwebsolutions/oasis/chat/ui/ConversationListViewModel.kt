package de.velwebsolutions.oasis.chat.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ChatSuggestion
import de.velwebsolutions.oasis.chat.api.ConversationPage
import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.describeChatError
import de.velwebsolutions.oasis.chat.core.lifetimeLabel
import de.velwebsolutions.oasis.chatcrypto.checkNumber
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import java.time.Duration
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
    val actionError: String? = null,
    /** „Sichere deine Chats, falls du dein Gerät verlierst“: no backup passphrase yet, and „Später“ not within 14 days. */
    val backupRow: Boolean = false,
    /** Up to two people to write first, for the empty list. */
    val suggestions: List<ChatSuggestion> = emptyList()
) {
    /** No chats and no requests: „Noch keine Chats“. */
    val empty: Boolean get() = loaded && conversations.isEmpty() && requests.isEmpty()
}

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

    /** The highest page shown ("Ältere laden" adds pages below the first). */
    private var shownPages = 0

    /**
     * First page (and the link requests). Older pages already shown are loaded again below it (coming back to the list,
     * pulling it down), so chats that are gone – left, declined, removed – go and the others keep their places.
     */
    suspend fun load() = inScope {
        mutableState.update { it.copy(error = null) }
        try {
            val pages = mutableListOf(context.repository.conversations(1))
            while (pages.size < shownPages && pages.last().currentPage < pages.last().lastPage) {
                pages += context.repository.conversations(pages.last().currentPage + 1)
            }
            pages.forEachIndexed { index, page -> apply(page, first = index == 0) }
            mutableState.update { it.copy(loaded = true) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
        refreshBackupRow()
        if (state.value.empty) loadSuggestions()
        refreshLinkRequests()
    }

    /** The backup row shows while the account has no passphrase copy and „Später“ is not within 14 days. */
    private suspend fun refreshBackupRow() {
        val snoozed = context.preferences.backupRowSnoozedUntil(context.userId)?.isAfter(vault.clock.now()) == true
        val row = vault.storedIdentity?.lockedIdentity == null && !snoozed
        mutableState.update { it.copy(backupRow = row) }
    }

    /** „Später“ on the backup row: hidden for 14 days on this device. */
    suspend fun snoozeBackupRow() = inScope {
        context.preferences.snoozeBackupRow(context.userId, vault.clock.now().plus(BACKUP_SNOOZE))
        mutableState.update { it.copy(backupRow = false) }
    }

    /** People to write first; without them (or offline, or without a membership: 402) only „Neuer Chat“ shows. */
    private suspend fun loadSuggestions() {
        val people = runCatching { context.repository.suggestions() }.getOrDefault(emptyList())
        mutableState.update { it.copy(suggestions = people.take(MAX_SUGGESTIONS)) }
    }

    /**
     * New activity, or a poll while offline: only the first page again – every page shown each time would cost a request
     * per page and message. It goes on top; the older pages shown keep the chats it does not hold, in their places, and
     * "Ältere laden" stays.
     */
    internal suspend fun reloadFirstPage() = inScope {
        try {
            val page = context.repository.conversations(1)
            if (shownPages > 1) merge(page) else apply(page, first = true)
            mutableState.update { it.copy(loaded = true, error = null) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    /** "Ältere laden". */
    suspend fun loadMore() = inScope {
        val next = state.value.nextPage ?: return@inScope
        try {
            apply(context.repository.conversations(next), first = false)
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    /** A page in its place: the first one replaces the list, a later one goes below the chats shown so far. */
    private fun apply(page: ConversationPage, first: Boolean) {
        shownPages = if (first) 1 else maxOf(shownPages, page.currentPage)
        mutableState.update { current ->
            val known = if (first) mutableSetOf<String>() else (current.conversations + current.requests).map { it.id }.toMutableSet()
            val conversations = if (first) mutableListOf<ChatConversation>() else current.conversations.toMutableList()
            val requests = if (first) mutableListOf<ChatConversation>() else current.requests.toMutableList()
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
    }

    /** The first page on top of the older pages shown: they keep the chats it does not hold, and the next page stays. */
    private fun merge(page: ConversationPage) {
        val fresh = page.data.distinctBy { it.id }
        val ids = fresh.map { it.id }.toSet()
        val (requests, conversations) = fresh.partition { it.request == true }
        mutableState.update { current ->
            current.copy(
                conversations = conversations + current.conversations.filter { it.id !in ids },
                requests = requests + current.requests.filter { it.id !in ids }
            )
        }
    }

    /**
     * Meta line: "Gruppe · 3 Menschen · löscht nach 7 Tagen · wird neu gesichert", or what ended it ("· Mira Sol hat den
     * Chat verlassen", "· Konto gelöscht", from older servers "· beendet").
     */
    fun meta(conversation: ChatConversation): String {
        val parts = mutableListOf(
            if (conversation.isGroup) "Gruppe · ${conversation.members.size} Menschen" else "Zu zweit",
            "löscht nach ${lifetimeLabel(conversation.lifetimeDays)}"
        )
        if (conversation.ended) {
            parts += conversation.endedSummary ?: "beendet"
        } else if (conversation.rotationRequired) {
            parts += "wird neu gesichert"
        }
        return parts.joinToString(" · ")
    }

    /** The row's title: group name or the other people's names. */
    fun title(conversation: ChatConversation): String = conversation.displayTitle(context.userId)

    /** „Chats sperren“. */
    fun lock() = vault.lock()

    /** The confirmation toast was shown. */
    fun dismissStatus() = mutableState.update { it.copy(status = null) }

    /** The error toast was shown. */
    fun dismissActionError() = mutableState.update { it.copy(actionError = null) }

    // region Row actions (swipes, „⋯“ and TalkBack: docs/oasis-chat-client.md, „Gestures“)

    /** Line 2: the last message this device decrypted in this session, else „Verschlüsselte Nachricht“. */
    fun preview(conversation: ChatConversation): String = vault.previews[conversation.id] ?: ENCRYPTED_PREVIEW

    /** The last activity: „14:05“, „Gestern“, „Montag“ or „02.11.26“; empty without one. */
    fun activity(conversation: ChatConversation): String =
        conversation.lastActivityAt?.let(OasisJson::parseInstant)?.let { ChatLayout.activityLabel(it, vault.clock.now()) }.orEmpty()

    /** Whether one may leave: everyone except a group's owner (who removes members instead). */
    fun canLeave(conversation: ChatConversation): Boolean = !(conversation.isGroup && conversation.createdBy == myId)

    /** „Als gelesen markieren“. */
    suspend fun markRead(conversation: ChatConversation) = act("Als gelesen markiert.") {
        context.repository.markRead(conversation.id)
        replace(conversation.copy(unread = false))
    }

    /** „Stummschalten“ (permanently, like „Dauerhaft“ in the details) or „Ton an“. */
    suspend fun toggleMute(conversation: ChatConversation) =
        act(if (conversation.muted == true) "Ton ist wieder an." else "Stummgeschaltet.") {
            val changed = if (conversation.muted == true) {
                context.repository.unmute(conversation.id)
            } else {
                context.repository.mute(conversation.id, "always")
            }
            replace(conversation.copy(muted = changed.muted, mutedUntil = changed.mutedUntil))
        }

    /** „Chat verlassen“ (after the confirmation): it leaves the list. */
    suspend fun leave(conversation: ChatConversation) = act("Chat verlassen.") {
        context.repository.leave(conversation.id)
        mutableState.update {
            it.copy(conversations = it.conversations.without(conversation), requests = it.requests.without(conversation))
        }
    }

    private fun replace(conversation: ChatConversation) = mutableState.update {
        it.copy(conversations = it.conversations.replacing(conversation), requests = it.requests.replacing(conversation))
    }

    private fun List<ChatConversation>.replacing(conversation: ChatConversation) = map {
        if (it.id ==
            conversation.id
        ) {
            conversation
        } else {
            it
        }
    }

    private fun List<ChatConversation>.without(conversation: ChatConversation) = filter { it.id != conversation.id }

    /** Runs a row action; its result is announced („Stummgeschaltet.“), a failure shows as the error. */
    private suspend fun act(done: String, action: suspend () -> Unit) = inScope {
        try {
            action()
            mutableState.update { it.copy(status = done) }
        } catch (error: Exception) {
            mutableState.update { it.copy(actionError = describeChatError(error)) }
        }
    }

    // endregion

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
     * While the list is shown: the first page again on new chat activity (`private-inbox.{id}`, `InboxActivity` kind
     * "chat"), polling while offline, and the link requests every few seconds (like the web).
     */
    suspend fun liveUpdates(): Nothing = coroutineScope {
        realtime.subscribe("private-inbox.${context.userId}")
            .filter { it.string("kind") == "chat" }
            .onEach { reloadFirstPage() }
            .launchIn(this)
        tick()
    }

    /** Every few seconds: the link requests, and the first page every fifth time while offline. */
    private suspend fun tick(): Nothing {
        var ticks = 0
        while (true) {
            delay(4.seconds)
            ticks += 1
            refreshLinkRequests()
            if (!realtime.isConnected.value && ticks % 5 == 0) reloadFirstPage()
        }
    }

    private companion object {
        /** How long „Später“ hides the backup row. */
        val BACKUP_SNOOZE: Duration = Duration.ofDays(14)

        /** The list shows at most two people to write first. */
        const val MAX_SUGGESTIONS = 2
    }
}
