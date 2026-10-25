package de.velwebsolutions.oasis.network.ui.profiles

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.CommonApi
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.network.api.FriendLists
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.InvitationLanding
import de.velwebsolutions.oasis.network.api.InvitationOverview
import de.velwebsolutions.oasis.network.api.MemberStatistics
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OwnProfile
import de.velwebsolutions.oasis.network.api.Profile
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.NetworkViewModel
import de.velwebsolutions.oasis.network.ui.PostListModel
import de.velwebsolutions.oasis.network.ui.apiError
import de.velwebsolutions.oasis.network.ui.attempt
import de.velwebsolutions.oasis.network.ui.compose.ComposeViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The buttons of a profile. */
enum class ProfileAction { Follow, Unfollow, RequestFriend, AcceptFriend, RemoveFriend, Mute, Unmute, Restrict, Unrestrict, Block }

/**
 * A member profile (/profil/{handle}): what the viewer may see, the member's Tropfen and the follow, friendship and
 * self-protection buttons.
 */
@HiltViewModel(assistedFactory = ProfileViewModel.Factory::class)
class ProfileViewModel @AssistedInject constructor(@Assisted handle: String, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(handle: String): ProfileViewModel
    }

    val handle: String = handle.lowercase()
    private val mutableProfile = MutableStateFlow<Loadable<Profile>>(Loadable.Idle)
    val profile: StateFlow<Loadable<Profile>> = mutableProfile.asStateFlow()
    val posts = PostListModel<NoContext>(repository) { _, cursor -> profilePosts(this@ProfileViewModel.handle, cursor) }
    private val mutableLeave = MutableStateFlow(false)

    /** Set after blocking: the profile no longer exists for the viewer. */
    val leave: StateFlow<Boolean> = mutableLeave.asStateFlow()

    init {
        observe(NetworkSignals.published) { posts.reload() }
        observe(NetworkSignals.profile) { load() }
    }

    suspend fun load() {
        mutableProfile.load { repository.profile(handle) } ?: return
        posts.reload()
    }

    suspend fun loadIfNeeded() {
        if (profile.value.valueOrNull == null) load()
    }

    suspend fun perform(action: ProfileAction) {
        val current = profile.value.valueOrNull ?: return
        val userId = current.member.id
        val call: suspend () -> String = when (action) {
            ProfileAction.Follow -> {
                { repository.follow(handle, on = true) }
            }

            ProfileAction.Unfollow -> {
                { repository.follow(handle, on = false) }
            }

            ProfileAction.RequestFriend -> {
                { repository.requestFriendship(handle) }
            }

            ProfileAction.AcceptFriend -> {
                { repository.acceptFriendship(handle) }
            }

            ProfileAction.RemoveFriend -> {
                { repository.removeFriendship(handle) }
            }

            ProfileAction.Mute -> {
                { repository.mute(userId, on = true) }
            }

            ProfileAction.Unmute -> {
                { repository.mute(userId, on = false) }
            }

            ProfileAction.Restrict -> {
                { repository.restrict(userId, on = true) }
            }

            ProfileAction.Unrestrict -> {
                { repository.restrict(userId, on = false) }
            }

            ProfileAction.Block -> {
                { repository.block(userId) }
            }
        }
        if (action != ProfileAction.Block) {
            if (confirm(call)) load()
            return
        }
        // Blocking leaves the profile (it no longer exists for the viewer); the tab bar shows the confirmation.
        val result = attempt(call)
        mutableError.value = result.apiError
        result.onSuccess {
            NetworkSignals.confirmation.value = it
            mutableLeave.value = true
        }
    }
}

/** What "Dein Profil" shows besides the loaded profile: the edited fields and the tone check. */
data class EditProfileState(
    val bio: String = "",
    val location: String = "",
    val color: String = "sun",
    val toneConfirmed: Boolean = false,
    val toneNudge: Boolean = false,
    val toneHint: String? = null,
    val uploading: Boolean = false
)

/**
 * "Dein Profil" (/app/profil): bio with tone hints, location, the optional photo and banner and the colour of the
 * initials. Who may see the profile is a privacy setting (account area).
 */
@HiltViewModel
class EditProfileViewModel @Inject constructor(private val repository: NetworkRepository, private val common: CommonApi) :
    NetworkViewModel() {
    private val mutableProfile = MutableStateFlow<Loadable<OwnProfile>>(Loadable.Idle)
    val profile: StateFlow<Loadable<OwnProfile>> = mutableProfile.asStateFlow()
    private val mutableForm = MutableStateFlow(EditProfileState())
    val form: StateFlow<EditProfileState> = mutableForm.asStateFlow()

    fun updateBio(value: String) = mutableForm.update { it.copy(bio = value) }

    fun updateLocation(value: String) = mutableForm.update { it.copy(location = value) }

    fun updateColor(value: String) = mutableForm.update { it.copy(color = value) }

    fun setToneConfirmed(value: Boolean) = mutableForm.update { it.copy(toneConfirmed = value) }

    suspend fun load() {
        mutableProfile.value = Loadable.Loading
        val result = attempt { repository.ownProfile() }
        result.onSuccess(::apply)
        result.apiError?.let { mutableProfile.value = Loadable.Failed(it) }
    }

    suspend fun loadIfNeeded() {
        if (profile.value.valueOrNull == null) load()
    }

    /** The tone check of bio and location while typing (500 ms after the last change). */
    suspend fun checkTone() {
        if (profile.value.valueOrNull == null) return
        mutableForm.update { it.copy(toneConfirmed = false) }
        val text = "${form.value.bio} ${form.value.location}".trim()
        // A failed check shows nothing.
        val result = attempt { common.toneHint(text, "profile") }.getOrNull() ?: return
        mutableForm.update { it.copy(toneHint = result.hint, toneNudge = result.nudge) }
    }

    suspend fun save() {
        mutableError.value = null
        val form = form.value
        val result = attempt { repository.updateOwnProfile(form.bio, form.location, form.color, form.toneConfirmed) }
        result.onSuccess { saved ->
            apply(saved.data)
            mutableStatus.value = saved.message
            NetworkSignals.didChangeProfile()
        }
        result.apiError?.let { failure ->
            if (failure.message("bio").orEmpty().startsWith(ComposeViewModel.TONE_QUESTION)) {
                mutableForm.update {
                    it.copy(toneNudge = true)
                }
            }
            mutableError.value = failure
        }
    }

    /** A picked photo (null when it could not be read): stored at once. */
    suspend fun uploadPhoto(image: ImageUpload?) = store(image) { repository.uploadPhoto(it) }

    suspend fun uploadBanner(image: ImageUpload?) = store(image) { repository.uploadProfileBanner(it) }

    suspend fun removePhoto() = change { repository.removePhoto() }

    suspend fun removeBanner() = change { repository.removeProfileBanner() }

    /** Marks the upload while the picked image is read and converted. */
    fun setUploading(value: Boolean) = mutableForm.update { it.copy(uploading = value) }

    private suspend fun store(image: ImageUpload?, upload: suspend (ImageUpload) -> Confirmed<OwnProfile>) {
        if (image == null) {
            setUploading(false)
            return
        }
        setUploading(true)
        mutableError.value = null
        change { upload(image) }
        setUploading(false)
    }

    private suspend fun change(action: suspend () -> Confirmed<OwnProfile>) {
        val result = attempt(action)
        result.onSuccess { saved ->
            mutableProfile.value = Loadable.Loaded(saved.data)
            mutableStatus.value = saved.message
            NetworkSignals.didChangeProfile()
        }
        result.apiError?.let { mutableError.value = it }
    }

    private fun apply(loaded: OwnProfile) {
        mutableForm.update {
            it.copy(
                bio = loaded.bio,
                location = loaded.location,
                color = loaded.avatarColor,
                toneHint = null,
                toneNudge = false,
                toneConfirmed = false
            )
        }
        mutableProfile.value = Loadable.Loaded(loaded)
    }
}

/** "Freund:innen" (/app/freunde): incoming requests, friends and own open requests. */
@HiltViewModel
class FriendsViewModel @Inject constructor(private val repository: NetworkRepository) : NetworkViewModel() {
    private val mutableLists = MutableStateFlow<Loadable<FriendLists>>(Loadable.Idle)
    val lists: StateFlow<Loadable<FriendLists>> = mutableLists.asStateFlow()

    suspend fun load() {
        mutableLists.load { repository.friends() }
    }

    suspend fun loadIfNeeded() {
        if (lists.value.valueOrNull == null) load()
    }

    suspend fun accept(userId: Int) {
        if (confirm { repository.acceptFriend(userId) }) load()
    }

    /** Declines a request, ends a friendship or withdraws an own request. */
    suspend fun remove(userId: Int) {
        if (confirm { repository.removeFriend(userId) }) load()
    }
}

/** "Deine Zahlen" (/app/statistik). */
@HiltViewModel
class StatisticsViewModel @Inject constructor(private val repository: NetworkRepository) : NetworkViewModel() {
    private val mutableStatistics = MutableStateFlow<Loadable<MemberStatistics>>(Loadable.Idle)
    val statistics: StateFlow<Loadable<MemberStatistics>> = mutableStatistics.asStateFlow()

    suspend fun load() {
        mutableStatistics.load { repository.statistics() }
        mutableError.value = null
    }

    suspend fun loadIfNeeded() {
        if (statistics.value.valueOrNull == null) load()
    }
}

/** "Menschen einladen" (/app/einladen): personal links within the monthly budget. */
@HiltViewModel
class InvitationsViewModel @Inject constructor(private val repository: NetworkRepository) : NetworkViewModel() {
    private val mutableOverview = MutableStateFlow<Loadable<InvitationOverview>>(Loadable.Idle)
    val overview: StateFlow<Loadable<InvitationOverview>> = mutableOverview.asStateFlow()

    suspend fun load() {
        mutableOverview.load { repository.invitations() }
    }

    suspend fun loadIfNeeded() {
        if (overview.value.valueOrNull == null) load()
    }

    /** A new link; the budget's message shows below the heading, anything else as a toast. */
    suspend fun create() {
        val result = attempt { repository.createInvitation() }
        mutableError.value = result.apiError
        result.onSuccess {
            mutableStatus.value = it
            load()
        }
        result.apiError?.let { failure -> if (failure.message("invitation") == null) mutableStatus.value = failure.userMessage }
    }
}

/** The page behind an invitation link (/einladung/{token}) for a signed-in member. */
@HiltViewModel(assistedFactory = InvitationViewModel.Factory::class)
class InvitationViewModel @AssistedInject constructor(@Assisted val token: String, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(token: String): InvitationViewModel
    }

    private val mutableLanding = MutableStateFlow<Loadable<InvitationLanding>>(Loadable.Idle)
    val landing: StateFlow<Loadable<InvitationLanding>> = mutableLanding.asStateFlow()

    suspend fun load() {
        mutableLanding.load { repository.invitation(token) }
    }
}
