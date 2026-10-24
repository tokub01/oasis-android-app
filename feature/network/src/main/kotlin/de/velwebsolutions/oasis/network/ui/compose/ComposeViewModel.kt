package de.velwebsolutions.oasis.network.ui.compose

import androidx.lifecycle.ViewModel
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
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.PostDraft
import de.velwebsolutions.oasis.network.api.PublishResult
import de.velwebsolutions.oasis.network.data.NetworkRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
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
    val isPublishing: Boolean = false
) {
    val maxLength: Int get() = context.valueOrNull?.maxLength ?: 500
    val maxImages: Int get() = context.valueOrNull?.maxImages ?: 4
    val isReply: Boolean get() = replyTo != null
    val isCarry: Boolean get() = carried != null

    /** Characters like PHP's `mb_strlen` (code points). */
    val length: Int get() = body.codePointCount(0, body.length)

    /** Whether closing would lose something the member wrote or picked. */
    val hasDraft: Boolean get() = body.isNotBlank() || images.isNotEmpty()

    /** The field label like the web ("Deine Antwort", "Deine Worte dazu", "Dein Tropfen in …", "Dein Tropfen"). */
    val bodyLabel: String
        get() {
            if (isReply) return "Deine Antwort"
            if (isCarry) return "Deine Worte dazu"
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
 * The composer for a new Tropfen (Quelle or oasis), a reply or a carry: context from the server, the draft, local tone
 * hints while typing, images with descriptions and publishing with the web's rules (SubmitPost). Under 18 the server
 * offers no "Öffentlich", and the preselected audience is always one it offers.
 */
@HiltViewModel(assistedFactory = ComposeViewModel.Factory::class)
class ComposeViewModel @AssistedInject constructor(
    @Assisted oasisSlug: String?,
    @Assisted("replyTo") replyTo: Int?,
    @Assisted("carried") carried: Int?,
    private val repository: NetworkRepository,
    private val common: CommonApi,
    private val session: Session
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(oasisSlug: String?, @Assisted("replyTo") replyTo: Int?, @Assisted("carried") carried: Int?): ComposeViewModel
    }

    private val mutableState = MutableStateFlow(ComposeUiState(oasisSlug = oasisSlug, replyTo = replyTo, carried = carried))
    val state: StateFlow<ComposeUiState> = mutableState.asStateFlow()
    private val current: ComposeUiState get() = mutableState.value
    private val draftKey = listOf(oasisSlug.orEmpty(), replyTo?.toString().orEmpty(), carried?.toString().orEmpty()).joinToString("|")

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

    fun setKind(value: String) = mutableState.update { it.copy(kind = value) }

    fun setLinkRead(value: Boolean) = mutableState.update { it.copy(linkRead = value) }

    fun setToneConfirmed(value: Boolean) = mutableState.update { it.copy(toneConfirmed = value) }

    /** "Entwurf verwerfen". */
    fun discard() {
        mutableState.update { it.copy(body = "", images = emptyList()) }
        saveDraft()
    }

    suspend fun load() {
        mutableState.update { it.copy(context = Loadable.Loading) }
        try {
            val loaded = repository.composer(current.oasisSlug, current.replyTo, current.carried)
            val offered = loaded.visibilities.map { it.value }
            val visibility = loaded.defaultVisibility.takeIf { offered.isEmpty() || it in offered } ?: offered.first()
            mutableState.update { it.copy(visibility = visibility, context = Loadable.Loaded(loaded)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(context = Loadable.Failed(ApiError.from(error))) }
        }
    }

    /** Loads the context unless it is there already (the reply form on a Tropfen page). */
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
        val result = try {
            common.toneHint(text, "post")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        if (text == current.body) mutableState.update { it.copy(toneHint = result.hint, toneNudge = result.nudge) }
    }

    fun add(upload: ImageUpload) {
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
                images = state.images.mapIndexed { position, image ->
                    if (position ==
                        index
                    ) {
                        image.copy(alt = capped)
                    } else {
                        image
                    }
                }
            )
        }
        saveDraft()
    }

    /** Publish; returns the new Tropfen on success. A tone question shows "So senden". */
    suspend fun publish(): PublishResult? {
        if (current.isPublishing) return null
        mutableState.update { it.copy(isPublishing = true, error = null) }
        val state = current
        val draft = PostDraft(
            body = state.body,
            visibility = state.visibility,
            kind = if (state.isReply || state.isCarry) "post" else state.kind,
            oasisSlug = state.oasisSlug,
            replyTo = state.replyTo,
            carried = state.carried,
            linkRead = state.linkRead,
            toneConfirmed = state.toneConfirmed,
            images = state.images.map { it.upload },
            alts = state.images.map { it.alt }
        )
        return try {
            val result = repository.publish(draft)
            mutableState.update {
                it.copy(body = "", images = emptyList(), linkRead = false, toneConfirmed = false, toneNudge = false, toneHint = null)
            }
            saveDraft()
            NetworkSignals.didPublish(result.data, result.message)
            // A held Tropfen locks the account at once; the app then shows why (account area).
            if (result.suspended) session.refreshMe()
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = ApiError.from(error)
            mutableState.update {
                it.copy(error = failure, toneNudge = it.toneNudge || failure.message("body")?.startsWith(TONE_QUESTION) == true)
            }
            null
        } finally {
            mutableState.update { it.copy(isPublishing = false) }
        }
    }

    private fun saveDraft() {
        val state = current
        if (state.hasDraft) drafts[draftKey] = Draft(state.body, state.images) else drafts.remove(draftKey)
    }

    private data class Draft(val body: String, val images: List<PickedImage>)

    companion object {
        /** The server's limit for image descriptions (PostInput `alts.*`). */
        const val MAX_ALT_LENGTH = 300

        /** The start of the server's tone question on `body` (and `bio` for profiles). */
        const val TONE_QUESTION = "Nochmal lesen?"

        /** Unsent drafts per composer context: a composer closed by a link or a notification opens again with its text. */
        private val drafts = ConcurrentHashMap<String, Draft>()
        private val nextImageId = AtomicLong()

        /** The first [length] characters (code points) of [text]. */
        fun prefix(text: String, length: Int): String =
            if (text.codePointCount(0, text.length) <= length) text else text.substring(0, text.offsetByCodePoints(0, length))
    }
}
