package de.velwebsolutions.oasis.network.ui.oases

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.network.api.CollectionInput
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.Meetup
import de.velwebsolutions.oasis.network.api.MeetupInput
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisClosing
import de.velwebsolutions.oasis.network.api.OasisCollection
import de.velwebsolutions.oasis.network.api.OasisCollections
import de.velwebsolutions.oasis.network.api.OasisDetail
import de.velwebsolutions.oasis.network.api.OasisDirectory
import de.velwebsolutions.oasis.network.api.OasisEditForm
import de.velwebsolutions.oasis.network.api.OasisInput
import de.velwebsolutions.oasis.network.api.OasisMeetups
import de.velwebsolutions.oasis.network.api.OasisMembers
import de.velwebsolutions.oasis.network.api.OasisOptions
import de.velwebsolutions.oasis.network.api.OasisStatistics
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.NetworkViewModel
import de.velwebsolutions.oasis.network.ui.PostListModel
import de.velwebsolutions.oasis.network.ui.apiError
import de.velwebsolutions.oasis.network.ui.attempt
import java.time.Instant
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
    private val mutableMeetups = MutableStateFlow<OasisMeetups?>(null)

    /** „Treffen“ for members (null for others, or while not loaded). */
    val meetups: StateFlow<OasisMeetups?> = mutableMeetups.asStateFlow()
    private val mutableCollections = MutableStateFlow<OasisCollections?>(null)

    /** „Sammlungen“ for everyone who sees the oasis (null while not loaded, or when they cannot load). */
    val collections: StateFlow<OasisCollections?> = mutableCollections.asStateFlow()
    private val mutableShownCollection = MutableStateFlow<PostListModel<NoContext>?>(null)

    /** The Tropfen of the chosen collection, newest added first (null: none chosen). */
    val shownCollection: StateFlow<PostListModel<NoContext>?> = mutableShownCollection.asStateFlow()
    private val mutableShownCollectionId = MutableStateFlow<Int?>(null)

    /** The chosen collection. */
    val shownCollectionId: StateFlow<Int?> = mutableShownCollectionId.asStateFlow()
    private val mutableCollectionForm = MutableStateFlow<CollectionForm?>(null)

    /** The form to create or rename a collection, while it is open. */
    val collectionForm: StateFlow<CollectionForm?> = mutableCollectionForm.asStateFlow()
    private val mutableMeetupForm = MutableStateFlow<MeetupForm?>(null)

    /** The form to create or change a meetup, while it is open. */
    val meetupForm: StateFlow<MeetupForm?> = mutableMeetupForm.asStateFlow()

    init {
        observe(NetworkSignals.published) { posts.reload() }
        observe(NetworkSignals.oases) { load() }
        observe(NetworkSignals.collections) {
            loadCollections()
            posts.reload()
            shownCollection.value?.reload()
        }
    }

    fun updateMemberQuery(query: String) {
        mutableMemberQuery.value = query
    }

    suspend fun load() {
        val loaded = mutableDetail.load { repository.oasis(slug) } ?: return
        posts.reload()
        loadCollections()
        if (loaded.isMember) {
            loadMembers()
            loadMeetups()
        } else {
            mutableMembers.value = null
            mutableMeetups.value = null
        }
    }

    /**
     * Loads the page once. What a load cancelled with the screen (a rotation) did not get to loads again with it: the
     * Tropfen, and for members the member list.
     */
    suspend fun loadIfNeeded() {
        val loaded = detail.value.valueOrNull
        if (loaded == null) {
            load()
            return
        }
        posts.loadIfNeeded()
        if (loaded.isMember && members.value == null) loadMembers()
        if (loaded.isMember && meetups.value == null) loadMeetups()
        if (collections.value == null) loadCollections()
    }

    /** The collections; a list that cannot load is simply not shown. */
    suspend fun loadCollections() {
        mutableCollections.value = attempt { repository.collections(slug) }.getOrNull()
    }

    /** Shows the Tropfen of collection [id] (the chosen one again: none). */
    suspend fun chooseCollection(id: Int?) {
        val chosen = id?.takeIf { it != shownCollectionId.value }
        mutableShownCollectionId.value = chosen
        val list = chosen?.let { collection ->
            PostListModel<NoContext>(repository, collectionId = collection) { _, cursor -> collectionPage(collection, cursor) }
        }
        mutableShownCollection.value = list
        list?.reload()
    }

    /** „Sammlung anlegen“ (null) or „Ändern“. */
    fun openCollectionForm(collection: OasisCollection?) {
        mutableCollectionForm.value = collection?.let { CollectionForm(it.id, it.name, it.description.orEmpty()) } ?: CollectionForm()
    }

    fun editCollectionForm(change: (CollectionForm) -> CollectionForm) = mutableCollectionForm.update { it?.let(change) }

    fun closeCollectionForm() {
        mutableCollectionForm.value = null
    }

    /** „Sammlung anlegen“ / „Änderung speichern“; refused fields stay on the form. */
    suspend fun saveCollection() {
        val form = collectionForm.value ?: return
        val id = form.editing
        val input = CollectionInput(form.name.trim(), form.description.trim())
        val result = attempt { if (id == null) repository.createCollection(slug, input) else repository.updateCollection(id, input) }
        val saved = result.getOrNull()
        if (saved == null) {
            mutableCollectionForm.value = form.copy(error = result.apiError)
            return
        }
        mutableStatus.value = saved.message
        mutableCollectionForm.value = null
        loadCollections()
    }

    /** „Sammlung löschen“ (after confirming): its Tropfen stay in the oasis. */
    suspend fun deleteCollection(collection: OasisCollection) {
        if (!confirm { repository.deleteCollection(collection.id) }) return
        if (shownCollectionId.value == collection.id) chooseCollection(null)
        loadCollections()
        posts.reload()
    }

    /** „Mitglieder dürfen Sammlungen anlegen“ (hosts). */
    suspend fun setMembersCreateCollections(allowed: Boolean) {
        val result = attempt { repository.setMembersCreateCollections(slug, allowed) }
        mutableError.value = result.apiError
        val stored = result.getOrNull() ?: return
        mutableCollections.update { it?.copy(membersCreateCollections = stored.membersCreateCollections) }
    }

    /** The meetups of the oasis; a list that cannot load is simply not shown. */
    suspend fun loadMeetups() {
        mutableMeetups.value = attempt { repository.meetups(slug) }.getOrNull()
    }

    /** „Treffen anlegen“ (null) or „Ändern“ of [meetup]: the form, filled with the meetup or empty for tomorrow evening. */
    fun openMeetupForm(meetup: Meetup?, now: Instant = Instant.now()) {
        mutableMeetupForm.value = meetup?.let(MeetupForm::of) ?: MeetupForm(startsAt = MeetupForm.tomorrowEvening(now))
    }

    fun editMeetupForm(change: (MeetupForm) -> MeetupForm) = mutableMeetupForm.update { it?.let(change) }

    /** „Abbrechen“. */
    fun closeMeetupForm() {
        mutableMeetupForm.value = null
    }

    /** „Treffen anlegen“ / „Änderung speichern“; refused fields stay on the form with their messages. */
    suspend fun saveMeetup() {
        val form = meetupForm.value ?: return
        val id = form.editing
        val result = attempt { if (id == null) repository.createMeetup(slug, form.input) else repository.updateMeetup(id, form.input) }
        val saved = result.getOrNull()
        if (saved == null) {
            mutableMeetupForm.value = form.copy(error = result.apiError)
            return
        }
        mutableStatus.value = saved.message
        mutableMeetupForm.value = null
        loadMeetups()
    }

    /** „Ich komme“, „Vielleicht“, „Nein“. */
    suspend fun answerMeetup(meetup: Meetup, answer: String) = changeMeetup { repository.answerMeetup(meetup.id, answer) }

    /** „Treffen absagen“ (after confirming); who said yes or maybe gets a hint. */
    suspend fun cancelMeetup(meetup: Meetup) = changeMeetup { repository.cancelMeetup(meetup.id) }

    private suspend fun changeMeetup(request: suspend () -> Confirmed<Meetup>) {
        val result = attempt(request)
        mutableError.value = result.apiError
        val answer = result.getOrNull() ?: return
        mutableStatus.value = answer.message
        mutableMeetups.update { list ->
            list?.copy(
                upcoming = list.upcoming.map { if (it.id == answer.data.id) answer.data else it },
                past = list.past.map { if (it.id == answer.data.id) answer.data else it }
            )
        }
    }

    /** „Mitglieder dürfen Treffen anlegen“ (hosts). */
    suspend fun setMembersCreateMeetups(allowed: Boolean) {
        val result = attempt { repository.setMembersCreateMeetups(slug, allowed) }
        mutableError.value = result.apiError
        val stored = result.getOrNull() ?: return
        mutableMeetups.update { it?.copy(membersCreateMeetups = stored.membersCreateMeetups) }
    }

    /** „Ich bin gern Willkommenspate oder -patin“. */
    suspend fun setWelcomeVolunteer(volunteer: Boolean) {
        val result = attempt { repository.setWelcomeVolunteer(slug, volunteer) }
        mutableError.value = result.apiError
        val stored = result.getOrNull() ?: return
        val loaded = detail.value.valueOrNull ?: return
        mutableDetail.value = Loadable.Loaded(loaded.copy(welcome = loaded.welcome?.copy(volunteer = stored.volunteer)))
    }

    suspend fun loadMembers() {
        // A list that cannot load is simply not shown.
        mutableMembers.value = attempt { repository.oasisMembers(slug, memberQuery.value.take(60)) }.getOrNull()
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
    val outcome: OasisFormOutcome? = null
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

    /** The form: an edited oasis with its saved texts, banner and roles, or the choices for founding one. */
    suspend fun load() {
        val result = attempt {
            if (slug != null) {
                val form = repository.oasisEditForm(slug)
                val oasis = form.oasis
                val input =
                    OasisInput(
                        oasis.name,
                        oasis.description,
                        form.houseRules,
                        oasis.visibility,
                        oasis.color,
                        oasis.region?.code,
                        oasis.city.orEmpty()
                    )
                mutableState.update { it.copy(edit = form, options = form.options, input = input) }
            } else {
                val options = repository.oases().options
                mutableState.update { it.copy(options = options) }
            }
        }
        mutableState.update { it.copy(loadError = result.apiError) }
    }

    suspend fun loadIfNeeded() {
        if (state.value.options == null) load()
    }

    suspend fun save() {
        val result = attempt {
            if (slug != null) {
                repository.updateOasis(slug, state.value.input)
                OasisFormOutcome.Close
            } else {
                OasisFormOutcome.Open(repository.createOasis(state.value.input, state.value.banner))
            }
        }
        mutableError.value = result.apiError
        result.onSuccess { outcome ->
            NetworkSignals.didChangeOases()
            mutableState.update { it.copy(outcome = outcome) }
        }
    }

    /** A picked banner (null when it could not be read): stored at once when editing, kept for "Oase eröffnen" when founding. */
    suspend fun pickBanner(upload: ImageUpload?) {
        upload ?: return
        mutableError.value = null
        if (slug != null) {
            if (confirm { repository.uploadBanner(slug, upload) }) {
                NetworkSignals.didChangeOases()
                refresh(slug)
            }
        } else {
            mutableState.update { it.copy(banner = upload) }
            mutableStatus.value = "Das Banner wird mit der Crowd gespeichert."
        }
    }

    suspend fun removeBanner() {
        mutableState.update { it.copy(banner = null) }
        if (slug == null) {
            mutableStatus.value = "Das Banner ist entfernt. Jetzt zeigen wir wieder die Farbe der Crowd."
            return
        }
        if (confirm { repository.removeBanner(slug) }) {
            NetworkSignals.didChangeOases()
            refresh(slug)
        }
    }

    suspend fun setRole(userId: Int, role: String) {
        val slug = slug ?: return
        val result = attempt { repository.setRole(slug, userId, role) }
        mutableError.value = result.apiError
        val change = result.getOrNull() ?: return
        NetworkSignals.didChangeOases()
        if (change.data.stillHost) {
            mutableStatus.value = change.message
            refresh(slug)
        } else {
            NetworkSignals.confirmation.value = change.message
            mutableState.update { it.copy(outcome = OasisFormOutcome.Close) }
        }
    }

    fun consumeOutcome() = mutableState.update { it.copy(outcome = null) }

    /**
     * The banner and the roles again after a stored banner or a changed role. What the member typed into the form and has
     * not saved yet stays, and a refresh that fails keeps the form (its error shows above it).
     */
    private suspend fun refresh(slug: String) {
        val result = attempt { repository.oasisEditForm(slug) }
        result.onSuccess { form -> mutableState.update { it.copy(edit = form, options = form.options) } }
        mutableError.value = result.apiError
    }

    companion object {
        /** The fields with their own error line; anything else shows as a banner above the form. */
        val FIELDS = listOf("name", "description", "house_rules", "visibility", "color", "banner", "role", "region", "city")
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
        val result = attempt { repository.closeOasis(slug, confirmed.value) }
        mutableError.value = result.apiError
        result.onSuccess { message ->
            NetworkSignals.confirmation.value = message
            NetworkSignals.didChangeOases()
            mutableClosed.value = true
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

/**
 * The form of a meetup: title (≤ 120), date and time in German time (in the future), place (≤ 200), link (http(s),
 * ≤ 500) and description (≤ 2000); [editing] names the meetup being changed, [error] the server's refusal.
 */
data class MeetupForm(
    val editing: Int? = null,
    val title: String = "",
    val startsAt: Instant,
    val place: String = "",
    val link: String = "",
    val description: String = "",
    val error: ApiError? = null
) {
    /** What is sent: every field, empty ones as "". */
    val input: MeetupInput get() = MeetupInput(
        title.trim(),
        OasisJson.formatInstant(startsAt),
        place.trim(),
        link.trim(),
        description.trim()
    )

    companion object {
        fun of(meetup: Meetup) = MeetupForm(
            editing = meetup.id,
            title = meetup.title,
            startsAt = meetup.startsAt,
            place = meetup.place.orEmpty(),
            link = meetup.link.orEmpty(),
            description = meetup.description.orEmpty()
        )

        /** A start to begin with: tomorrow at 18:00 German time. */
        fun tomorrowEvening(now: Instant): Instant =
            now.atZone(GermanTime.zone).toLocalDate().plusDays(1).atTime(18, 0).atZone(GermanTime.zone).toInstant()
    }
}

/** The form of a collection: name (1–60) and description (≤ 300); [editing] names the one being renamed. */
data class CollectionForm(val editing: Int? = null, val name: String = "", val description: String = "", val error: ApiError? = null)

/** A page of a collection's Tropfen as a list page: the next page number as the cursor while there is one. */
internal suspend fun NetworkRepository.collectionPage(id: Int, cursor: String?): FeedPage<NoContext> {
    val page = collectionPosts(id, cursor?.toIntOrNull() ?: 1)
    return FeedPage(page.data, page.meta)
}
