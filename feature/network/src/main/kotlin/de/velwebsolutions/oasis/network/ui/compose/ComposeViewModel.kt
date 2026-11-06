package de.velwebsolutions.oasis.network.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.CommonApi
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.network.api.ComposerContext
import de.velwebsolutions.oasis.network.api.ImageEdge
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.PollDraft
import de.velwebsolutions.oasis.network.api.PostDraft
import de.velwebsolutions.oasis.network.api.PublishResult
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.apiError
import de.velwebsolutions.oasis.network.ui.attempt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A picked image with its description. */
data class PickedImage(val id: Long, val upload: ImageUpload, val alt: String = "")

/** What the composer shows: the server's context, the draft, the tone hints and the last error. */
data class ComposeUiState(
    val oasisSlug: String? = null,
    val replyTo: Int? = null,
    val carried: Int? = null,
    val context: Loadable<ComposerContext> = Loadable.Idle,
    val body: String = "",
    val visibility: String = "public",
    val kind: String = "post",
    val linkRead: Boolean = false,
    val toneConfirmed: Boolean = false,
    val toneNudge: Boolean = false,
    val toneHint: String? = null,
    val images: List<PickedImage> = emptyList(),
    val error: ApiError? = null,
    /** While the Tropfen is sent, also after the form that sent it went away (a rotation during the upload). */
    val isPublishing: Boolean = false,
    /** „Umfrage hinzufügen“ (null: none). */
    val poll: PollDraft? = null,
    /** Only plain Weitertragen: the member may not answer, so own words (or images) would be an answer. */
    val plainCarry: Boolean = false
) {
    /** A poll fits only a new Tropfen of an oasis (not a reply, not a carry, not in the Quelle). */
    val canPoll: Boolean get() = oasisSlug != null && !isReply && !isCarry

    val maxLength: Int get() = context.valueOrNull?.maxLength ?: 500
    val maxImages: Int get() = context.valueOrNull?.maxImages ?: 4

    /** The longest edge a picked photo is scaled to before upload (the server's `image_edge` for the member's tier). */
    val imageEdge: Int get() = context.valueOrNull?.imageEdge ?: ImageEdge.DEFAULT
    val isReply: Boolean get() = replyTo != null
    val isCarry: Boolean get() = carried != null

    /** Characters like PHP's `mb_strlen` (code points). */
    val length: Int get() = body.codePointCount(0, body.length)

    /** Whether closing would lose something the member wrote or picked. */
    val hasDraft: Boolean get() = body.isNotBlank() || images.isNotEmpty()

    /** The field label like the web ("Deine Antwort", "Deine Worte dazu (freiwillig)", "Dein Tropfen in …", "Dein Tropfen"). */
    val bodyLabel: String
        get() {
            if (isReply) return "Deine Antwort"
            if (isCarry) return "Deine Worte dazu (freiwillig)"
            val name = context.valueOrNull?.oasis?.name
            return if (name != null) "Dein Tropfen in $name" else "Dein Tropfen"
        }

    val submitLabel: String get() = if (isReply) {
        "Antworten"
    } else if (isCarry) {
        "Weitertragen"
    } else {
        "Tropfen teilen"
    }

    fun fieldError(field: String): String? = error?.message(field)

    /** A general error that belongs to no field. */
    val generalError: String?
        get() {
            val error = error ?: return null
            if (error is ApiError.Validation && error.fields.isNotEmpty()) return null
            return error.userMessage
        }
}

/**
 * Unsent composer drafts (text and images) in memory, so a composer closed by a link or a notification opens again with
 * them. A draft belongs to the member who wrote it: its key names the member, and signing out (also a revoked token)
 * forgets all drafts, so whoever signs in next on the device never finds them.
 */
@Singleton
class ComposeDrafts @Inject constructor(session: Session) {
    /** What a composer had written and picked. */
    data class Draft(val body: String, val images: List<PickedImage>)

    private val drafts = ConcurrentHashMap<String, Draft>()

    init {
        session.addOnSignedOut(drafts::clear)
    }

    operator fun get(key: String): Draft? = drafts[key]

    /** Keeps the draft under [key]; null (nothing written or picked) forgets it. */
    operator fun set(key: String, draft: Draft?) {
        if (draft == null) drafts.remove(key) else drafts[key] = draft
    }
}

/**
 * The composer for a new Tropfen (Quelle or oasis), a reply or a carry: context from the server, the draft (kept in
 * [ComposeDrafts] for the signed-in member), local tone hints while typing, images with descriptions and publishing with
 * the web's rules (SubmitPost). Under 18 the server offers no "Öffentlich", and the preselected audience is always one it
 * offers.
 */
@HiltViewModel(assistedFactory = ComposeViewModel.Factory::class)
class ComposeViewModel @AssistedInject constructor(
    @Assisted oasisSlug: String?,
    @Assisted("replyTo") replyTo: Int?,
    @Assisted("carried") carried: Int?,
    private val repository: NetworkRepository,
    private val common: CommonApi,
    private val session: Session,
    private val drafts: ComposeDrafts
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(oasisSlug: String?, @Assisted("replyTo") replyTo: Int?, @Assisted("carried") carried: Int?): ComposeViewModel
    }

    private val mutableState = MutableStateFlow(ComposeUiState(oasisSlug = oasisSlug, replyTo = replyTo, carried = carried))
    val state: StateFlow<ComposeUiState> = mutableState.asStateFlow()
    private val current: ComposeUiState get() = mutableState.value

    /** The member and the composer context the draft belongs to ("7|stadtgarten||"). */
    private val draftKey = listOf(session.me?.id, oasisSlug, replyTo, carried).joinToString("|") { it?.toString().orEmpty() }

    /** The running publish, until the form that started it takes its answer (or the next form, [unclaimedPublish]). */
    private var sending: Deferred<PublishResult?>? = null

    init {
        drafts[draftKey]?.let { draft -> mutableState.update { it.copy(body = draft.body, images = draft.images) } }
    }

    /** Capped at the server's limit, counted in characters like PHP's `mb_strlen`. */
    fun updateBody(text: String) {
        val capped = prefix(text, current.maxLength)
        mutableState.update { if (capped != it.body) it.copy(body = capped, toneConfirmed = false) else it }
        saveDraft()
    }

    fun setVisibility(value: String) = mutableState.update { it.copy(visibility = value) }

    /** Weitertragen without own words (`can_quote` false): the form hides the text and images and sends neither. */
    fun carryPlainly() = mutableState.update { it.copy(plainCarry = true) }

    fun setKind(value: String) = mutableState.update { it.copy(kind = value) }

    fun setLinkRead(value: Boolean) = mutableState.update { it.copy(linkRead = value) }

    fun setToneConfirmed(value: Boolean) = mutableState.update { it.copy(toneConfirmed = value) }

    /** „Umfrage hinzufügen“ (a new, empty one) or „Umfrage entfernen“ (null). */
    fun showPoll(poll: PollDraft?) = mutableState.update { it.copy(poll = poll) }

    /** The question, the answers, „Mehrere Antworten erlaubt“ and the runtime. */
    fun editPoll(change: (PollDraft) -> PollDraft) = mutableState.update { state -> state.copy(poll = state.poll?.let(change)) }

    /** "Entwurf verwerfen". */
    fun discard() {
        mutableState.update { it.copy(body = "", images = emptyList()) }
        saveDraft()
    }

    /**
     * Loads the context. A load cancelled with its screen (a rotation, the reply form scrolled out of view) leaves it
     * unloaded again, so [loadIfNeeded] starts it anew when the screen is back.
     */
    suspend fun load() {
        mutableState.update { it.copy(context = Loadable.Loading) }
        try {
            val result = attempt { repository.composer(current.oasisSlug, current.replyTo, current.carried) }
            result.onSuccess { loaded ->
                val offered = loaded.visibilities.map { it.value }
                val visibility = loaded.defaultVisibility.takeIf { offered.isEmpty() || it in offered } ?: offered.first()
                mutableState.update { it.copy(visibility = visibility, context = Loadable.Loaded(loaded)) }
            }
            result.apiError?.let { error -> mutableState.update { it.copy(context = Loadable.Failed(error)) } }
        } finally {
            mutableState.update { if (it.context == Loadable.Loading) it.copy(context = Loadable.Idle) else it }
        }
    }

    /** Loads the context unless it is there or failed (the reply form on a Tropfen page, a screen shown again). */
    suspend fun loadIfNeeded() {
        if (current.context == Loadable.Idle) load()
    }

    /** The tone check while typing (only the word list and the slang hint; drafts never reach the language model). */
    suspend fun checkTone() {
        val text = current.body
        if (text.isBlank()) {
            mutableState.update { it.copy(toneHint = null, toneNudge = false) }
            return
        }
        // A failed check shows nothing; an answer for an older text is dropped.
        val result = attempt { common.toneHint(text, "post") }.getOrNull() ?: return
        if (text != current.body) return
        mutableState.update { it.copy(toneHint = result.hint, toneNudge = result.nudge) }
    }

    /** Adds a picked image (null when it could not be read) while there is room. */
    fun add(upload: ImageUpload?) {
        upload ?: return
        if (current.images.size >= current.maxImages) return
        mutableState.update { it.copy(images = it.images + PickedImage(nextImageId.incrementAndGet(), upload)) }
        saveDraft()
    }

    fun removeImage(index: Int) {
        if (index !in current.images.indices) return
        mutableState.update { it.copy(images = it.images.filterIndexed { position, _ -> position != index }) }
        saveDraft()
    }

    /** The description of an image, capped at the server's limit. */
    fun updateAlt(index: Int, alt: String) {
        if (index !in current.images.indices) return
        val capped = prefix(alt, MAX_ALT_LENGTH)
        mutableState.update { state ->
            state.copy(
                images = state.images.mapIndexed { position, image -> if (position == index) image.copy(alt = capped) else image }
            )
        }
        saveDraft()
    }

    /**
     * Publish; returns the new Tropfen on success. A tone question shows "So senden". The upload runs in the view model's
     * scope: when the form goes away meanwhile (a rotation cancels its coroutine), the Tropfen is still sent once, the
     * publishing state ends, the sent draft is gone, and the form shown next takes the answer with [unclaimedPublish].
     */
    suspend fun publish(): PublishResult? {
        if (current.isPublishing) return null
        mutableState.update { it.copy(isPublishing = true, error = null) }
        val state = current
        val draft = PostDraft(
            body = if (state.plainCarry) "" else state.body,
            visibility = state.visibility,
            kind = if (state.isReply || state.isCarry) "post" else state.kind,
            oasisSlug = state.oasisSlug,
            replyTo = state.replyTo,
            carried = state.carried,
            linkRead = state.linkRead,
            toneConfirmed = state.toneConfirmed,
            images = if (state.plainCarry) emptyList() else state.images.map { it.upload },
            alts = if (state.plainCarry) emptyList() else state.images.map { it.alt },
            poll = state.poll?.takeIf { state.canPoll }
        )
        val running = viewModelScope.async { send(draft) }
        sending = running
        return running.await().also { if (sending === running) sending = null }
    }

    /**
     * The answer of a publish whose form went away while it was sent (a rotation during the upload), once: the form shown
     * next closes with it as after its own tap. Waits while the Tropfen is still on its way; null when no answer waits.
     */
    suspend fun unclaimedPublish(): PublishResult? {
        val running = sending ?: return null
        // A later publish (started once this one ended) keeps its own answer for its form.
        return running.await().also { if (sending === running) sending = null }
    }

    private suspend fun send(draft: PostDraft): PublishResult? {
        val outcome = try {
            attempt { repository.publish(draft) }
        } finally {
            mutableState.update { it.copy(isPublishing = false) }
        }
        outcome.apiError?.let { failure ->
            val asked = failure.message("body").orEmpty().startsWith(TONE_QUESTION)
            mutableState.update { it.copy(error = failure, toneNudge = it.toneNudge || asked) }
        }
        val result = outcome.getOrNull() ?: return null
        mutableState.update {
            it.copy(
                body = "",
                images = emptyList(),
                linkRead = false,
                toneConfirmed = false,
                toneNudge = false,
                toneHint = null,
                poll = null
            )
        }
        saveDraft()
        NetworkSignals.didPublish(result.data, result.message)
        // A held Tropfen never locks the account (since 6 November 2026 only a human decision does); `suspended` is true when
        // such a lock is in force, and the app then shows why (account area).
        if (result.suspended) session.refreshMe()
        return result
    }

    private fun saveDraft() {
        val state = current
        drafts[draftKey] = if (state.hasDraft) ComposeDrafts.Draft(state.body, state.images) else null
    }

    companion object {
        /** The server's limit for image descriptions (PostInput `alts.*`). */
        const val MAX_ALT_LENGTH = 300

        /** The start of the server's tone question on `body` (and `bio` for profiles). */
        const val TONE_QUESTION = "Nochmal lesen?"

        private val nextImageId = AtomicLong()

        /** The first [length] characters (code points) of [text]. */
        fun prefix(text: String, length: Int): String =
            if (text.codePointCount(0, text.length) <= length) text else text.substring(0, text.offsetByCodePoints(0, length))
    }
}
