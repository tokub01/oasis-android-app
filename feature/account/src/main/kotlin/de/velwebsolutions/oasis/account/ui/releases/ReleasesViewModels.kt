package de.velwebsolutions.oasis.account.ui.releases

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.api.Release
import de.velwebsolutions.oasis.account.api.ReleaseList
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.core.di.AppVersion
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The app version whose notes this device has seen (shown once after an update), kept on this device. */
interface ReleaseNotesStore {
    fun lastSeen(): String?

    fun markSeen(version: String)
}

/** [ReleaseNotesStore] in the app's private preferences. */
class PreferencesReleaseNotesStore(context: Context) : ReleaseNotesStore {
    private val preferences = context.getSharedPreferences("oasis.releaseNotes", Context.MODE_PRIVATE)

    override fun lastSeen(): String? = preferences.getString(KEY, null)

    override fun markSeen(version: String) {
        preferences.edit { putString(KEY, version) }
    }

    private companion object {
        const val KEY = "last-seen"
    }
}

/** Was ist neu (/neuigkeiten): every version, newest first, and how Mildcrowd is built. */
@HiltViewModel
class ReleasesViewModel @Inject constructor(private val repository: AccountRepository, appVersion: AppVersion) : ViewModel() {
    private val mutableState = MutableStateFlow<Loadable<ReleaseList>>(Loadable.Idle)
    val state: StateFlow<Loadable<ReleaseList>> = mutableState.asStateFlow()

    /** The version of this app („0.23.0“). */
    val version: String = appVersion.name

    init {
        reload()
    }

    /**
     * „Noch einmal versuchen“: loads in the view model's scope, so the retry button leaving the screen while it loads
     * does not cancel the request.
     */
    fun reload() {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutableState.update { it.refreshing() }
        catching { repository.releases() }
            .onSuccess { releases -> mutableState.value = Loadable.Loaded(releases) }
            .onFailure { error -> mutableState.update { it.failing(error) } }
    }
}

/**
 * „Neu in Mildcrowd 0.23.0“ once after an update: when the version seen last on this device differs from the app's. A
 * fresh install only remembers its version. Without an answer (offline) or without notes for this version nothing
 * shows, and the next start asks again.
 */
@HiltViewModel
class UpdateNotesViewModel @Inject constructor(
    private val repository: AccountRepository,
    private val store: ReleaseNotesStore,
    private val appVersion: AppVersion
) : ViewModel() {
    private val mutableNotes = MutableStateFlow<Release?>(null)

    /** The notes of this version while the dialog shows. */
    val notes: StateFlow<Release?> = mutableNotes.asStateFlow()

    /** Asks once per start of the shell (the dialog's effect); a dismissed version is not asked for again. */
    suspend fun check() {
        val seen = store.lastSeen()
        if (seen == appVersion.name) return
        if (seen == null && appVersion.freshInstall) {
            store.markSeen(appVersion.name)
            return
        }
        catching { repository.releases() }.onSuccess { releases ->
            mutableNotes.value = releases.data.firstOrNull { it.version == appVersion.name }
        }
    }

    /** „Verstanden“: this version's notes do not show again on this device. */
    fun dismiss() {
        store.markSeen(appVersion.name)
        mutableNotes.value = null
    }
}
