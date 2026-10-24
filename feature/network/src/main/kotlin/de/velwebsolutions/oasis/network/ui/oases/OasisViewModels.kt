package de.velwebsolutions.oasis.network.ui.oases

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisClosing
import de.velwebsolutions.oasis.network.api.OasisDetail
import de.velwebsolutions.oasis.network.api.OasisDirectory
import de.velwebsolutions.oasis.network.api.OasisEditForm
import de.velwebsolutions.oasis.network.api.OasisInput
import de.velwebsolutions.oasis.network.api.OasisMembers
import de.velwebsolutions.oasis.network.api.OasisOptions
import de.velwebsolutions.oasis.network.api.OasisStatistics
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.NetworkViewModel
import de.velwebsolutions.oasis.network.ui.PostListModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** "Oasen" (/app/oasen): the member's oases and all others. */
@HiltViewModel
class OasesViewModel @Inject constructor(private val repository: NetworkRepository) : NetworkViewModel() {
    private val mutableDirectory = MutableStateFlow<Loadable<OasisDirectory>>(Loadable.Idle)
    val directory: StateFlow<Loadable<OasisDirectory>> = mutableDirectory.asStateFlow()

    init {
        observe(NetworkSignals.oases) { load() }
    }

    suspend fun load() {
        mutableDirectory.load { repository.oases() }
    }

    suspend fun loadIfNeeded() {
        if (directory.value.valueOrNull == null) load()
    }
}

/**
 * An oasis page (/oasen/{slug}): the oasis, its Tropfen, joining and leaving and – for members – the member list with a
 * name filter.
 */
@HiltViewModel(assistedFactory = OasisViewModel.Factory::class)
class OasisViewModel @AssistedInject constructor(@Assisted val slug: String, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(slug: String): OasisViewModel
    }

    private val mutableDetail = MutableStateFlow<Loadable<OasisDetail>>(Loadable.Idle)
    val detail: StateFlow<Loadable<OasisDetail>> = mutableDetail.asStateFlow()
    val posts = PostListModel<NoContext>(repository) { _, cursor -> oasisPosts(slug, cursor) }
    private val mutableMembers = MutableStateFlow<OasisMembers?>(null)
    val members: StateFlow<OasisMembers?> = mutableMembers.asStateFlow()
    private val mutableMemberQuery = MutableStateFlow("")
    val memberQuery: StateFlow<String> = mutableMemberQuery.asStateFlow()

    init {
        observe(NetworkSignals.published) { posts.reload() }
        observe(NetworkSignals.oases) { load() }
    }

    fun updateMemberQuery(query: String) {
        mutableMemberQuery.value = query
    }

    suspend fun load() {
        val loaded = mutableDetail.load { repository.oasis(slug) } ?: return
        posts.reload()
        if (loaded.isMember) loadMembers() else mutableMembers.value = null
    }

    suspend fun loadIfNeeded() {
        if (detail.value.valueOrNull == null) load()
    }

    suspend fun loadMembers() {
        mutableMembers.value = runCatching { repository.oasisMembers(slug, memberQuery.value.take(60)) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
    }

    /** Follow (= become a member) or unfollow; the last host cannot leave (the server explains why). */
    suspend fun setFollowing(follow: Boolean) {
        if (confirm { repository.followOasis(slug, follow) }) {
            NetworkSignals.didChangeOases()
            load()
        }
    }
}

/** What the oasis form shows. */
data class OasisFormState(
    val input: OasisInput = OasisInput(),
    val options: OasisOptions? = null,
    val edit: OasisEditForm? = null,
    val loadError: ApiError? = null,
    /** The banner picked while founding; sent with "Oase eröffnen". */
    val banner: ImageUpload? = null,
    /** Where the form goes after saving. */
    val outcome: OasisFormOutcome? = null,
)

/** Where the oasis form goes after saving. */
sealed interface OasisFormOutcome {
    /** Back to the oasis (edited), or back after giving up the own host role (the confirmation goes to the tab). */
    data object Close : OasisFormOutcome

    /** The new oasis opens in place of the form. */
    data class Open(val slug: String) : OasisFormOutcome
}

/**
 * Found an oasis (/app/oasen/neu) or edit it as host (/oasen/{slug}/bearbeiten): texts, house rules, audience, colour,
 * the optional banner (stored at once when editing, with "Oase eröffnen" when founding), closing and roles.
 */
@HiltViewModel(assistedFactory = OasisFormViewModel.Factory::class)
class OasisFormViewModel @AssistedInject constructor(@Assisted val slug: String?, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(slug: String?): OasisFormViewModel
    }

    private val mutableState = MutableStateFlow(OasisFormState())
    val state: StateFlow<OasisFormState> = mutableState.asStateFlow()
    val editing: Boolean get() = slug != null

    fun updateInput(change: (OasisInput) -> OasisInput) = mutableState.update { it.copy(input = change(it.input)) }

    suspend fun load() {
        mutableState.update { it.copy(loadError = null) }
        try {
            if (slug != null) {
                val form = repository.oasisEditForm(slug)
                val input = OasisInput(form.oasis.name, form.oasis.description, form.houseRules, form.oasis.visibility, form.oasis.color)
                mutableState.update { it.copy(edit = form, options = form.options, input = input) }
            } else {
                val options = repository.oases().options
                mutableState.update { it.copy(options = options) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(loadError = ApiError.from(error)) }
        }
    }

    suspend fun loadIfNeeded() {
        if (state.value.options == null) load()
    }

    suspend fun save() {
        mutableError.value = null
        try {
            if (slug != null) {
                repository.updateOasis(slug, state.value.input)
                NetworkSignals.didChangeOases()
                mutableState.update { it.copy(outcome = OasisFormOutcome.Close) }
            } else {
                val created = repository.createOasis(state.value.input, state.value.banner)
                NetworkSignals.didChangeOases()
                mutableState.update { it.copy(outcome = OasisFormOutcome.Open(created)) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableError.value = ApiError.from(error)
        }
    }

    /** A picked banner: stored at once when editing, kept for "Oase eröffnen" when founding. */
    suspend fun pickBanner(upload: ImageUpload) {
        mutableError.value = null
        if (slug != null) {
            if (confirm { repository.uploadBanner(slug, upload) }) {
                NetworkSignals.didChangeOases()
                load()
            }
        } else {
            mutableState.update { it.copy(banner = upload) }
            mutableStatus.value = "Das Banner wird mit der Oase gespeichert."
        }
    }

    suspend fun removeBanner() {
        mutableState.update { it.copy(banner = null) }
        if (slug == null) {
            mutableStatus.value = "Das Banner ist entfernt. Jetzt zeigen wir wieder die Farbe der Oase."
            return
        }
        if (confirm { repository.removeBanner(slug) }) {
            NetworkSignals.didChangeOases()
            load()
        }
    }

    suspend fun setRole(userId: Int, role: String) {
        val slug = slug ?: return
        mutableError.value = null
        try {
            val change = repository.setRole(slug, userId, role)
            NetworkSignals.didChangeOases()
            if (change.data.stillHost) {
                mutableStatus.value = change.message
                load()
            } else {
                NetworkSignals.confirmation.value = change.message
                mutableState.update { it.copy(outcome = OasisFormOutcome.Close) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableError.value = ApiError.from(error)
        }
    }

    fun consumeOutcome() = mutableState.update { it.copy(outcome = null) }

    companion object {
        /** The fields with their own error line; anything else shows as a banner above the form. */
        val FIELDS = listOf("name", "description", "house_rules", "visibility", "color", "banner", "role")
    }
}

/** "„…“ schließen?" (/oasen/{slug}/schliessen): host-only confirmation with an explicit checkbox. */
@HiltViewModel(assistedFactory = CloseOasisViewModel.Factory::class)
class CloseOasisViewModel @AssistedInject constructor(@Assisted val slug: String, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(slug: String): CloseOasisViewModel
    }

    private val mutableClosing = MutableStateFlow<Loadable<OasisClosing>>(Loadable.Idle)
    val closing: StateFlow<Loadable<OasisClosing>> = mutableClosing.asStateFlow()
    private val mutableConfirmed = MutableStateFlow(false)
    val confirmed: StateFlow<Boolean> = mutableConfirmed.asStateFlow()
    private val mutableClosed = MutableStateFlow(false)

    /** Set after closing: the app goes to the oasis overview, which shows the confirmation. */
    val closed: StateFlow<Boolean> = mutableClosed.asStateFlow()

    fun setConfirmed(value: Boolean) {
        mutableConfirmed.value = value
    }

    suspend fun load() {
        mutableClosing.load { repository.oasisClosing(slug) }
    }

    suspend fun close() {
        mutableError.value = null
        try {
            NetworkSignals.confirmation.value = repository.closeOasis(slug, confirmed.value)
            NetworkSignals.didChangeOases()
            mutableClosed.value = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableError.value = ApiError.from(error)
        }
    }
}

/** Host statistics (/oasen/{slug}/statistik): sums only, never lists of people. */
@HiltViewModel(assistedFactory = OasisStatisticsViewModel.Factory::class)
class OasisStatisticsViewModel @AssistedInject constructor(@Assisted val slug: String, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(slug: String): OasisStatisticsViewModel
    }

    private val mutableStatistics = MutableStateFlow<Loadable<OasisStatistics>>(Loadable.Idle)
    val statistics: StateFlow<Loadable<OasisStatistics>> = mutableStatistics.asStateFlow()

    suspend fun load() {
        mutableStatistics.load { repository.oasisStatistics(slug) }
    }
}
