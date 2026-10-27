package de.velwebsolutions.oasis.membership.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.membership.api.MembershipPerks
import de.velwebsolutions.oasis.membership.api.SupporterInput
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.data.MembershipRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs [block] and returns its value, or its failure as [ApiError] (the field errors of a 422 included). A cancellation
 * (the screen closed) is no failure: it ends the caller as well.
 */
internal suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(ApiError.from(error))
}

/** Deine Vorteile: the perks and the outcome of the last action for the snackbar. */
data class PerksUiState(
    val perks: Loadable<MembershipPerks> = Loadable.Idle,
    val message: String? = null,
    val error: String? = null
)

/**
 * /app/mitgliedschaft/vorteile: what the tier includes; a Mäzen's companion places (create a link, end a place) and
 * supporter choices; a companion's held place (give it back).
 */
@HiltViewModel
class PerksViewModel @Inject constructor(
    private val repository: MembershipRepository,
    private val events: MembershipEvents,
    private val session: Session
) : ViewModel() {
    private val mutableState = MutableStateFlow(PerksUiState())
    val state: StateFlow<PerksUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** Loads the page again; a failed first load shows the error page, a failed refresh a snackbar. */
    suspend fun load() {
        mutableState.update { it.copy(perks = it.perks.refreshing()) }
        val result = attempt { repository.perks() }
        result.onSuccess { perks -> mutableState.update { it.copy(perks = Loadable.Loaded(perks)) } }
        result.onFailure { error ->
            mutableState.update {
                it.copy(perks = it.perks.failing(error), error = it.perks.valueOrNull?.let { (error as ApiError).userMessage })
            }
        }
    }

    /** "Link für einen Platz erstellen"; why none is free comes back as the snackbar. */
    suspend fun createSeat() {
        act { repository.createSeat() }
    }

    /** "Platz freigeben": the link stops working, or the companion loses the place. */
    suspend fun releaseSeat(id: Int) {
        act { repository.releaseSeat(id) }
    }

    /** "Platz zurückgeben": the held place ends, so the access and the overview are asked again. */
    suspend fun giveBack() {
        if (act { repository.leaveSeat() }) {
            events.changed()
            session.refreshMe()
        }
    }

    /** "Unterstützer-Zeichen am Profil" or "Auf der Dankesliste stehen" switched. */
    suspend fun updateSupporter(input: SupporterInput) {
        act { repository.updateSupporter(input) }
    }

    /** Hides the confirmation once the snackbar showed it. */
    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    /** Hides the error once the snackbar showed it. */
    fun dismissError() = mutableState.update { it.copy(error = null) }

    /** Runs an action whose answer is the server's confirmation, then reloads; returns whether it worked. */
    private suspend fun act(action: suspend () -> String): Boolean {
        val result = attempt(action)
        result.onSuccess { message ->
            mutableState.update { it.copy(message = message) }
            load()
        }
        result.onFailure { error -> mutableState.update { it.copy(error = (error as ApiError).userMessage) } }
        return result.isSuccess
    }
}

/** A companion link: who offers the place, and why accepting failed. */
data class CompanionLinkUiState(
    /** The sponsor's name; a loaded null means the link no longer works. */
    val sponsor: Loadable<String?> = Loadable.Idle,
    val error: String? = null
)

/**
 * /app/mitgliedschaft/mitkommen/{token}: "<Mäzen> nimmt dich mit nach Oasis" with "Platz annehmen". Opens without a
 * membership, because accepting is what gives one.
 */
@HiltViewModel(assistedFactory = CompanionLinkViewModel.Factory::class)
class CompanionLinkViewModel @AssistedInject constructor(
    @Assisted val token: String,
    private val repository: MembershipRepository,
    private val events: MembershipEvents,
    private val session: Session
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(token: String): CompanionLinkViewModel
    }

    private val mutableState = MutableStateFlow(CompanionLinkUiState())
    val state: StateFlow<CompanionLinkUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.copy(sponsor = Loadable.Loading) }
        val result = attempt { repository.companionSponsor(token) }
        mutableState.update {
            it.copy(sponsor = result.fold({ sponsor -> Loadable.Loaded(sponsor) }, { error -> Loadable.Failed(error as ApiError) }))
        }
    }

    /**
     * "Platz annehmen": [onAccepted] leaves the page (to the Quelle) before the member is asked again, so the tabs that
     * open with the new access do not show this link once more; the overview gets the welcome.
     */
    suspend fun accept(onAccepted: () -> Unit) {
        val result = attempt { repository.acceptCompanionLink(token) }
        result.onFailure { error -> mutableState.update { it.copy(error = (error as ApiError).userMessage) } }
        result.onSuccess { welcome ->
            events.changed(message = welcome)
            onAccepted()
            session.refreshMe()
        }
    }

    /** Hides the error once the snackbar showed it. */
    fun dismissError() = mutableState.update { it.copy(error = null) }
}
