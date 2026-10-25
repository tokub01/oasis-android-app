package de.velwebsolutions.oasis.account.ui.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.api.HiddenWords
import de.velwebsolutions.oasis.account.api.PrivacyCentre
import de.velwebsolutions.oasis.account.api.PrivacySection
import de.velwebsolutions.oasis.account.api.PrivacySettingItem
import de.velwebsolutions.oasis.account.api.PrivacyValue
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.account.data.AccountRepository
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

/** What the privacy centre and the guided check show. */
data class PrivacyUiState(
    val state: Loadable<PrivacyCentre> = Loadable.Idle,
    /** A change is on its way to the server; the controls wait meanwhile. */
    val isSaving: Boolean = false,
    /** Confirmation of the last change. */
    val message: String? = null,
    val error: String? = null,
    /** Setting key → the refusal shown at that setting. */
    val errors: Map<String, String> = emptyMap(),
    /** Guided check: 1…steps for the sections, steps + 1 for the summary. */
    val step: Int = 1
) {
    /** The sections the guided check walks through. */
    val checkupSections: List<PrivacySection> get() = state.valueOrNull?.sections?.filter { it.inCheckup }.orEmpty()

    /** The section of the current check step, or null on the summary. */
    val checkupSection: PrivacySection? get() = checkupSections.getOrNull(step - 1)
}

/**
 * Privatsphäre-Center and Privatsphäre-Check: every setting is saved the moment it changes. The screen shows the new
 * value at once ([show]), the controls wait while it is saved, and a refused value goes back with the web's message at
 * that setting. The settings come from the API as sections of [PrivacySettingItem]s and are drawn from their `kind`
 * and `choices` alone.
 */
@HiltViewModel
class PrivacyViewModel @Inject constructor(private val repository: AccountRepository) : ViewModel() {
    private val mutableUi = MutableStateFlow(PrivacyUiState())
    val ui: StateFlow<PrivacyUiState> = mutableUi.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { repository.privacy() }
            .onSuccess { centre ->
                mutableUi.update { it.copy(state = Loadable.Loaded(centre)) }
            }
            .onFailure { error ->
                mutableUi.update {
                    val failing = it.state.failing(error)
                    it.copy(state = failing, error = if (failing.valueOrNull != null) error.asApiError().userMessage else it.error)
                }
            }
    }

    /** Shows a new value at once; [save] sends it. */
    fun show(key: String, value: PrivacyValue) = replace(key) { it.copy(value = value) }

    /** Saves a value shown with [show]; a refused one goes back to [previous] with the reason at the setting. */
    suspend fun save(key: String, value: PrivacyValue, previous: PrivacyValue) {
        mutableUi.update { it.copy(errors = it.errors - key, isSaving = true) }
        val result = try {
            catching { repository.updatePrivacy(key, value) }
        } finally {
            mutableUi.update { it.copy(isSaving = false) }
        }
        result
            .onSuccess { saved ->
                replace(key) { saved.data }
                mutableUi.update { it.copy(message = saved.message) }
            }
            .onFailure { error ->
                replace(key) { it.copy(value = previous) }
                val failure = error.asApiError()
                mutableUi.update { it.copy(errors = it.errors + (key to (failure.message("value") ?: failure.userMessage))) }
            }
    }

    /** A control changed: show it and save it. */
    fun change(setting: PrivacySettingItem, value: PrivacyValue) {
        val previous = setting.value
        show(setting.key, value)
        viewModelScope.launch { save(setting.key, value, previous) }
    }

    /** „Tropfen begrenzen“. */
    suspend fun limitPastPosts() {
        catching { repository.limitPastPosts() }
            .onSuccess { message ->
                mutableUi.update { it.copy(message = message) }
                load()
            }
            .onFailure { error ->
                mutableUi.update { it.copy(error = error.asApiError().userMessage) }
            }
    }

    fun next() = mutableUi.update { it.copy(step = minOf(it.step + 1, it.checkupSections.size + 1)) }

    fun back() = mutableUi.update { it.copy(step = maxOf(it.step - 1, 1)) }

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

    fun dismissError() = mutableUi.update { it.copy(error = null) }

    private fun replace(key: String, change: (PrivacySettingItem) -> PrivacySettingItem) {
        mutableUi.update { state ->
            val centre = state.state.valueOrNull ?: return@update state
            val sections = centre.sections.map { section ->
                section.copy(settings = section.settings.map { if (it.key == key) change(it) else it })
            }
            state.copy(state = Loadable.Loaded(centre.copy(sections = sections)))
        }
    }
}

/** What Verborgene Wörter shows. */
data class HiddenWordsUiState(
    val state: Loadable<HiddenWords> = Loadable.Idle,
    val word: String = "",
    val message: String? = null,
    val error: String? = null,
    /** The refusal of the typed word. */
    val wordError: String? = null
)

/** Verborgene Wörter (/app/privatsphaere/woerter). */
@HiltViewModel
class HiddenWordsViewModel @Inject constructor(private val repository: AccountRepository) : ViewModel() {
    private val mutableUi = MutableStateFlow(HiddenWordsUiState())
    val ui: StateFlow<HiddenWordsUiState> = mutableUi.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun setWord(value: String) = mutableUi.update { it.copy(word = value) }

    suspend fun load() {
        mutableUi.update { it.copy(state = it.state.refreshing()) }
        catching { repository.hiddenWords() }
            .onSuccess { words ->
                mutableUi.update { it.copy(state = Loadable.Loaded(words)) }
            }
            .onFailure { error ->
                mutableUi.update {
                    val failing = it.state.failing(error)
                    it.copy(state = failing, error = if (failing.valueOrNull != null) error.asApiError().userMessage else it.error)
                }
            }
    }

    suspend fun add() {
        mutableUi.update { it.copy(wordError = null) }
        catching { repository.addHiddenWord(ui.value.word) }
            .onSuccess { change ->
                mutableUi.update { it.copy(state = Loadable.Loaded(change.data), message = change.message, word = "") }
            }
            .onFailure { error ->
                val failure = error.asApiError()
                val refusal = failure.message("word")
                mutableUi.update { if (refusal != null) it.copy(wordError = refusal) else it.copy(error = failure.userMessage) }
            }
    }

    suspend fun remove(word: String) {
        catching { repository.removeHiddenWord(word) }
            .onSuccess { change ->
                mutableUi.update { it.copy(state = Loadable.Loaded(change.data), message = change.message) }
            }
            .onFailure { error ->
                mutableUi.update { it.copy(error = error.asApiError().userMessage) }
            }
    }

    fun dismissMessage() = mutableUi.update { it.copy(message = null) }

    fun dismissError() = mutableUi.update { it.copy(error = null) }
}
