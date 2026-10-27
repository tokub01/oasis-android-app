package de.velwebsolutions.oasis.chat.ui

import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.chat.api.AttachmentState
import de.velwebsolutions.oasis.chat.api.ChatAvatar
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.PublicUser
import de.velwebsolutions.oasis.chat.core.AndroidChatMedia
import de.velwebsolutions.oasis.chat.core.AttachmentProblem
import de.velwebsolutions.oasis.chat.core.AttachmentTexts
import de.velwebsolutions.oasis.chat.core.AttachmentView
import de.velwebsolutions.oasis.chat.core.ChatAttachments
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatKeyring
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.core.ChatSafety
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.core.DraftAttachment
import de.velwebsolutions.oasis.chat.core.describeChatError
import de.velwebsolutions.oasis.chat.core.formatDate
import de.velwebsolutions.oasis.chat.core.lifetimeLabel
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import de.velwebsolutions.oasis.core.share.PendingShare
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

/** The open view-once file: shown full screen from memory until it is closed, then reported as viewed. */
data class ViewOnceViewer(val item: ChatMessageItem, val attachment: MessageAttachment, val view: AttachmentView)

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
    val isOwner: Boolean = false,
    /** Files picked or recorded for the next message. */
    val drafts: List<DraftAttachment> = emptyList(),
    /** A picked file is being checked and prepared. */
    val preparing: Boolean = false,
    /** Seconds of the voice message being recorded; null while not recording. */
    val recordingSeconds: Int? = null,
    /** Share of the files uploaded (0 … 1) while a message with files is sent. */
    val uploadProgress: Float? = null,
    /** Decrypted files of the shown messages by attachment id. */
    val attachmentViews: Map<String, AttachmentView> = emptyMap(),
    val viewOnce: ViewOnceViewer? = null
) {
    /** Room for another file in the composer, and nothing else going on. */
    val canAttach: Boolean
        get() = canSend && drafts.size < ChatAttachments.MAX_PER_MESSAGE && recordingSeconds == null && uploadProgress == null && !preparing

    /** "Senden" is possible: text or files, within the limits, not while recording or uploading. */
    val canSubmit: Boolean
        get() = canSend && (draft.isNotBlank() || drafts.isNotEmpty()) && !tooLong && recordingSeconds == null && uploadProgress == null
}

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
    /** Creates the view model for one conversation (Hilt). */
    @AssistedFactory
    interface Factory {
        /** The view model of [conversationId]. */
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

    /** How often the recording time advances (a second). */
    var recordingTick: Duration = 1.seconds

    private val media get() = context.media
    private var nextLocalId = 0L
    private var recordingJob: Job? = null

    private val myId: String get() = context.userId

    /** The display name of a sender, "Unbekannt" for someone not in the directory. */
    fun senderName(userId: String): String = users[userId]?.name ?: "Unbekannt"

    /**
     * The avatar beside a sender's messages, as the conversation describes its members (photos of an accepted chat); the
     * "?" avatar of someone no longer in it.
     */
    fun senderAvatar(userId: String): ChatAvatar = conversation?.avatarOf(userId) ?: ChatAvatar.Unknown

    /** Recomputes the derived fields after a change of the conversation, the keys or the timeline. */
    private fun render(change: (ConversationState) -> ConversationState = { it }) {
        val current = conversation
        val changed = change(mutableState.value)
        val measured = ChatMessages.measure(changed.draft, changed.drafts.size)
        mutableState.value = changed.copy(
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
            takeShare()
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
        stopTicker()
        // A view-once file open while the chat closes (lock, leaving) counts as viewed.
        mutableState.value.viewOnce?.let { open ->
            viewModelScope.launch { runCatching { context.repository.answerAttachment(conversationId, open.attachment.id, VIEWED) } }
        }
        media.clear()
        openCount += 1
        timeline = ChatTimeline()
        conversation = null
        nextBeforeId = null
        notices = emptyList()
        flagged = null
        dismissed.clear()
        confirmedDraft = null
        render {
            it.copy(
                draft = "",
                hint = null,
                drafts = emptyList(),
                preparing = false,
                recordingSeconds = null,
                uploadProgress = null,
                attachmentViews = emptyMap(),
                viewOnce = null
            )
        }
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
        val items = page.data.reversed().map { ChatMessages.open(it.envelope, conversationId, vault, context.crypto, it.attachments) }
        timeline = timeline.prepending(items)
        nextBeforeId = page.nextBeforeId
        checkSafety()
        render()
    }

    /** "Ältere Nachrichten laden". */
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
            page.data.reversed().forEach { receive(it.envelope, it.attachments) }
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

    /**
     * A new envelope (realtime, poll or own send) with the files' states when the server named them; realtime does not,
     * so the newest page is loaded for them.
     */
    internal fun receive(envelope: MessageEnvelope, states: List<AttachmentState>? = null) {
        val current = conversation ?: return
        if (envelope.conversationId != current.id) return
        if (states != null) updateStates(envelope.signature, states)
        val item = ChatMessages.open(envelope, current.id, vault, context.crypto, states.orEmpty()) ?: return
        timeline = timeline.appending(item) ?: return
        if (states == null && item.content.attachments.isNotEmpty()) viewModelScope.launch { reloadStates() }
        checkSafety()
        render()
        if (item.senderId != myId) {
            render { it.copy(spokenNotice = "Neue Nachricht von ${senderName(item.senderId)}.") }
            viewModelScope.launch { markRead() }
        }
    }

    /** The files' states of a shown message, as the server names them now. */
    private fun updateStates(signature: String, states: List<AttachmentState>) {
        val known = timeline.items.firstOrNull { it.signature == signature } ?: return
        val mapped = states.associateBy { it.id }
        if (known.attachmentStates == mapped) return
        timeline = timeline.replacing(known.copy(attachmentStates = mapped))
        render()
    }

    /** States of the newest messages (after a realtime message with files). Failures leave them as they are. */
    private suspend fun reloadStates() {
        val page = runCatching { context.repository.messages(conversationId) }.getOrNull() ?: return
        page.data.forEach { updateStates(it.envelope.signature, it.attachments) }
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
        val drafts = state.value.drafts
        if (!state.value.canSubmit) return@inScope
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
            val references = upload(current.id, drafts)
            val plaintext = MessagePayload.encode(MessageContent(text, references))
            val envelope = ChatMessages.seal(plaintext, current, vault, context.crypto)
            val stored = context.repository.sendMessage(current.id, envelope, references.map { it.id })
            confirmedDraft = null
            // What was picked stays on screen without a download; view-once files are not shown to the sender again.
            val shown = drafts.zip(references).filter { !it.first.viewOnce }.associate { (draft, reference) ->
                reference.id to media.present(draft.kind, draft.data, draft.mime, draft.name)
            }
            render { it.copy(drafts = it.drafts - drafts.toSet(), uploadProgress = null, attachmentViews = it.attachmentViews + shown) }
            setDraft(remainder(state.value.draft, typed))
            receive(envelope, stored.attachments)
            // Replying accepts a message request.
            if (current.request == true) acceptLocally()
            render { it.copy(spokenNotice = "Gesendet.") }
        } catch (error: Exception) {
            render { it.copy(composerError = describeChatError(error), uploadProgress = null) }
            if (error is ApiError.Conflict) {
                try {
                    refresh()
                } catch (refreshError: Exception) {
                    handleFailure(refreshError)
                }
            }
        }
    }

    /** Encrypts and uploads the drafts one after the other, with the overall progress. */
    private suspend fun upload(conversationId: String, drafts: List<DraftAttachment>): List<MessageAttachment> {
        if (drafts.isNotEmpty()) render { it.copy(uploadProgress = 0f) }
        return drafts.mapIndexed { index, draft ->
            ChatAttachments.upload(context, conversationId, draft) { part ->
                render { it.copy(uploadProgress = (index + part) / drafts.size) }
            }
        }
    }

    // endregion

    // region Attachments in the composer

    /** Room for one more file; otherwise the composer says why not. */
    private fun roomForAttachment(): Boolean {
        if (state.value.drafts.size < ChatAttachments.MAX_PER_MESSAGE) return true
        render { it.copy(composerError = "Höchstens ${ChatAttachments.MAX_PER_MESSAGE} Anhänge pro Nachricht.") }
        return false
    }

    /** A photo or video from the photo picker, or ([asFile]) any file picked under "Datei". */
    suspend fun addPicked(uri: Uri, asFile: Boolean = false) =
        addDraft { if (asFile) media.prepareFile(uri, it) else media.prepare(uri, it) }

    private suspend fun addDraft(prepare: suspend (localId: Long) -> DraftAttachment) = inScope {
        vault.touch()
        if (!roomForAttachment()) return@inScope
        render { it.copy(preparing = true, composerError = null) }
        try {
            val draft = prepare(++nextLocalId)
            render { it.copy(drafts = it.drafts + draft) }
        } catch (problem: AttachmentProblem) {
            render { it.copy(composerError = problem.message) }
        } finally {
            render { it.copy(preparing = false) }
        }
    }

    /**
     * What the member shared into this chat under "Teilen mit …": its files become drafts (photos by the photo rules),
     * its text the draft text. Nothing is sent before "Senden"; the cache copies are deleted right after.
     */
    private suspend fun takeShare() {
        val share = PendingShare.take(conversationId) ?: return
        val problems = mutableListOf<String>()
        try {
            for (file in share.files) {
                if (state.value.drafts.size >= ChatAttachments.MAX_PER_MESSAGE) {
                    problems +=
                        "Höchstens ${ChatAttachments.MAX_PER_MESSAGE} Anhänge pro Nachricht. Den Rest kannst du mit der nächsten Nachricht teilen."
                    break
                }
                try {
                    val draft = media.prepareShared(file, ++nextLocalId)
                    render { it.copy(drafts = it.drafts + draft) }
                } catch (problem: AttachmentProblem) {
                    problems += "${file.name}: ${problem.message}"
                }
            }
        } finally {
            PendingShare.delete(share)
        }
        share.text?.let(::setDraft)
        render { it.copy(composerError = problems.firstOrNull()) }
    }

    /** "Anhang entfernen". */
    fun removeDraft(localId: Long) = render { state -> state.copy(drafts = state.drafts.filter { it.localId != localId }) }

    /** "Einmal ansehen" of one draft. */
    fun setViewOnce(localId: Long, value: Boolean) = render { state ->
        state.copy(drafts = state.drafts.map { if (it.localId == localId) it.withViewOnce(value) else it })
    }

    /** The microphone button: starts a voice message (the screen asked for the permission). */
    fun startRecording() {
        vault.touch()
        if (state.value.recordingSeconds != null || !roomForAttachment()) return
        if (!media.startRecording(onLimit = { viewModelScope.launch { finishRecording() } })) {
            render { it.copy(composerError = "Die Aufnahme ließ sich nicht starten. Versuch es noch einmal.") }
            return
        }
        render { it.copy(recordingSeconds = 0, composerError = null) }
        recordingJob = viewModelScope.launch {
            var seconds = 0
            while (true) {
                delay(recordingTick)
                seconds += 1
                render { it.copy(recordingSeconds = seconds) }
            }
        }
    }

    /** "Fertig" (or the 3-minute limit): the voice message joins the drafts. */
    suspend fun finishRecording() = inScope {
        if (state.value.recordingSeconds == null) return@inScope
        stopTicker()
        val draft = media.finishRecording(++nextLocalId)
        render {
            if (draft == null) {
                it.copy(recordingSeconds = null, composerError = "Die Aufnahme ist leer. Versuch es noch einmal.")
            } else {
                it.copy(recordingSeconds = null, drafts = it.drafts + draft)
            }
        }
    }

    /** "Abbrechen" while recording. */
    fun cancelRecording() {
        stopTicker()
        media.cancelRecording()
        render { it.copy(recordingSeconds = null) }
    }

    private fun stopTicker() {
        recordingJob?.cancel()
        recordingJob = null
    }

    // endregion

    // region Attachments in messages

    /**
     * Downloads, decrypts and prepares a shown file once (after the member accepted it); [retry] tries a failed one
     * again.
     */
    suspend fun loadAttachment(attachment: MessageAttachment, retry: Boolean = false) = inScope {
        val known = state.value.attachmentViews[attachment.id]
        if (known != null && !(retry && known is AttachmentView.Failed)) return@inScope
        val token = openCount
        setView(attachment.id, AttachmentView.Loading)
        val view = fetch(attachment)
        if (token == openCount) setView(attachment.id, view) else media.discard(view)
    }

    private fun setView(id: String, view: AttachmentView) = render { it.copy(attachmentViews = it.attachmentViews + (id to view)) }

    private suspend fun fetch(attachment: MessageAttachment): AttachmentView = try {
        media.present(
            AttachmentTexts.kind(attachment),
            ChatAttachments.download(context, conversationId, attachment),
            attachment.mime,
            attachment.name
        )
    } catch (error: ApiError) {
        if (error is ApiError.NotFound) {
            AttachmentView.Failed(
                GONE
            )
        } else {
            AttachmentView.Failed("Anhang ließ sich nicht laden.", retry = true)
        }
    } catch (_: Exception) {
        AttachmentView.Failed(AndroidChatMedia.UNVERIFIABLE)
    }

    /** "Annehmen" / "Ablehnen" of a file someone sent: nothing is downloaded before it is accepted. */
    suspend fun answer(item: ChatMessageItem, attachment: MessageAttachment, accept: Boolean) = inScope {
        vault.touch()
        try {
            setState(
                item.signature,
                context.repository.answerAttachment(conversationId, attachment.id, if (accept) "accept" else "decline")
            )
        } catch (error: Exception) {
            render { it.copy(actionError = attachmentError(error)) }
        }
    }

    private fun setState(signature: String, state: AttachmentState) {
        val known = timeline.items.firstOrNull { it.signature == signature } ?: return
        timeline = timeline.replacing(known.copy(attachmentStates = known.attachmentStates + (state.id to state)))
        render()
    }

    /** "Ansehen" of an accepted view-once file: full screen, from memory. */
    suspend fun openViewOnce(item: ChatMessageItem, attachment: MessageAttachment) = inScope {
        vault.touch()
        if (state.value.viewOnce != null) return@inScope
        val token = openCount
        val view = fetch(attachment)
        when {
            token != openCount -> media.discard(view)
            view is AttachmentView.Failed -> render { it.copy(actionError = view.message) }
            else -> render { it.copy(viewOnce = ViewOnceViewer(item, attachment, view)) }
        }
    }

    /**
     * A view-once file no app on the device could open: its copy is deleted, nothing is reported as viewed, so it can
     * be opened again once such an app is installed.
     */
    fun cancelViewOnce() {
        val open = state.value.viewOnce ?: return
        media.discard(open.view)
        render { it.copy(viewOnce = null, actionError = NO_APP) }
    }

    /** Closing the view-once viewer: the file is deleted here and reported as viewed, so the server deletes it too. */
    suspend fun closeViewOnce() = inScope {
        val open = state.value.viewOnce ?: return@inScope
        media.discard(open.view)
        render { it.copy(viewOnce = null) }
        try {
            setState(open.item.signature, context.repository.answerAttachment(conversationId, open.attachment.id, VIEWED))
        } catch (error: Exception) {
            render { it.copy(actionError = attachmentError(error)) }
        }
    }

    private fun attachmentError(error: Exception): String = if (error is ApiError.NotFound) GONE else describeChatError(error)

    // endregion

    // region Safety banner, requests, blocking

    private fun checkSafety() {
        val incoming = timeline.items.filter { it.senderId != myId && it.state == ChatMessageItem.State.Readable }
        for (item in incoming.reversed()) {
            if (item.signature in dismissed) continue
            val finding = ChatSafety.scanIncoming(item.content.text, vault.safety.lists) ?: continue
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

    /** The confirmation toast was shown. */
    fun dismissStatus() = render { it.copy(status = null) }

    /** The error toast was shown. */
    fun dismissActionError() = render { it.copy(actionError = null) }

    /** TalkBack read the notice. */
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

        private const val VIEWED = "viewed"

        private const val GONE = "Diesen Anhang gibt es nicht mehr."

        /** No app on the device opens this kind of file. */
        const val NO_APP = "Auf deinem Gerät ist keine App, die diese Datei öffnen kann."

        private const val SENSITIVE_ADVICE =
            "Oasis fragt dich nie im Chat danach – sei vorsichtig, besonders bei Menschen, die du noch nicht kennst. Zum Senden tippe noch einmal auf „Senden“."

        /** The composer after `sent` went out: what was typed meanwhile stays; a changed draft stays as it is. */
        fun remainder(draft: String, sent: String): String {
            if (!draft.startsWith(sent)) return draft
            return draft.substring(sent.length).trimStart()
        }
    }
}
