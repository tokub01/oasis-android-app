package de.velwebsolutions.oasis.network.ui.profiles

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.network.api.TimeCapsuleInput
import de.velwebsolutions.oasis.network.api.TimeCapsules
import de.velwebsolutions.oasis.network.api.Yearbook
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.NetworkViewModel
import de.velwebsolutions.oasis.network.ui.apiError
import de.velwebsolutions.oasis.network.ui.attempt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A Zeitkapsel being written: text, the moment it opens and where it appears (null: the own Quelle). */
data class CapsuleDraft(
    val body: String = "",
    val opensAt: Instant = CapsuleDraft.suggestedOpening(Instant.now()),
    val oasisId: Int? = null
) {
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

/** Zeitkapseln (/app/zeitkapseln): seal a Tropfen for a later day, open a waiting one early or delete it. */
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

    fun updateOasis(oasisId: Int?) = mutableDraft.update { it.copy(oasisId = oasisId) }

    /** "Zeitkapsel versiegeln": a fresh draft and the new list once sealed; field errors stay in [error]. */
    suspend fun seal() {
        val current = draft.value
        if (confirm {
                repository.sealTimeCapsule(TimeCapsuleInput(current.body, CapsuleDraft.wireTime(current.opensAt), current.oasisId))
            }
        ) {
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
    }
}

/** Dein Jahrbuch (/app/jahrbuch/{year?}): the own Tropfen of a year; the year chips switch the book in place. */
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

/** "2 Tropfen · 3 Fotos · in 1 Oase" – the cover line of a Jahrbuch; the oases only when there are any. */
fun yearbookTotals(totals: Yearbook.Totals): String {
    val images = if (totals.images == 1) "1 Foto" else "${totals.images} Fotos"
    val oases = when (totals.oases) {
        0 -> ""
        1 -> " · in 1 Oase"
        else -> " · in ${totals.oases} Oasen"
    }
    return "${totals.posts} Tropfen · $images$oases"
}
