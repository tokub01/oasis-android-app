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
import de.velwebsolutions.oasis.chat.core.ChatBackground
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatKeyring
import de.velwebsolutions.oasis.chat.core.ChatKeyringException
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.core.ChatSafety
import de.velwebsolutions.oasis.chat.core.ChatSpam
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.core.DraftAttachment
import de.velwebsolutions.oasis.chat.core.LookChoice
import de.velwebsolutions.oasis.chat.core.TranscriptionProblem
import de.velwebsolutions.oasis.chat.core.describeChatError
import de.velwebsolutions.oasis.chat.core.formatDate
import de.velwebsolutions.oasis.chat.core.lifetimeLabel
import de.velwebsolutions.oasis.chat.data.ChatRepository
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.chatcrypto.MessageReply
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import de.velwebsolutions.oasis.core.share.PendingShare
import de.velwebsolutions.oasis.core.share.Share
import de.velwebsolutions.oasis.core.share.SharedFile
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** The safety banner: the flagged message and the texts about it. */
data class SafetyFlag(val item: ChatMessageItem, val title: String, val text: String)

/** The open view-once file: shown full screen from memory until it is closed, then reported as viewed. */
data class ViewOnceViewer(val item: ChatMessageItem, val attachment: MessageAttachment, val view: AttachmentView)

/** A voice message as text: being made, shown, hidden again, or why it failed. */
sealed interface Transcript {
    /** „Wird umgewandelt …“. */
    data object Working : Transcript

    data class Shown(val text: String) : Transcript

    data class Hidden(val text: String) : Transcript

    data class Failed(val message: String) : Transcript
}

/** One playing of an effect; [id] tells two playings of the same effect apart. */
data class EffectPlay(val key: String, val id: Int)

/** The German names of the send effects, in the order of the menu (ui/effects.js `EFFECT_NAMES`). */
object EffectNames {
    val all: List<Pair<String, String>> = listOf(
        "konfetti" to "Konfetti",
        "herzen" to "Herzen",
        "ballons" to "Ballons",
        "feuerwerk" to "Feuerwerk"
    )

    fun of(key: String): String = all.first { it.first == key }.second
}

/**
 * One reaction chip under a bubble: „{emoji} {count}“, whether the own reaction is among them, and who reacted
 * („Mira, Du“: names as in the chat, „Du“ last).
 */
data class ReactionChip(val emoji: String, val count: Int, val mine: Boolean, val names: String) {
    /** „👍, 2 Reaktionen“. */
    val description: String get() = "$emoji, $count ${if (count == 1) "Reaktion" else "Reaktionen"}"
}

/**
 * The quote of a reply inside its bubble: whose message ([name], „Du“ for one's own) and what it said; [gone] once the
 * original's expiry passed (then only „Nachricht nicht mehr verfügbar“, not tappable).
 */
data class ReplyQuote(val to: String, val name: String, val text: String, val gone: Boolean)

/** The quote bar above the composer while a reply is being written. */
data class ReplyBar(val title: String, val text: String)

/** The texts of a reply's quote by kind (conversation-view.js `QUOTE_LABELS`). */
object QuoteTexts {
    private val labels = mapOf(
        "image" to "Foto",
        "audio" to "Sprachnachricht",
        "video" to "Video",
        "file" to "Datei",
        "once" to "Einmal ansehen"
    )

    /** The excerpt of a text, or what the message held. */
    fun of(reply: MessageReply): String = if (reply.kind == "text") reply.excerpt else labels.getValue(reply.kind)

    /** What a file of [kind] is called („Foto“ …); empty for an unknown kind. */
    fun label(kind: String): String = labels[kind].orEmpty()

    const val GONE = "Nachricht nicht mehr verfügbar"
    const val NOT_LOADED = "Die Nachricht ist nicht geladen. Ältere Nachrichten laden, um sie zu sehen."
}

/**
 * An informational line in the timeline (docs/oasis-chat-client.md, „C9 Quiet notices“) at the moment it happened
 * ([at]); null, or before the loaded messages: at the end of what is loaded.
 */
data class QuietNotice(val text: String, val at: Instant?)

/** What the conversation screen shows. */
data class ConversationState(
    val conversation: ChatConversation? = null,
    val title: String = "",
    val timeline: ChatTimeline = ChatTimeline(),
    /** Banners where the member must act or cannot write (read-only, ended, no access yet, a failed step). */
    val notices: List<String> = emptyList(),
    /** Small lines in the timeline where something happened (a member's new device …); a tap opens the details. */
    val quietNotices: List<QuietNotice> = emptyList(),
    /** „Tipp: Nachricht nach rechts wischen zum Antworten“ above the composer, until „Verstanden“. */
    val swipeHint: Boolean = false,
    /** The newest received message, which moves right and back once with the hint; null once it did. */
    val nudge: String? = null,
    val nextBeforeId: String? = null,
    val loadFailed: String? = null,
    /** The member's access ended (403/404): the screen goes back to the list. */
    val closed: Boolean = false,
    /** What is typed, exactly as typed (the field keeps it; only sending trims). */
    val draft: String = "",
    /** Counts the view model's own changes of [draft] (sent, shared in, left): only those are written into the field. */
    val draftRevision: Int = 0,
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
    /** Composer enabled: may participate, chat not ended, owner trusted, key active and present. */
    val canSend: Boolean = false,
    /**
     * The owner the server names is no active owner member or another one than before ([ChatKeyring.ownerTrusted]): the
     * screen shows [ChatKeyring.UNTRUSTED_OWNER] instead of the composer.
     */
    val untrustedOwner: Boolean = false,
    /** „3.120 / 4.000“ from 75 % of the maximum, „Höchstens 4.000 Zeichen“ at it; "" below ([DraftLimit.counter]). */
    val counter: String = "",
    /** The draft is at or over the limit: the counter in the warning colour. */
    val atLimit: Boolean = false,
    val tooLong: Boolean = false,
    /** What the field may take with the files, effect and quote of the next message. */
    val limit: DraftLimit = DraftLimit(),
    /** Message request banner title, while this member has not accepted the chat. */
    val requestTitle: String? = null,
    val isOwner: Boolean = false,
    /** Files picked or recorded for the next message. */
    val drafts: List<DraftAttachment> = emptyList(),
    /** „Einmal ansehen“ of the „+“ sheet: every file of the next message is view-once (the chip above the field). */
    val viewOnceNext: Boolean = false,
    /** A picked file is being checked and prepared. */
    val preparing: Boolean = false,
    /** Seconds of the voice message being recorded; null while not recording. */
    val recordingSeconds: Int? = null,
    /** Share of the files uploaded (0 … 1) while a message with files is sent. */
    val uploadProgress: Float? = null,
    /** Decrypted files of the shown messages by attachment id. */
    val attachmentViews: Map<String, AttachmentView> = emptyMap(),
    val viewOnce: ViewOnceViewer? = null,
    /** German speech can be turned into text on this device itself ([de.velwebsolutions.oasis.chat.core.VoiceTranscriber]). */
    val canTranscribe: Boolean = false,
    /** Voice messages as text by attachment id; in memory only, gone when the chat closes or locks. */
    val transcripts: Map<String, Transcript> = emptyMap(),
    /** The personal background of this chat on this device. */
    val look: LookChoice = LookChoice(ChatBackground.Standard, "default"),
    /** „Effekte abspielen“ on this device. */
    val effectsOn: Boolean = true,
    /** An effect chosen with nothing to send yet: the chip „Effekt: …“ above the field, for the next message. */
    val pendingEffect: String? = null,
    /** The effect playing over the chat right now. */
    val playing: EffectPlay? = null,
    /** The reaction chips by target signature, in the order of [MessagePayload.REACTIONS]. */
    val reactions: Map<String, List<ReactionChip>> = emptyMap(),
    /** The quotes of replies by the reply's signature. */
    val quotes: Map<String, ReplyQuote> = emptyMap(),
    /** The quote bar above the composer while answering a message. */
    val replyBar: ReplyBar? = null,
    /** „Angeheftet: …“ at the top of the chat. */
    val pinBar: PinBar? = null,
    /** A label on the round send button: „Trotzdem senden“ after a spam hint, „Noch 27 s“ during a pause; null: the icon. */
    val sendLabel: String? = null,
    /** The empty field's text and hint: „Nachricht – bis zu 4.000 Zeichen“ with the current maximum. */
    val placeholder: String = DraftLimit.placeholder(DraftLimit.DEFAULT_MAX),
    /** Sending pauses after flooding (ChatSpam), with the countdown in [sendLabel]. */
    val paused: Boolean = false,
    /** „Anheften“ is offered: both members of a 1:1 chat, a group's owner. */
    val canPin: Boolean = false,
    /** The polls as they stand, by the poll's signature. */
    val polls: Map<String, PollView> = emptyMap(),
    /** The poll form above the field („Umfrage“). */
    val pollForm: PollForm? = null,
    /** The header bar's line under the name: a 1:1 partner's status, else the encryption and the lifetime. */
    val headerStatus: String = "",
    /** The message list: day pills, the „Neue Nachrichten“ divider and the bubbles with their place in a run. */
    val rows: List<TimelineRow> = emptyList()
) {
    /** The chip text of [pendingEffect]. */
    val effectChip: String? get() = pendingEffect?.let { "Effekt: ${EffectNames.of(it)}" }

    /** Room for another file in the composer, and nothing else going on. */
    val canAttach: Boolean
        get() = canSend && drafts.size < ChatAttachments.MAX_PER_MESSAGE && recordingSeconds == null && uploadProgress == null && !preparing

    /** The round button says „Senden“ (something is written or attached); otherwise it records a voice message. */
    val showsSend: Boolean
        get() = draft.isNotBlank() || drafts.isNotEmpty()

    /** The messages shown as bubbles: every item except reactions (they show as chips under their target). */
    val bubbles: List<ChatMessageItem> get() = timeline.items.filter { !it.content.control }

    /** The rows shown: as the view model laid them out, else (a state built without it) laid out without a divider. */
    val shownRows: List<TimelineRow>
        get() = rows.ifEmpty { timelineRows(bubbles, conversation?.lifetimeDays ?: 0, null, "", Instant.now()) }

    /** The rows of the message list above the first timeline row (notices, banners, „Ältere Nachrichten laden“, a note). */
    val leadingRows: Int
        get() = notices.size + listOfNotNull(pinBar, requestTitle, flagged, nextBeforeId).size +
            if (loadFailed != null || conversation == null || bubbles.isEmpty()) 1 else 0

    /** Reacting to and answering [item]: a readable message, while this account may write here. */
    fun canAct(item: ChatMessageItem): Boolean = canSend && item.state == ChatMessageItem.State.Readable

    /** „Anheften“, or „Lösen“ for the pinned message, in the reaction bar of those who may pin; null for the others. */
    fun pinLabel(signature: String): String? = when {
        !canPin -> null
        pinBar?.to == signature -> "Lösen"
        else -> "Anheften"
    }

    /** The own reaction to the message with [signature], or "". */
    fun ownReaction(signature: String): String = reactions[signature]?.firstOrNull { it.mine }?.emoji.orEmpty()

    /** "Senden" is possible: text or files, within the limits, not while recording or uploading. */
    val canSubmit: Boolean
        get() = canSend && (draft.isNotBlank() || drafts.isNotEmpty()) && !tooLong && recordingSeconds == null && uploadProgress == null &&
            !paused
}

/**
 * Screen "conversation" (ui/conversation-view.js; iOS `ConversationModel`): read and write one chat.
 *
 * On open: load the conversation (syncs the server clock), verify the members' keys, open my grants; as owner of a
 * pending key create and distribute a new one; load the newest history; listen on `private-conversation.{id}` for
 * `MessageSent` / `KeyRotated` (polling while the socket is down). Every `KeyRotated` and every 409 repeats the key
 * steps. Plaintext lives only in the in-memory timeline; expired messages are removed every 15 s. A lock of the vault
 * closes the chat at once – also while the screen is stopped in the background and does not redraw – and a message
 * counts as read only while the chat is on screen ([setVisible]).
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

    /** Whether this screen asked the transcriber already. */
    private var transcriberAsked = false

    /** Effects of live messages with files, by signature, until their states say none is view once. */
    private val awaitingEffects = mutableMapOf<String, String>()

    /** Counts the playings, so the same effect plays again. */
    private var effectPlays = 0

    /** What the message being written answers („Antworten“), until it is sent, cancelled or the chat closes. */
    private var reply: MessageReply? = null
    private var timeline = ChatTimeline()
    private var notices: List<String> = emptyList()
    private var quiet: List<QuietNotice> = emptyList()

    private val swipeHint by lazy { SwipeHintTracker(context.preferences, conversationId) }
    private var nextBeforeId: String? = null
    private var users: Map<String, PublicUser> = emptyMap()
    private var warnings: List<String> = emptyList()
    private val dismissed = mutableSetOf<String>()

    /** Shouting, repetition and flooding in this chat, in memory only; a pause counts down on the send button. */
    private val guard = SendGuard(viewModelScope, { vault.clock.now() }) { left ->
        render { it.pausing(left) }
        if (left == null) setDraft(state.value.draft)
    }
    private var flagged: SafetyFlag? = null

    /** When the member last had the chat open, taken once per opening (the „Neue Nachrichten“ divider). */
    private var readAt: Instant? = null
    private var readAtTaken = false

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

    /** Whether the chat is on screen (its screen is started); a message only counts as read then. */
    private var visible = true

    /** A message from someone else arrived while the chat was not on screen: it is read once the chat shows again. */
    private var unseen = false

    private val myId: String get() = context.userId

    init {
        // Sperren, idle, background, sign-out: no live messages, no "read", and plaintext and decrypted files are gone.
        vault.unlockedFlow.drop(1).filter { unlocked -> !unlocked }.onEach { close() }.launchIn(viewModelScope)
    }

    /** The display name of a sender, "Unbekannt" for someone not in the directory. */
    fun senderName(userId: String): String = users[userId]?.name ?: "Unbekannt"

    /**
     * The avatar beside a sender's messages, as the conversation describes its members (photos of an accepted chat); the
     * "?" avatar of someone no longer in it.
     */
    fun senderAvatar(userId: String): ChatAvatar = conversation?.avatarOf(userId) ?: ChatAvatar.Unknown

    /** Whose profile the avatar beside a sender's messages opens: a member of the chat; null for anyone else („?“). */
    fun senderProfile(userId: String): ProfileLink? = ProfileLink.of(conversation?.members?.firstOrNull { it.userId == userId })

    /** Recomputes the derived fields after a change of the conversation, the keys or the timeline. */
    private fun render(change: (ConversationState) -> ConversationState = { it }) {
        val current = conversation
        val trusted = current != null && ChatKeyring.ownerTrusted(vault, current)
        val changed = change(mutableState.value)
        val limit = DraftLimit(changed.drafts, sentEffect(changed), reply)
        val measured = limit.measure(changed.draft)
        // Also with a key this app session still holds: nothing is sealed for an untrusted owner (assertSealable).
        val canSend = current != null && trusted && context.canParticipate && !current.ended && !current.rotationRequired &&
            vault.conversationKey(current.id, current.keyVersion) != null
        // Once per change of the timeline, the chat, the right to write, `last_read_at` or the directory – not per keystroke.
        val views = TimelineViews(myId, ::senderName, vault.clock.now())
        val derived = derived.get(timeline, current, canSend, readAt, users) {
            DerivedTimeline.of(views, timeline.items, current, canSend, readAt)
        }
        mutableState.value = changed.copy(
            reactions = derived.reactions,
            quotes = derived.quotes,
            conversation = current,
            title = current?.displayTitle(myId).orEmpty(),
            timeline = timeline,
            notices = notices,
            quietNotices = quiet,
            nextBeforeId = nextBeforeId,
            flagged = flagged,
            canSend = canSend,
            pinBar = derived.pinBar,
            canPin = current != null && (current.type == "direct" || current.createdBy == myId),
            polls = derived.polls,
            untrustedOwner = current != null && !current.ended && !trusted,
            counter = DraftLimit.counter(measured),
            placeholder = DraftLimit.placeholder(measured.max),
            atLimit = DraftLimit.atLimit(measured),
            tooLong = measured.tooLong,
            limit = limit,
            requestTitle = requestTitle(current, myId),
            isOwner = current?.createdBy == myId,
            headerStatus = headerStatus(current, myId),
            rows = derived.rows
        )
        // The list's line 2 of this chat, kept only in memory (forgotten on every lock).
        current?.let { chat -> latestPreview(mutableState.value.bubbles, myId)?.let { vault.previews[chat.id] = it } }
    }

    private val derived = IdentityCache<DerivedTimeline>()

    // region Lifecycle

    /**
     * Open the chat and start listening. Every step checks that the chat was not closed meanwhile (leaving, a lock):
     * a share for it then waits for the next opening, and nothing listens for a closed chat.
     */
    suspend fun open() = inScope {
        close()
        readAtTaken = false
        val token = openCount
        try {
            refresh()
            if (!isCurrent(token)) return@inScope
            loadHistory()
            if (!isCurrent(token)) return@inScope
            markRead()
            if (!isCurrent(token)) return@inScope
            takeShare(token)
            if (!isCurrent(token)) return@inScope
            offerSwipeHint()
            jobs += viewModelScope.launch {
                realtime.subscribe("private-conversation.$conversationId").collect { handle(it) }
            }
            startTimers(token)
            loadLook()
            // Asked once per screen: creating the recognizer to ask costs a moment.
            if (!transcriberAsked) {
                transcriberAsked = true
                val available = context.transcriber.isAvailable()
                render { it.copy(canTranscribe = available) }
            }
        } catch (error: Exception) {
            handleFailure(error)
        }
    }

    /**
     * The chat's screen is shown or hidden (another app in front, the screen off): while hidden, new messages are not
     * reported as read; once it shows again, they are.
     */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (visible && unseen) {
            unseen = false
            viewModelScope.launch { markRead() }
        }
    }

    /** Leave the chat: stop listening and forget its plaintext, notices, errors and the unsent draft. */
    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        awaitingEffects.clear()
        stopTicker()
        // A view-once file open while the chat closes (lock, leaving) counts as viewed – also when the answer fails.
        mutableState.value.viewOnce?.let { open ->
            vault.viewedOnce += viewedKey(open.attachment.id)
            viewModelScope.launch { runCatching { context.repository.answerAttachment(conversationId, open.attachment.id, VIEWED) } }
        }
        media.clear()
        openCount += 1
        timeline = ChatTimeline()
        conversation = null
        nextBeforeId = null
        notices = emptyList()
        quiet = emptyList()
        flagged = null
        dismissed.clear()
        guard.forget()
        unseen = false
        reply = null
        render {
            it.copy(
                draft = "",
                draftRevision = it.draftRevision + 1,
                replyBar = null,
                pollForm = null,
                hint = null,
                // A failure of the last opening (or of work cut off by the close) is no news for the next one.
                loadFailed = null,
                composerError = null,
                drafts = emptyList(),
                viewOnceNext = false,
                preparing = false,
                recordingSeconds = null,
                uploadProgress = null,
                attachmentViews = emptyMap(),
                transcripts = emptyMap(),
                playing = null,
                pendingEffect = null,
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
        val newQuiet = keyNotices(current, directory, myId, vault.clock.now())
        val newWarnings = mutableListOf<String>()
        // An ended direct chat (the other person left) gets no key any more; it stays readable without a composer.
        if (current.rotationRequired && current.createdBy == myId && !current.ended) {
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
        quiet = newQuiet
        conversation = current
        if (!readAtTaken) readAt = current.lastReadAt?.let(OasisJson::parseInstant).also { readAtTaken = true }
        renderNotices(current)
        redecrypt()
        render()
    }

    private fun renderNotices(current: ChatConversation) {
        val readOnly = "Dein Konto ist gerade nur zum Lesen. Mit einer Mitgliedschaft kannst du wieder schreiben."
        notices = listOfNotNull(waitingNotice(current, vault, myId), readOnly.takeUnless { context.canParticipate }) + warnings
    }

    /** Step 3: the next 50 older messages ("Ältere Nachrichten laden"). */
    private suspend fun loadHistory() {
        val token = openCount
        val page = context.repository.messages(conversationId, nextBeforeId)
        if (!isCurrent(token)) return
        // Verifying and decrypting a page is no work for the main thread; a chat closed meanwhile keeps none of it.
        val items = withContext(context.cryptoDispatcher) {
            page.data.reversed().map { ChatMessages.open(it.envelope, conversationId, vault, context.crypto, keptViewed(it.attachments)) }
        }
        if (!isCurrent(token)) return
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

    /** Polling fallback: key changes and the newest page. A poll cut off by closing the chat is no failure. */
    internal suspend fun poll() {
        val token = openCount
        try {
            val fresh = context.repository.conversation(conversationId)
            val known = conversation
            if (known != null && (fresh.keyVersion != known.keyVersion || fresh.rotationRequired != known.rotationRequired)) refresh()
            val page = context.repository.messages(conversationId)
            if (!isCurrent(token)) return
            page.data.reversed().forEach { receive(it.envelope, it.attachments) }
        } catch (cancelled: CancellationException) {
            throw cancelled
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
                receive(envelope, live = true)
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
    internal fun receive(envelope: MessageEnvelope, states: List<AttachmentState>? = null, live: Boolean = false) {
        val current = conversation ?: return
        if (envelope.conversationId != current.id) return
        val kept = states?.let(::keptViewed)
        if (kept != null) updateStates(envelope.signature, kept)
        val item = ChatMessages.open(envelope, current.id, vault, context.crypto, kept.orEmpty()) ?: return
        timeline = timeline.appending(item) ?: return
        if (states == null && item.content.attachments.isNotEmpty()) viewModelScope.launch { reloadStates() }
        // Live only, once: a message from someone else with an effect plays it – after its files' states said none is
        // view once (realtime brings no states, they come with the reload).
        // A reaction only changes the chips of its target: no announcement, no effect, not read (conversation-view.js).
        val fromOthers = item.senderId != myId && !item.content.control
        val effect = item.content.effect
        if (live && effect != null && fromOthers) {
            if (item.content.attachments.isEmpty()) play(effect) else awaitingEffects[item.signature] = effect
        }
        checkSafety()
        render()
        if (fromOthers) {
            render { it.copy(spokenNotice = "Neue Nachricht von ${senderName(item.senderId)}.") }
            // Read means seen: not while another app is in front or the screen is off.
            if (visible) {
                viewModelScope.launch { markRead() }
            } else {
                unseen = true
            }
        }
    }

    /** The files' states of a shown message, as the server names them now. */
    private fun updateStates(signature: String, states: List<AttachmentState>) {
        awaitingEffects.remove(signature)?.let { effect -> if (states.none { it.viewOnce }) play(effect) }
        val known = timeline.items.firstOrNull { it.signature == signature } ?: return
        val mapped = states.associateBy { it.id }
        if (known.attachmentStates == mapped) return
        timeline = timeline.replacing(known.copy(attachmentStates = mapped))
        render()
    }

    /** States of the newest messages (after a realtime message with files). Failures leave them as they are. */
    private suspend fun reloadStates() {
        val page = runCatching { context.repository.messages(conversationId) }.getOrNull() ?: return
        page.data.forEach { updateStates(it.envelope.signature, keptViewed(it.attachments)) }
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
        guard.edited(value)
        val tone = ChatSafety.scanOutgoing(value, vault.safety.tone).tone
        mutableState.value = guard.typed(mutableState.value.copy(draft = value), tone)
        render()
    }

    /** The view model's own change of the draft: the field shows it ([ConversationState.draftRevision]). */
    private fun replaceDraft(value: String) {
        setDraft(value)
        render { it.copy(draftRevision = it.draftRevision + 1) }
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
        // Sensitive data, shouting or repetition: a hint, and the next press with the same text sends it.
        guard.hold(text, ChatSafety.scanOutgoing(text, vault.safety.tone).sensitive)?.let { hint ->
            render { it.copy(hint = hint, sendLabel = ChatSpam.SEND_ANYWAY.takeIf { guard.asksAgain }) }
            return@inScope
        }
        if (!guard.mayLeave(text)) return@inScope
        val token = openCount
        try {
            // Nothing is uploaded for a chat nothing may be sealed for.
            ChatKeyring.assertSealable(vault, current)
            // No effect with a view-once file: the chosen one waits for the next message.
            val effect = sentEffect(state.value)
            val references = upload(current.id, drafts)
            val answered = reply
            val plaintext = MessagePayload.encode(MessageContent(text, references, effect, answered))
            val envelope = ChatMessages.seal(plaintext, current, vault, context.crypto)
            val stored = context.repository.sendMessage(current.id, envelope, references.map { it.id })
            // Left or locked meanwhile: sent, but nothing of it is kept or written to the device any more (conversation-view.js).
            if (!isCurrent(token)) return@inScope
            guard.sent(text)
            render { it.copy(sendLabel = null) }
            // Sent: nothing after this may report a failure, or the member sends it again. What was picked stays on screen
            // without a download (a preview that cannot be written, on a full storage, comes down later like a
            // recipient's); view-once files are not shown to the sender again.
            val shown = drafts.zip(references).filter { !it.first.viewOnce }.mapNotNull { (draft, reference) ->
                runCatching { media.present(draft.kind, draft.data, draft.mime, draft.name) }.getOrNull()?.let { reference.id to it }
            }.toMap()
            render { it.copy(drafts = it.drafts - drafts.toSet(), uploadProgress = null, attachmentViews = it.attachmentViews + shown) }
            // The quote went along: the bar closes. „Einmal ansehen“ was for this message only.
            reply = null
            render { it.copy(replyBar = null, viewOnceNext = false) }
            replaceDraft(remainder(state.value.draft, typed))
            receive(envelope, stored.attachments)
            // Replying accepts a message request.
            if (current.request == true) acceptLocally()
            render { it.copy(spokenNotice = "Gesendet.") }
            if (effect != null) {
                render { it.copy(pendingEffect = null) }
                play(effect)
            }
        } catch (error: Exception) {
            // The chat was left meanwhile: its error and a reload are not for the chat shown now.
            if (!isCurrent(token)) return@inScope
            if (guard.limited(error)) return@inScope render { it.copy(uploadProgress = null) }
            render { it.copy(composerError = describeChatError(error), uploadProgress = null) }
            if (error is ApiError.Conflict || error == ChatKeyringException.StaleKeyVersion) {
                try {
                    refresh()
                } catch (refreshError: Exception) {
                    handleFailure(refreshError)
                }
            }
            // A refused expiry: the owner may have changed the lifetime meanwhile, and open chats are not told. It is
            // reloaded and shown; sending again under it is the member's choice, never done silently (conversation-view.js).
            if (error is ApiError.Validation && error.fields.containsKey("envelope")) lifetimeChanged(current)
        }
    }

    /** After a refused envelope: reload the chat and say so when its lifetime changed since [sent] was sealed for it. */
    private suspend fun lifetimeChanged(sent: ChatConversation) {
        try {
            refresh()
        } catch (refreshError: Exception) {
            handleFailure(refreshError)
            return
        }
        val now = conversation ?: return
        if (now.lifetimeDays != sent.lifetimeDays) {
            render {
                it.copy(
                    composerError = "Nachrichten in diesem Chat löschen sich jetzt nach ${lifetimeLabel(now.lifetimeDays)}. " +
                        "Zum Senden tippe noch einmal auf „Senden“."
                )
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
            render { it.copy(drafts = it.drafts + draft.withViewOnce(it.viewOnceNext)) }
        } catch (problem: AttachmentProblem) {
            render { it.copy(composerError = problem.message) }
        } finally {
            render { it.copy(preparing = false) }
        }
    }

    /**
     * What the member shared into this chat under "Teilen mit …": its files become drafts (photos by the photo rules),
     * its text the draft text. Nothing is sent before "Senden"; the cache copies are deleted right after – unless the
     * chat closed while they were read (leaving, a lock): then the share waits for the next opening of this chat. Files
     * beyond the four of a message keep waiting for the next "Teilen mit …", like on iOS (`takeShares`).
     */
    private suspend fun takeShare(token: Int) {
        val share = PendingShare.take(conversationId) ?: return
        val problems = mutableListOf<String>()
        val taken = mutableListOf<DraftAttachment>()
        var left = emptyList<SharedFile>()
        var waits = false
        try {
            for ((index, file) in share.files.withIndex()) {
                if (state.value.drafts.size + taken.size >= ChatAttachments.MAX_PER_MESSAGE) {
                    left = share.files.drop(index)
                    break
                }
                try {
                    taken += media.prepareShared(file, ++nextLocalId)
                } catch (problem: AttachmentProblem) {
                    problems += "${file.name}: ${problem.message}"
                }
            }
            waits = !isCurrent(token)
        } finally {
            // A newer share that came in meanwhile wins, as always.
            val alone = PendingShare.share.value == null
            when {
                waits && alone -> {
                    PendingShare.offer(share)
                    PendingShare.target(conversationId)
                }

                left.isNotEmpty() && alone -> {
                    (share.files - left.toSet()).forEach { it.file.delete() }
                    PendingShare.offer(Share(left, null, share.folder))
                }

                else -> PendingShare.delete(share)
            }
        }
        if (waits) return
        render { state -> state.copy(drafts = state.drafts + taken.map { it.withViewOnce(state.viewOnceNext) }) }
        share.text?.let(::replaceDraft)
        val rest = if (left.size == 1) "Den übrigen" else "Die übrigen ${left.size}"
        val waiting = left.takeIf { it.isNotEmpty() }?.let {
            "Du kannst höchstens ${ChatAttachments.MAX_PER_MESSAGE} Anhänge an eine Nachricht hängen. $rest bietet dir Oasis wieder " +
                "unter „Teilen mit …“ an, wenn du die App das nächste Mal öffnest."
        }
        render { it.copy(composerError = waiting ?: problems.firstOrNull()) }
    }

    /** "Anhang entfernen". */
    fun removeDraft(localId: Long) = render { state -> state.copy(drafts = state.drafts.filter { it.localId != localId }) }

    /** „Einmal ansehen“ in the „+“ sheet (or ✕ on its chip): all files of the next message, also those added later. */
    fun setViewOnceNext(value: Boolean) = render { state ->
        state.copy(viewOnceNext = value, drafts = state.drafts.map { it.withViewOnce(value) })
    }

    /** View-once for one draft only (a message with some view-once files, as other clients may send). */
    internal fun setViewOnce(localId: Long, value: Boolean) = render { state ->
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
                it.copy(recordingSeconds = null, drafts = it.drafts + draft.withViewOnce(it.viewOnceNext))
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
        if (!item.listed(attachment.id, item.senderId == myId)) return@inScope
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
        if (state.value.viewOnce != null || !item.listed(attachment.id, item.senderId == myId)) return@inScope
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

    /**
     * Closing the view-once viewer: the file is deleted here and reported as viewed, so the server deletes it too. Shown
     * is shown: it counts as viewed at once, so a failed answer never lets it be opened again ([keptViewed]).
     */
    suspend fun closeViewOnce() = inScope {
        val open = state.value.viewOnce ?: return@inScope
        media.discard(open.view)
        vault.viewedOnce += viewedKey(open.attachment.id)
        render { it.copy(viewOnce = null) }
        setState(open.item.signature, open.item.state(open.attachment.id, mine = false).copy(status = AttachmentState.VIEWED))
        try {
            setState(open.item.signature, context.repository.answerAttachment(conversationId, open.attachment.id, VIEWED))
        } catch (error: Exception) {
            render { it.copy(actionError = attachmentError(error)) }
        }
    }

    private fun viewedKey(attachmentId: String) = "$conversationId|$attachmentId"

    /**
     * The server's states with every view-once file this app session showed as viewed, although a failed answer left it
     * accepted on the server (attachment-view.js `keepViewed`); the answer goes again.
     */
    private fun keptViewed(states: List<AttachmentState>): List<AttachmentState> = states.map { state ->
        if (state.status != AttachmentState.ACCEPTED || viewedKey(state.id) !in vault.viewedOnce) {
            state
        } else {
            viewModelScope.launch { runCatching { context.repository.answerAttachment(conversationId, state.id, VIEWED) } }
            state.copy(status = AttachmentState.VIEWED)
        }
    }

    private fun attachmentError(error: Exception): String = if (error is ApiError.NotFound) GONE else describeChatError(error)

    // endregion

    // region Backgrounds and effects

    /** The background and „Effekte abspielen“ as stored on this device. */
    private suspend fun loadLook() {
        val look = context.look
        val choice = look.choice(conversationId)
        val effects = look.effectsEnabled()
        render { it.copy(look = choice, effectsOn = effects) }
    }

    /**
     * An effect from the menu („Mit Effekt senden“): with something to send it goes at once, otherwise it waits as the
     * chip for the next message.
     */
    suspend fun chooseEffect(key: String) {
        render { it.copy(pendingEffect = key) }
        if (state.value.canSubmit) send()
    }

    /** „✕“ on the chip. */
    fun clearEffect() = render { it.copy(pendingEffect = null) }

    /** Plays an effect once over the chat, unless „Effekte abspielen“ is off on this device. */
    private fun play(key: String) {
        if (state.value.effectsOn) render { it.copy(playing = EffectPlay(key, ++effectPlays)) }
    }

    /** The effect has run its course. */
    fun effectDone() = render { it.copy(playing = null) }

    /** The effect a message sent now carries: the chosen one, but none with a view-once file. */
    private fun sentEffect(state: ConversationState): String? = state.pendingEffect.takeIf { state.drafts.none { it.viewOnce } }

    // endregion

    // region Voice messages as text

    /**
     * „Als Text anzeigen“: the decrypted voice message on screen is turned into text on the device; a hidden text shows
     * again. The text stays in memory for this chat only and is never sent.
     */
    suspend fun transcribe(attachment: MessageAttachment) = inScope {
        val hidden = state.value.transcripts[attachment.id] as? Transcript.Hidden
        if (hidden != null) {
            setTranscript(attachment.id, Transcript.Shown(hidden.text))
            return@inScope
        }
        val file = (state.value.attachmentViews[attachment.id] as? AttachmentView.Playable)?.file ?: return@inScope
        val token = openCount
        setTranscript(attachment.id, Transcript.Working)
        val result = try {
            Transcript.Shown(context.transcriber.transcribe(file))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: TranscriptionProblem) {
            Transcript.Failed(problem.text)
        } catch (_: Exception) {
            Transcript.Failed(TranscriptionProblem.Failed.text)
        }
        // Left or locked meanwhile: the text is not kept.
        if (token == openCount) setTranscript(attachment.id, result)
    }

    /** „Text ausblenden“. */
    fun hideTranscript(attachmentId: String) {
        val shown = state.value.transcripts[attachmentId] as? Transcript.Shown ?: return
        setTranscript(attachmentId, Transcript.Hidden(shown.text))
    }

    private fun setTranscript(attachmentId: String, transcript: Transcript) =
        render { it.copy(transcripts = it.transcripts + (attachmentId to transcript)) }

    // endregion

    // region Reactions and replies

    /**
     * React to [item] with [emoji] – the own reaction again removes it: an encrypted reaction message, quiet on the server
     * (`"kind":"reaction"`), shown at once as a chip.
     */
    suspend fun react(item: ChatMessageItem, emoji: String) = inScope {
        val chosen = if (state.value.ownReaction(item.signature) == emoji) "" else emoji
        sendControl(ChatRepository.REACTION) { MessagePayload.reaction(item.signature, chosen) }
    }

    /** „Anheften“ / „Lösen“: an encrypted pin (the message's quote) or unpin, quiet on the server. */
    suspend fun togglePin(item: ChatMessageItem) = inScope {
        val quote = quoteOf(item) ?: return@inScope
        sendControl(ChatRepository.PIN) { MessagePayload.pin(quote.takeIf { state.value.pinBar?.to != item.signature }) }
    }

    /** A vote: single choice replaces (the chosen answer again takes it back), multiple choice toggles. */
    suspend fun vote(item: ChatMessageItem, index: Int) = inScope {
        val chosen = state.value.polls[item.signature]?.choosing(index) ?: return@inScope
        sendControl(ChatRepository.VOTE) { MessagePayload.vote(item.signature, chosen) }
    }

    /** „Umfrage beenden“ (its creator, after confirming): nobody can vote any more. */
    suspend fun closePoll(item: ChatMessageItem) = inScope { sendControl(ChatRepository.VOTE) { MessagePayload.pollClose(item.signature) } }

    /** A quiet control message (reaction, pin, vote); a failure is explained. */
    private suspend fun sendControl(kind: String, plaintext: () -> String) =
        sendSealed(kind, plaintext)?.let { problem -> render { it.copy(actionError = problem) } }

    /**
     * Seal [plaintext], send it as [kind] and show it at once: null once sent (also when the chat was left meanwhile, or
     * there is none), else why it failed.
     */
    private suspend fun sendSealed(kind: String, plaintext: () -> String): String? {
        val current = conversation ?: return null
        val token = openCount
        return try {
            val envelope = ChatMessages.seal(plaintext(), current, vault, context.crypto)
            val stored = context.repository.sendMessage(current.id, envelope, kind = kind)
            if (isCurrent(token)) receive(envelope, stored.attachments)
            null
        } catch (error: Exception) {
            describeChatError(error).takeIf { isCurrent(token) }
        }
    }

    /** „Umfrage“ opens the form (a new one), „Abbrechen“ closes it (null). */
    fun showPollForm(form: PollForm?) = render { it.copy(pollForm = form) }

    /** Typing in the form, adding or removing an answer, „Mehrere Antworten erlaubt“. */
    fun editPoll(change: (PollForm) -> PollForm) = render { state -> state.copy(pollForm = state.pollForm?.let(change)) }

    /** „Umfrage senden“: checked against the limits, then sent as an ordinary (notifying) encrypted message. */
    suspend fun sendPoll() = inScope {
        val form = state.value.pollForm ?: return@inScope
        val problem = form.problem ?: sendSealed(ChatRepository.MESSAGE) { MessagePayload.poll(form.question, form.answers, form.multiple) }
        render {
            if (problem ==
                null
            ) {
                it.copy(pollForm = null, spokenNotice = "Umfrage gesendet.")
            } else {
                it.copy(pollForm = form.copy(error = problem))
            }
        }
    }

    // region Swipe hint (docs/oasis-chat-client.md, „C8 Swipe hint once“)

    /** A chat with a message of someone else: the hint, and the newest such message nudges once. */
    private suspend fun offerSwipeHint() {
        val newest = mutableState.value.bubbles.lastOrNull { it.senderId != myId } ?: return
        if (swipeHint.offer()) render { it.copy(swipeHint = true, nudge = newest.id) }
    }

    /** The nudge played (or could not, without motion). */
    fun nudged() = render { it.copy(nudge = null) }

    /** „Verstanden“: the hint is gone for good on this device. */
    suspend fun dismissSwipeHint() = inScope { endSwipeHint() }

    /** A message swiped right: answering it, and the hint has done its job. */
    suspend fun swipedReply(item: ChatMessageItem) = inScope {
        startReply(item)
        endSwipeHint()
    }

    private suspend fun endSwipeHint() {
        render { it.copy(swipeHint = false, nudge = null) }
        swipeHint.finish()
    }

    // endregion

    /** „Antworten“: the quote bar above the composer („Antwort an {Name}“, „Antwort an dich“). */
    fun startReply(item: ChatMessageItem) {
        val quoted = quoteOf(item) ?: return
        reply = quoted
        val name = if (item.senderId == myId) "dich" else senderName(item.senderId)
        render { it.copy(replyBar = ReplyBar("Antwort an $name", QuoteTexts.of(quoted))) }
    }

    /** „Antwort abbrechen“. */
    fun cancelReply() {
        reply = null
        render { it.copy(replyBar = null) }
    }

    /**
     * Tapping a quote: the position of the original among the bubbles while it is loaded; otherwise null and the note
     * that it is not loaded.
     */
    fun quoteTarget(signature: String): Int? = state.value.rows.indexOfMessage(signature)
        ?: null.also { render { it.copy(status = QuoteTexts.NOT_LOADED) } }

    // endregion

    // region Safety banner, requests, blocking

    private fun checkSafety() {
        val incoming = timeline.items.filter {
            it.senderId != myId && it.state == ChatMessageItem.State.Readable &&
                !it.content.control
        }
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
            val left = try {
                context.repository.leave(conversationId)
                true
            } catch (_: ApiError.Validation) {
                // A group owner stays; the block alone stops the messages (iOS ConversationModel.blockAndLeave).
                false
            }
            vault.announcement.value = if (left) {
                "Blockiert. Der Chat ist gelöscht."
            } else {
                "Blockiert. Die Gruppe bleibt, weil du sie gestartet hast. In den Details kannst du die Person entfernen."
            }
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

        /** The composer after `sent` went out: what was typed meanwhile stays; a changed draft stays as it is. */
        fun remainder(draft: String, sent: String): String {
            if (!draft.startsWith(sent)) return draft
            return draft.substring(sent.length).trimStart()
        }
    }
}
