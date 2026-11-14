package de.velwebsolutions.oasis.network.ui.profiles

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.network.api.CapsuleInvitation
import de.velwebsolutions.oasis.network.api.Member
import de.velwebsolutions.oasis.network.api.TimeCapsuleInput
import de.velwebsolutions.oasis.network.api.TimeCapsules
import de.velwebsolutions.oasis.network.api.Yearbook
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.NetworkViewModel
import de.velwebsolutions.oasis.network.ui.apiError
import de.velwebsolutions.oasis.network.ui.attempt
import de.velwebsolutions.oasis.network.ui.components.postsLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A Zeitkapsel being written: text, the moment it opens and where it appears (null: the own Start). */
data class CapsuleDraft(
    val body: String = "",
    val opensAt: Instant = CapsuleDraft.suggestedOpening(Instant.now()),
    val oasisId: Int? = null,
    /** „Gemeinsam versiegeln“: the invited usernames (up to 10) and „Beitragen bis“ (none: an hour before opening). */
    val shared: Boolean = false,
    val invitees: Set<String> = emptySet(),
    val contributeUntil: Instant? = null
) {
    /** What „Zeitkapsel versiegeln“ sends. */
    val input: TimeCapsuleInput
        get() = TimeCapsuleInput(
            body,
            wireTime(opensAt),
            oasisId,
            invitees = if (shared) invitees.sorted() else emptyList(),
            contributeUntil = contributeUntil?.takeIf { shared }?.let { wireTime(it) }
        )

    companion object {
        private val Wire = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

        /** Tomorrow at the full hour: a moment the server accepts (at least one hour ahead). */
        fun suggestedOpening(now: Instant): Instant = now.plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)

        /** `opens_at` as the server reads it: ISO 8601 with the device's offset, e.g. 2026-12-24T18:00:00+01:00. */
        fun wireTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = Wire.format(instant.atZone(zone))

        /** The latest opening the picker offers: [maxYears] (`max_years`) from [now] (iOS `latestDay`). */
        fun latestOpening(now: Instant, maxYears: Int, zone: ZoneId = ZoneId.systemDefault()): Instant =
            now.atZone(zone).plusYears(maxYears.toLong()).toInstant()

        /** [text] cut to [maxLength] characters (code points, as the server counts; iOS caps the same way). */
        fun capped(text: String, maxLength: Int): String =
            if (text.codePointCount(0, text.length) <= maxLength) text else text.substring(0, text.offsetByCodePoints(0, maxLength))
    }
}

/** Zeitkapseln (/app/zeitkapseln): seal a Post for a later day, open a waiting one early or delete it. */
@HiltViewModel
class TimeCapsulesViewModel @Inject constructor(private val repository: NetworkRepository) : NetworkViewModel() {
    private val mutableCapsules = MutableStateFlow<Loadable<TimeCapsules>>(Loadable.Idle)
    val capsules: StateFlow<Loadable<TimeCapsules>> = mutableCapsules.asStateFlow()

    private val mutableDraft = MutableStateFlow(CapsuleDraft())
    val draft: StateFlow<CapsuleDraft> = mutableDraft.asStateFlow()

    suspend fun load() {
        mutableCapsules.load { repository.timeCapsules() }
    }

    suspend fun loadIfNeeded() {
        if (capsules.value.valueOrNull == null) load()
    }

    /** The text, at most `max_length` characters (500 until the capsules are loaded). */
    fun updateBody(body: String) {
        val max = capsules.value.valueOrNull?.maxLength ?: DEFAULT_MAX_LENGTH
        mutableDraft.update { it.copy(body = CapsuleDraft.capped(body, max)) }
    }

    fun updateOpensAt(opensAt: Instant) = mutableDraft.update { it.copy(opensAt = opensAt) }

    /** Where it appears; the invitable people change with it (members of that crowd). */
    suspend fun updateOasis(oasisId: Int?) {
        mutableDraft.update { it.copy(oasisId = oasisId) }
        if (draft.value.shared) loadCandidates()
    }

    private val mutableCandidates = MutableStateFlow<List<Member>?>(null)

    /** Who may be invited: friends, plus the members of the chosen crowd (null while not loaded). */
    val candidates: StateFlow<List<Member>?> = mutableCandidates.asStateFlow()

    /** „Gemeinsam versiegeln“ on or off; on, the invitable people load. */
    suspend fun setShared(shared: Boolean) {
        mutableDraft.update { it.copy(shared = shared) }
        if (shared) loadCandidates()
    }

    private suspend fun loadCandidates() {
        mutableCandidates.value = attempt { repository.capsuleInvitees(draft.value.oasisId) }.getOrNull().orEmpty()
    }

    /** A person in or out (at most 10). */
    fun toggleInvitee(username: String) = mutableDraft.update { draft ->
        when {
            username in draft.invitees -> draft.copy(invitees = draft.invitees - username)
            draft.invitees.size < MAX_INVITEES -> draft.copy(invitees = draft.invitees + username)
            else -> draft
        }
    }

    fun updateContributeUntil(until: Instant) = mutableDraft.update { it.copy(contributeUntil = until) }

    private val mutableContributions = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** The own texts of the invitations being written, by invitation. */
    val contributions: StateFlow<Map<Int, String>> = mutableContributions.asStateFlow()

    private val mutableContributionErrors = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** A refused text's message, by invitation. */
    val contributionErrors: StateFlow<Map<Int, String>> = mutableContributionErrors.asStateFlow()

    /** The text being written for [invitation] (at first its stored text). */
    fun contributionOf(invitation: CapsuleInvitation): String = contributions.value[invitation.id] ?: invitation.body.orEmpty()

    fun updateContribution(id: Int, text: String) {
        mutableContributions.update { it + (id to CapsuleDraft.capped(text, capsules.value.valueOrNull?.maxLength ?: DEFAULT_MAX_LENGTH)) }
    }

    /** „Beitragen“ / „Beitrag speichern“. */
    suspend fun saveContribution(invitation: CapsuleInvitation) =
        changeInvitation(invitation.id) { repository.contribute(invitation.id, contributionOf(invitation)) }

    /** „Beitrag entfernen“. */
    suspend fun removeContribution(invitation: CapsuleInvitation) =
        changeInvitation(invitation.id) { repository.removeContribution(invitation.id) }

    /** „Ablehnen“ (after confirming): final, the own text goes. */
    suspend fun decline(invitation: CapsuleInvitation) = changeInvitation(invitation.id) { repository.declineInvitation(invitation.id) }

    private suspend fun changeInvitation(id: Int, request: suspend () -> Confirmed<CapsuleInvitation>) {
        val result = attempt(request)
        val failure = result.apiError
        val field = failure?.message("body")
        mutableContributionErrors.update { if (field == null) it - id else it + (id to field) }
        mutableError.value = failure?.takeIf { field == null }
        val answer = result.getOrNull() ?: return
        mutableStatus.value = answer.message
        mutableContributions.update { it - id }
        mutableCapsules.update { loaded ->
            val info = loaded.valueOrNull ?: return@update loaded
            Loadable.Loaded(info.copy(invitations = info.invitations.map { if (it.id == id) answer.data else it }))
        }
    }

    /** "Zeitkapsel versiegeln": a fresh draft and the new list once sealed; field errors stay in [error]. */
    suspend fun seal() {
        val current = draft.value
        if (confirm { repository.sealTimeCapsule(current.input) }) {
            mutableDraft.value = CapsuleDraft()
            load()
        }
    }

    /** "Jetzt öffnen": the confirmation says whether it opened or why it could not. */
    suspend fun openNow(id: Int) {
        if (confirm { repository.openTimeCapsule(id) }) load()
    }

    suspend fun delete(id: Int) {
        if (confirm { repository.deleteTimeCapsule(id) }) load()
    }

    private companion object {
        /** `TimeCapsules.maxLength` before the list answered. */
        const val DEFAULT_MAX_LENGTH = 500

        /** „Höchstens 10 Personen.“ */
        const val MAX_INVITEES = 10
    }
}

/** Dein Jahrbuch (/app/jahrbuch/{year?}): the own Posts of a year; the year chips switch the book in place. */
@HiltViewModel(assistedFactory = YearbookViewModel.Factory::class)
class YearbookViewModel @AssistedInject constructor(@Assisted year: Int?, private val repository: NetworkRepository) : NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(year: Int?): YearbookViewModel
    }

    private var year: Int? = year
    private val mutableBook = MutableStateFlow<Loadable<Yearbook>>(Loadable.Idle)
    val book: StateFlow<Loadable<Yearbook>> = mutableBook.asStateFlow()

    /**
     * The book of the year chosen last; pull to refresh loads it again. The answer for a year tapped before comes too
     * late and is dropped, so the shown book always is the last tapped one.
     */
    suspend fun load() {
        val requested = year
        mutableBook.value = mutableBook.value.refreshing()
        val result = attempt { repository.yearbook(requested) }
        if (requested != year) return
        result.onSuccess { mutableBook.value = Loadable.Loaded(it) }
        result.apiError?.let { error ->
            if (mutableBook.value.valueOrNull == null) mutableBook.value = Loadable.Failed(error) else mutableError.value = error
        }
    }

    suspend fun loadIfNeeded() {
        if (book.value.valueOrNull == null) load()
    }

    /** Another year: its book replaces the shown one (placeholder while it loads). */
    suspend fun show(year: Int) {
        this.year = year
        mutableBook.value = Loadable.Loading
        load()
    }
}

/** "2 Posts · 3 Fotos · in 1 Crowd" – the cover line of a Jahrbuch; the crowds only when there are any. */
fun yearbookTotals(totals: Yearbook.Totals): String {
    val images = if (totals.images == 1) "1 Foto" else "${totals.images} Fotos"
    val oases = when (totals.oases) {
        0 -> ""
        1 -> " · in 1 Crowd"
        else -> " · in ${totals.oases} Crowds"
    }
    return "${postsLabel(totals.posts)} · $images$oases"
}
