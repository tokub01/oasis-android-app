package de.velwebsolutions.oasis.network.ui.posts

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.network.api.CarrySource
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.PostDetail
import de.velwebsolutions.oasis.network.api.PostEditForm
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.NetworkViewModel
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.applying
import de.velwebsolutions.oasis.network.ui.perform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Tropfen page (/tropfen/{id}): the post with earlier versions, its replies and the side cards, plus the card
 * actions. Actions that make the Tropfen unreadable for the viewer (delete, block its author, remove it from the oasis)
 * leave the page, like the web goes back to the Quelle.
 */
@HiltViewModel(assistedFactory = PostDetailViewModel.Factory::class)
class PostDetailViewModel @AssistedInject constructor(@Assisted val id: Int, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(id: Int): PostDetailViewModel
    }

    private val mutableDetail = MutableStateFlow<Loadable<PostDetail>>(Loadable.Idle)
    val detail: StateFlow<Loadable<PostDetail>> = mutableDetail.asStateFlow()

    private val mutableLeave = MutableStateFlow(false)

    /** Set when the page should close after an action. */
    val leave: StateFlow<Boolean> = mutableLeave.asStateFlow()

    init {
        observe(NetworkSignals.published) { load() }
    }

    suspend fun load() {
        mutableDetail.load { repository.post(id) }
    }

    /** Reactions and bookmarks change at once and roll back if the server refuses; the rest follows its confirmation. */
    suspend fun perform(action: PostAction, post: Post) {
        val undo = action.undo
        val optimistic = undo != null
        if (optimistic) apply(action, post)
        try {
            mutableStatus.value = repository.perform(action, post)
            mutableError.value = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (undo != null) apply(undo, post)
            mutableError.value = ApiError.from(error)
            return
        }
        val current = detail.value.valueOrNull
        if (optimistic || current == null) return
        if (post.id == current.post.id) {
            when (action) {
                PostAction.Delete, PostAction.RemoveFromOasis, PostAction.Block -> {
                    // The page closes; the tab bar shows the confirmation.
                    NetworkSignals.confirmation.value = mutableStatus.value
                    mutableStatus.value = null
                    mutableLeave.value = true
                    return
                }
                PostAction.Mute -> {
                    load()
                    return
                }
                else -> Unit
            }
        }
        apply(action, post)
    }

    /** The effect on the page: the Tropfen itself when it was the target or its author's state changed, and the replies. */
    private fun apply(action: PostAction, post: Post) {
        val current = detail.value.valueOrNull ?: return
        val authorState = action == PostAction.Restrict || action == PostAction.Unrestrict || action == PostAction.Unmute
        val updated = if (post.id == current.post.id || authorState) current.post.applying(action, post) ?: current.post else current.post
        mutableDetail.value = Loadable.Loaded(current.copy(post = updated, replies = current.replies.applying(action, post)))
    }
}

/**
 * "Tropfen bearbeiten" (/tropfen/{id}/bearbeiten): the text within the edit window; images and audience stay.
 */
@HiltViewModel(assistedFactory = EditPostViewModel.Factory::class)
class EditPostViewModel @AssistedInject constructor(@Assisted val id: Int, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(id: Int): EditPostViewModel
    }

    private val mutableForm = MutableStateFlow<Loadable<PostEditForm>>(Loadable.Idle)
    val form: StateFlow<Loadable<PostEditForm>> = mutableForm.asStateFlow()
    private val mutableText = MutableStateFlow("")
    val text: StateFlow<String> = mutableText.asStateFlow()
    private val mutableSaved = MutableStateFlow(false)

    /** Set after saving: the page closes and the tab shows the confirmation. */
    val saved: StateFlow<Boolean> = mutableSaved.asStateFlow()

    /** Whether leaving would lose a changed text. */
    val isChanged: Boolean get() = form.value.valueOrNull?.let { it.body != text.value } ?: false

    fun updateText(value: String) {
        mutableText.value = value
    }

    suspend fun load() {
        mutableForm.value = Loadable.Loading
        try {
            val loaded = repository.editForm(id)
            mutableText.value = loaded.body
            mutableForm.value = Loadable.Loaded(loaded)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableForm.value = Loadable.Failed(ApiError.from(error))
        }
    }

    suspend fun save() {
        mutableError.value = null
        try {
            NetworkSignals.didPublish(null, repository.updatePost(id, text.value))
            mutableSaved.value = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableError.value = ApiError.from(error)
        }
    }
}

/** "Weitertragen" (/tropfen/{id}/weitertragen): the original above a composer for own words. */
@HiltViewModel(assistedFactory = CarryViewModel.Factory::class)
class CarryViewModel @AssistedInject constructor(@Assisted val id: Int, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(id: Int): CarryViewModel
    }

    private val mutableSource = MutableStateFlow<Loadable<CarrySource>>(Loadable.Idle)
    val source: StateFlow<Loadable<CarrySource>> = mutableSource.asStateFlow()

    suspend fun load() {
        mutableSource.value = Loadable.Loading
        try {
            mutableSource.value = Loadable.Loaded(repository.carrySource(id))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableSource.value = Loadable.Failed(ApiError.from(error))
        }
    }
}

