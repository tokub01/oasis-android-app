package de.velwebsolutions.oasis.account.ui.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.api.StarterOasis
import de.velwebsolutions.oasis.account.api.WelcomeStep
import de.velwebsolutions.oasis.account.api.WelcomeTour
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.asApiError
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the welcome tour shows. */
data class WelcomeUiState(
    val state: Loadable<WelcomeTour> = Loadable.Idle,
    val step: Int = 1,
    val bio: String = "",
    val location: String = "",
    val avatarColor: String = "sun",
    val profileAudience: String = "members",
    val defaultPostVisibility: String = "members",
    /**
     * The member picked who sees them in this step, and no other profile audience was saved elsewhere since; until then
     * the step shows what is stored and saves nothing.
     */
    val visibilityChanged: Boolean = false,
    /** "So speichern – ich meine es genau so." */
    val toneConfirmed: Boolean = false,
    val toneQuestion: Boolean = false,
    val toneHint: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val error: String? = null
) {
    val steps: List<WelcomeStep> get() = state.valueOrNull?.steps.orEmpty()
    val stepKey: String get() = steps.getOrNull(step - 1)?.key ?: "profil"
}

/**
 * Einstieg (/app/willkommen): four skippable steps; finishing or skipping ends the tour for good. „Wer dich sieht“ links
 * to the Privatsphäre-Check, which saves on its own: the step saves only what the member picked in it, and shown again it
 * takes over what is stored now ([appeared]) – also over its own unsaved pick when the check saved another profile
 * audience meanwhile, since that is the later choice. So a stricter choice made in the check is never turned back.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(private val repository: AccountRepository, private val session: Session) : ViewModel() {
    private val mutableUi = MutableStateFlow(WelcomeUiState())
    val ui: StateFlow<WelcomeUiState> = mutableUi.asStateFlow()

    /** The tour was shown before; until then the first load is the one started in `init`. */
    private var shown = false

    /**
     * Who may see the profile as stored when the tour last loaded or saved it; a different one later was saved elsewhere.
     * Only this one counts: the step's save stores just the profile audience (the server ignores the default audience of
     * new Tropfen there), so only it could turn back a choice made in the check.
     */
    private var storedAudience: String? = null

    init {
        viewModelScope.launch { load() }
    }

    fun edit(change: (WelcomeUiState) -> WelcomeUiState) = mutableUi.update(change)

    /**
     * The tour is shown (back from the Privatsphäre-Check or another app): after the first time „Wer dich sieht“ shows
     * what is stored now. A pick of the member in the step stays only while no other profile audience was saved
     * elsewhere meanwhile.
     */
    suspend fun appeared() {
        if (!shown) {
            shown = true
            return
        }
        catching { repository.welcome() }.onSuccess { tour ->
            val savedElsewhere = tour.profileAudience != storedAudience
            storedAudience = tour.profileAudience
            mutableUi.update {
                if (it.visibilityChanged && !savedElsewhere) {
                    it
                } else {
                    it.copy(
                        profileAudience = tour.profileAudience,
                        defaultPostVisibility = tour.defaultPostVisibility,
                        visibilityChanged = false
                    )
                }
            }
        }
    }

    suspend fun load() {
        val firstLoad = ui.value.state.valueOrNull == null
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { repository.welcome() }
            .onSuccess { tour ->
                if (firstLoad) storedAudience = tour.profileAudience
                mutableUi.update {
                    if (firstLoad) {
                        it.copy(
                            state = Loadable.Loaded(tour),
                            bio = tour.bio,
                            location = tour.location,
                            avatarColor = tour.avatarColor,
                            profileAudience = tour.profileAudience,
                            defaultPostVisibility = tour.defaultPostVisibility
                        )
                    } else {
                        it.copy(state = Loadable.Loaded(tour))
                    }
                }
            }
            .onFailure { error ->
                mutableUi.update { it.copy(state = it.state.failing(error)) }
            }
    }

    /** Live hint while typing (slang address, local word list); a change asks the question again. */
    suspend fun textChanged() {
        mutableUi.update { it.copy(toneConfirmed = false, toneQuestion = false) }
        val text = "${ui.value.bio} ${ui.value.location}".trim()
        if (text.isEmpty() || text.length > 400) {
            mutableUi.update { it.copy(toneHint = null) }
            return
        }
        // The hint is a courtesy; without it the profile is checked when it is saved.
        catching { repository.profileTone(text) }
            .onSuccess { tone ->
                mutableUi.update { it.copy(toneHint = tone.hint, toneQuestion = tone.nudge) }
            }
    }

    /** „Weiter“: saves the current step (oases save on tap; „Wer dich sieht“ only when picked in it) and moves on. */
    suspend fun next() {
        mutableUi.update { it.copy(fieldErrors = emptyMap(), error = null) }
        val current = ui.value
        catching {
            when (current.stepKey) {
                "profil" -> repository.saveWelcomeProfile(current.bio, current.location, current.avatarColor, current.toneConfirmed)

                "sichtbarkeit" -> if (current.visibilityChanged) {
                    repository.saveWelcomeVisibility(current.profileAudience, current.defaultPostVisibility)
                    storedAudience = current.profileAudience
                    mutableUi.update { it.copy(visibilityChanged = false) }
                }
            }
        }
            .onSuccess { go(current.step + 1) }
            .onFailure { error ->
                val failure = error.asApiError()
                val fields = failure.fieldMessages
                mutableUi.update {
                    when {
                        fields.isEmpty() -> it.copy(error = failure.userMessage)
                        it.stepKey == "profil" && fields["bio"] != null -> it.copy(fieldErrors = fields, toneQuestion = true)
                        else -> it.copy(fieldErrors = fields)
                    }
                }
            }
    }

    fun skip() = go(ui.value.step + 1)

    fun back() = go(ui.value.step - 1)

    suspend fun toggle(oasis: StarterOasis) {
        catching { repository.toggleStarterOasis(oasis.id) }
            .onSuccess { joined ->
                mutableUi.update { state ->
                    val tour = state.state.valueOrNull ?: return@update state
                    state.copy(
                        state = Loadable.Loaded(
                            tour.copy(
                                oases = tour.oases.map {
                                    if (it.id ==
                                        oasis.id
                                    ) {
                                        it.copy(joined = joined)
                                    } else {
                                        it
                                    }
                                }
                            )
                        )
                    )
                }
            }
            .onFailure { error ->
                mutableUi.update { it.copy(error = error.asApiError().userMessage) }
            }
    }

    /**
     * „Los geht's“ or „Später – direkt zur Quelle“: ends the tour and reloads `me`. Returns whether the tour was opened
     * again by an onboarded member (the screen then goes back to where it was opened from); a new member's app shows
     * the Quelle by itself once `me` is onboarded.
     */
    suspend fun finish(): Boolean? {
        catching { repository.finishWelcome() }
            .onFailure { error ->
                mutableUi.update { it.copy(error = error.asApiError().userMessage) }
                return null
            }
        val wasOnboarded = ui.value.state.valueOrNull?.onboarded ?: true
        session.refreshMe()
        return wasOnboarded
    }

    private fun go(target: Int) {
        mutableUi.update { it.copy(step = target.coerceIn(1, maxOf(it.steps.size, 1)), fieldErrors = emptyMap()) }
    }
}
