package de.velwebsolutions.oasis.chat.ui

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.PublicUser
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatKeyring
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.core.ChatSafety
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.core.describeChatError
import de.velwebsolutions.oasis.chat.core.formatDate
import de.velwebsolutions.oasis.chat.core.lifetimeLabel
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** The safety banner: the flagged message and the texts about it. */
data class SafetyFlag(val item: ChatMessageItem, val title: String, val text: String)

/** What the conversation screen shows. */
data class ConversationState(
    val conversation: ChatConversation? = null,
    val title: String = "",
    val lifetimeLine: String = "",
    val timeline: ChatTimeline = ChatTimeline(),
    val notices: List<String> = emptyList(),
    val nextBeforeId: String? = null,
    val loadFailed: String? = null,
    /** The member's access ended (403/404): the screen goes back to the list. */
    val closed: Boolean = false,
    val draft: String = "",
    val composerError: String? = null,
    val hint: String? = null,
    /** Read out by TalkBack only ("Gesendet.", "Neue Nachricht von …"): the bubble itself is the visible feedback. */
    val spokenNotice: String? = null,
    /** The confirmation of a request or block action (toast). */
    val status: String? = null,
    /** The error of a request or block action (toast); sending errors stay at the composer. */
    val actionError: String? = null,
    /** Safety banner for the newest flagged message from someone else. */
    val flagged: SafetyFlag? = null,
    /** Composer enabled: may participate, key active and present. */
    val canSend: Boolean = false,
    /** "12 / 4000" or "Zu lang". */
    val counter: String = "0 / ${ChatCrypto.MAX_MESSAGE_LENGTH}",
    val tooLong: Boolean = false,
    /** Message request banner title, while this member has not accepted the chat. */
    val requestTitle: String? = null,
    val isOwner: Boolean = false
)

/**
 * Screen "conversation" (ui/conversation-view.js; iOS `ConversationModel`): read and write one chat.
 *
 * On open: load the conversation (syncs the server clock), verify the members' keys, open my grants; as owner of a
 * pending key create and distribute a new one; load the newest history; listen on `private-conversation.{id}` for
 * `MessageSent` / `KeyRotated` (polling while the socket is down). Every `KeyRotated` and every 409 repeats the key
 * steps. Plaintext lives only in the in-memory timeline; expired messages are removed every 15 s.
 */
@HiltViewModel(assistedFactory = ConversationViewModel.Factory::class)
class ConversationViewModel @AssistedInject constructor(
    @Assisted val conversationId: String,
    context: ChatContext,
    private val realtime: RealtimeService
) : ChatViewModel(context) {
    @AssistedFactory
    interface Factory {
        fun create(conversationId: String): ConversationViewModel
    }

    private val mutableState = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = mutableState.asStateFlow()

    private var conversation: ChatConversation? = null
    private var timeline = ChatTimeline()
    private var notices: List<String> = emptyList()
    private var nextBeforeId: String? = null
    private var users: Map<String, PublicUser> = emptyMap()
    private var warnings: List<String> = emptyList()
    private val dismissed = mutableSetOf<String>()
    private var confirmedDraft: String? = null
    private var flagged: SafetyFlag? = null

    /** Increases on every open/close; results of older work are dropped. */
    private var openCount = 0
    private val jobs = mutableListOf<Job>()

    var sweepInterval: Duration = 15.seconds
    var pollInterval: Duration = 5.seconds

    private val myId: String get() = context.userId

    fun senderName(userId: String): String = users[userId]?.name ?: "Unbekannt"

    /** Recomputes the derived fields after a change of the conversation, the keys or the timeline. */
    private fun render(change: (ConversationState) -> ConversationState = { it }) {
        val current = conversation
        val measured = ChatMessages.measure(mutableState.value.draft)
        mutableState.value = change(
            mutableState.value.copy(
                conversation = current,
                title = current?.displayTitle(myId).orEmpty(),
                lifetimeLine = current?.let {
                    "Ende-zu-Ende verschlüsselt · Nachrichten löschen sich nach ${lifetimeLabel(it.lifetimeDays)}"
                }.orEmpty(),
                timeline = timeline,
                notices = notices,
                nextBeforeId = nextBeforeId,
                flagged = flagged,
                canSend = current != null && context.canParticipate && !current.rotationRequired &&
                    vault.conversationKey(current.id, current.keyVersion) != null,
                counter = if (measured.tooLong) "Zu lang" else "${measured.characters} / ${ChatCrypto.MAX_MESSAGE_LENGTH}",
                tooLong = measured.tooLong,
                requestTitle = current?.takeIf { it.request == true }?.let { conversation ->
                    val others = conversation.members.filter { it.userId != myId }
                    "Nachrichtenanfrage von ${if (others.size == 1) others[0].name else "Jemand"}"
                },
                isOwner = current?.createdBy == myId
            )
        )
    }

    // region Lifecycle

    /** Open the chat and start listening. */
    suspend fun open() = inScope {
        close()
        val token = openCount
        try {
            refresh()
            if (!isCurrent(token)) return@inScope
            loadHistory()
            if (!isCurrent(token)) return@inScope
            markRead()
            jobs += viewModelScope.launch {
                realtime.subscribe("private-conversation.$conversationId").collect { handle(it) }
            }
            startTimers(token)
        } catch (error: Exception) {
            handleFailure(error)
        }
    }

    /** Leave the chat: stop listening and forget its plaintext, notices and the unsent draft. */
    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        openCount += 1
        timeline = ChatTimeline()
        conversation = null
        nextBeforeId = null
        notices = emptyList()
        flagged = null
        dismissed.clear()
        confirmedDraft = null
        render { it.copy(draft = "", hint = null, counter = "0 / ${ChatCrypto.MAX_MESSAGE_LENGTH}", tooLong = false) }
    }

    private fun isCurrent(token: Int): Boolean = token == openCount && vault.unlocked

    private fun startTimers(token: Int) {
        jobs += viewModelScope.launch {
            while (true) {
                delay(sweepInterval)
                if (!isCurrent(token)) return@launch
                // Only a timeline in which something expired is published, so the screen does not redraw for nothing.
                timeline.droppingExpired(vault.clock.now())?.let {
                    timeline = it
                    checkSafety()
                    render()
                }
            }
        }
        // Fallback while Reverb is not connected: fetch the newest page (and key changes) regularly.
        jobs += viewModelScope.launch {
            while (true) {
                delay(pollInterval)
                if (!isCurrent(token)) return@launch
                if (!realtime.isConnected.value) poll()
            }
        }
    }

    /** Steps 1–2: conversation, directory, grants and – as owner – a pending key. */
    suspend fun refresh() {
        val token = openCount
        var current = context.repository.conversation(conversationId)
        vault.clock.sync(current.serverTime)
        val keyring = context.keyring
        val directory = keyring.loadDirectory(conversationId)
        users = directory.users.associateBy { it.id }
        keyring.openGrants(current)
        val newWarnings = keyWarnings(current, directory).toMutableList()
        if (current.rotationRequired && current.createdBy == myId) {
            try {
                val request = current.request
                current = keyring.distributeKey(current).copy(request = request)
                vault.clock.sync(current.serverTime)
            } catch (error: Exception) {
                newWarnings += describeChatError(error)
            }
        }
        if (!isCurrent(token)) return
        warnings = newWarnings
        conversation = current
        renderNotices(current)
        redecrypt()
        render()
    }

    private fun renderNotices(current: ChatConversation) {
        val texts = warnings.toMutableList()
        if (!context.canParticipate) {
            texts.add(0, "Dein Konto ist gerade nur zum Lesen. Mit einer Mitgliedschaft kannst du wieder schreiben.")
        }
        if (current.rotationRequired && current.createdBy != myId) {
            texts.add(
                0,
                "Der Chat bekommt einen neuen Schlüssel. Sobald die Person, die ihn gestartet hat, online ist, kannst du schreiben."
            )
        } else if (vault.conversationKey(current.id, current.keyVersion) == null) {
            texts.add(0, "Für diesen Chat liegt dir noch kein Schlüssel vor. Du kannst noch nicht schreiben.")
        }
        notices = texts
    }

    /** Warnings about replaced identities, keys changed during this session and invalid bundles. */
    internal fun keyWarnings(conversation: ChatConversation, directory: ChatKeyring.Directory): List<String> {
        val names = directory.users.associate { it.id to it.name }
        val result = mutableListOf<String>()
        for (member in conversation.members) {
            if (member.userId == myId) continue
            val changed = directory.user(member.userId)?.keyChangedAt ?: continue
            result += "${names.getValue(member.userId)} hat am ${formatDate(changed)} neue Chat-Schlüssel erstellt. " +
                "Vergleicht eure Sicherheitsnummer erneut."
        }
        for (id in directory.changedInSession) {
            result += "Die Schlüssel von ${names.getValue(id)} haben sich gerade geändert. " +
                "Vergleicht eure Sicherheitsnummer, bevor du Vertrauliches schreibst."
        }
        for (id in directory.invalid) {
            result += "Die Schlüssel von ${names.getValue(id)} sind ungültig. Diese Person bekommt keinen Schlüssel für den Chat."
        }
        return result
    }

    /** Step 3: the next 50 older messages ("Ältere Nachrichten laden"). */
    private suspend fun loadHistory() {
        val token = openCount
        val page = context.repository.messages(conversationId, nextBeforeId)
        if (!isCurrent(token)) return
        val items = page.data.reversed().map { ChatMessages.open(it.envelope, conversationId, vault, context.crypto) }
        timeline = timeline.prepending(items)
        nextBeforeId = page.nextBeforeId
        checkSafety()
        render()
    }

    suspend fun loadOlder() = inScope {
        try {
            loadHistory()
        } catch (error: Exception) {
            handleFailure(error)
        }
    }

    /** Polling fallback: key changes and the newest page. */
    internal suspend fun poll() {
        val token = openCount
        try {
            val fresh = context.repository.conversation(conversationId)
            val known = conversation
            if (known != null && (fresh.keyVersion != known.keyVersion || fresh.rotationRequired != known.rotationRequired)) refresh()
            val page = context.repository.messages(conversationId)
            if (!isCurrent(token)) return
            page.data.reversed().forEach { receive(it.envelope) }
        } catch (error: Exception) {
            handleFailure(error)
        }
    }

    // endregion

    // region Realtime

    /** The two events of `private-conversation.{id}` (their `broadcastAs()` names): the envelope itself, or a key change. */
    fun handle(event: RealtimeEvent) {
        when (event.name.removePrefix(".")) {
            "MessageSent" -> {
                val envelope =
                    runCatching { EventJson.decodeFromJsonElement(MessageEnvelope.serializer(), event.data) }.getOrNull() ?: return
                receive(envelope)
            }

            "KeyRotated" -> viewModelScope.launch {
                try {
                    refresh()
                } catch (error: Exception) {
                    handleFailure(error)
                }
            }
        }
    }

    /** A new envelope (realtime, poll or own send). */
    internal fun receive(envelope: MessageEnvelope) {
        val current = conversation ?: return
        if (envelope.conversationId != current.id) return
        val item = ChatMessages.open(envelope, current.id, vault, context.crypto) ?: return
        timeline = timeline.appending(item) ?: return
        checkSafety()
        render()
        if (item.senderId != myId) {
            render { it.copy(spokenNotice = "Neue Nachricht von ${senderName(item.senderId)}.") }
            viewModelScope.launch { markRead() }
        }
    }

    /** New keys may make earlier "no key" messages readable. */
    private fun redecrypt() {
        for (item in timeline.items) {
            if (item.state != ChatMessageItem.State.NoKey) continue
            val reopened = ChatMessages.open(item.envelope, conversationId, vault, context.crypto) ?: continue
            if (reopened.state != ChatMessageItem.State.NoKey) timeline = timeline.replacing(reopened)
        }
        checkSafety()
    }

    /** Tell the server the chat was seen (unread badge, e-mail/push notices). Failures only leave the badge stale. */
    private suspend fun markRead() {
        runCatching { context.repository.markRead(conversationId) }
    }

    // endregion

    // region Sending

    /** Typing: tone hint; a pending sensitive-data warning ends when the text changes; counts as interaction (idle lock). */
    fun setDraft(value: String) {
        vault.touch()
        if (confirmedDraft != null && confirmedDraft != value.trim()) confirmedDraft = null
        val tone = ChatSafety.scanOutgoing(value, vault.safety.tone).tone
        mutableState.value = mutableState.value.copy(
            draft = value,
            hint = tone?.let { "Kurzer Hinweis: „$it“ wirkt schnell herablassend. Bitte achte auf einen freundlichen Ton." }
        )
        render()
    }

    /**
     * "Senden". Sensitive data needs a second press with the same text. Text typed while the message is sealed and sent
     * stays in the composer.
     */
    suspend fun send() = inScope {
        val typed = state.value.draft
        val text = typed.trim()
        val current = conversation ?: return@inScope
        if (text.isEmpty() || ChatMessages.measure(text).tooLong || !state.value.canSend) return@inScope
        render { it.copy(composerError = null) }
        val sensitive = ChatSafety.scanOutgoing(text, vault.safety.tone).sensitive
        if (sensitive.isNotEmpty() && confirmedDraft != text) {
            confirmedDraft = text
            render {
                it.copy(
                    hint = "Du bist dabei, ${sensitive.joinToString(" und ")} zu verschicken. $SENSITIVE_ADVICE"
                )
            }
            return@inScope
        }
        try {
            val envelope = ChatMessages.seal(text, current, vault, context.crypto)
            context.repository.sendMessage(current.id, envelope)
            confirmedDraft = null
            setDraft(remainder(state.value.draft, typed))
            receive(envelope)
            // Replying accepts a message request.
            if (current.request == true) acceptLocally()
            render { it.copy(spokenNotice = "Gesendet.") }
        } catch (error: Exception) {
            render { it.copy(composerError = describeChatError(error)) }
            if (error is ApiError.Conflict) {
                try {
                    refresh()
                } catch (refreshError: Exception) {
                    handleFailure(refreshError)
                }
            }
        }
    }

    // endregion

    // region Safety banner, requests, blocking

    private fun checkSafety() {
        val incoming = timeline.items.filter { it.senderId != myId && it.state == ChatMessageItem.State.Readable }
        for (item in incoming.reversed()) {
            if (item.signature in dismissed) continue
            val finding = ChatSafety.scanIncoming(item.plaintext, vault.safety.lists) ?: continue
            val name = senderName(item.senderId)
            val early = incoming.count { it.senderId == item.senderId } <= 3
            val advice = when (finding.kind) {
                ChatSafety.Kind.Phishing ->
                    "$name fragt nach Passwort, Bank- oder Kartendaten oder deiner Adresse. Gib so etwas nie im Chat heraus – Oasis fragt dich nie danach."

                ChatSafety.Kind.Solicitation ->
                    "Die Nachricht von $name wirkt wie ein sexuelles Angebot oder eine sexuelle Anfrage. Das verstößt gegen unsere Regeln."

                ChatSafety.Kind.Harassment ->
                    "Die Nachricht von $name wirkt beleidigend oder bedrohlich. Du musst dir das nicht gefallen lassen."
            }
            flagged = SafetyFlag(
                item,
                "${finding.title} $name blockieren und Chat löschen?",
                if (early) "$advice Gerade in den ersten Nachrichten ist das ein typisches Warnzeichen." else advice
            )
            return
        }
        flagged = null
    }

    /** "Ist in Ordnung" (remembered in memory only). */
    fun dismissFlagged() {
        flagged?.let { dismissed += it.item.signature }
        flagged = null
        render()
    }

    /**
     * "Blockieren und Chat löschen": block the sender, leave (a group owner cannot – the block alone stops the messages).
     * Returns true when the screen should go back to the list.
     */
    suspend fun blockAndLeave(senderId: String): Boolean = inScope {
        try {
            context.repository.block(senderId)
            try {
                context.repository.leave(conversationId)
            } catch (_: ApiError.Validation) {
                // A group owner stays; the block alone stops the messages.
            }
            vault.announcement.value = "Blockiert. Der Chat ist gelöscht."
            true
        } catch (error: Exception) {
            render { it.copy(actionError = describeChatError(error)) }
            false
        }
    }

    /** The request is accepted (by answering or "Annehmen"): a normal chat from now on, unless it was closed meanwhile. */
    private fun acceptLocally() {
        conversation = conversation?.copy(request = false)
    }

    /** "Annehmen". */
    suspend fun acceptRequest() = inScope {
        try {
            context.repository.accept(conversationId)
            acceptLocally()
            render { it.copy(status = "Angenommen. Das ist jetzt ein normaler Chat.") }
        } catch (error: Exception) {
            render { it.copy(actionError = describeChatError(error)) }
        }
    }

    /** "Ablehnen und löschen" / "Blockieren": leave, optionally block the person who started the chat. */
    suspend fun declineRequest(block: Boolean): Boolean = inScope {
        val current = conversation ?: return@inScope false
        try {
            if (block) context.repository.block(current.createdBy)
            context.repository.leave(conversationId)
            vault.announcement.value = if (block) "Blockiert und gelöscht." else "Anfrage gelöscht. Die Person erfährt davon nichts."
            true
        } catch (error: Exception) {
            render { it.copy(actionError = describeChatError(error)) }
            false
        }
    }

    fun dismissStatus() = render { it.copy(status = null) }

    fun dismissActionError() = render { it.copy(actionError = null) }

    fun noticeSpoken() = render { it.copy(spokenNotice = null) }

    /** The report dialog for one message from someone else. */
    fun reportModel(item: ChatMessageItem): ReportModel = ReportModel(context, conversationId, item, senderName(item.senderId))

    // endregion

    private fun handleFailure(error: Throwable) {
        if (error is ApiError.Forbidden || error is ApiError.NotFound) {
            vault.announcement.value = describeChatError(error)
            close()
            render { it.copy(closed = true) }
            return
        }
        render {
            if (conversation == null) it.copy(loadFailed = describeChatError(error)) else it.copy(composerError = describeChatError(error))
        }
    }

    override fun onCleared() {
        close()
    }

    companion object {
        private val EventJson = Json { ignoreUnknownKeys = true }

        private const val SENSITIVE_ADVICE =
            "Oasis fragt dich nie im Chat danach – sei vorsichtig, besonders bei Menschen, die du noch nicht kennst. Zum Senden tippe noch einmal auf „Senden“."

        /** The composer after `sent` went out: what was typed meanwhile stays; a changed draft stays as it is. */
        fun remainder(draft: String, sent: String): String {
            if (!draft.startsWith(sent)) return draft
            return draft.substring(sent.length).trimStart()
        }
    }
}
