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

/** Einstieg (/app/willkommen): four skippable steps; finishing or skipping ends the tour for good. */
@HiltViewModel
class WelcomeViewModel @Inject constructor(private val repository: AccountRepository, private val session: Session) : ViewModel() {
    private val mutableUi = MutableStateFlow(WelcomeUiState())
    val ui: StateFlow<WelcomeUiState> = mutableUi.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun edit(change: (WelcomeUiState) -> WelcomeUiState) = mutableUi.update(change)

    suspend fun load() {
        val firstLoad = ui.value.state.valueOrNull == null
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { repository.welcome() }
            .onSuccess { tour ->
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

    /** „Weiter“: saves the current step (oases save on tap) and moves on. */
    suspend fun next() {
        mutableUi.update { it.copy(fieldErrors = emptyMap(), error = null) }
        val current = ui.value
        catching {
            when (current.stepKey) {
                "profil" -> repository.saveWelcomeProfile(current.bio, current.location, current.avatarColor, current.toneConfirmed)
                "sichtbarkeit" -> repository.saveWelcomeVisibility(current.profileAudience, current.defaultPostVisibility)
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
