package de.velwebsolutions.oasis.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.live.LiveCoordinator
import de.velwebsolutions.oasis.membership.PlayTransactions
import de.velwebsolutions.oasis.network.NetworkSignals
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The app shell's state: the session, the live counters and the network confirmation (OasisAppEnvironment on iOS). */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val session: Session,
    val live: LiveCoordinator,
    private val playTransactions: PlayTransactions,
) : ViewModel() {
    val state: StateFlow<SessionState> = session.state
    val confirmation = NetworkSignals.confirmation
    private var liveJob: Job? = null
    private var liveUserId: Int? = null

    init {
        session.addOnSignedOut { live.stop() }
        viewModelScope.launch { session.restore() }
    }

    /** The tabs appeared for this member: go live and listen for Play purchases (once per member). */
    fun tabsAppeared(userId: Int) {
        if (liveUserId == userId) return
        liveUserId = userId
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            launch { live.start(userId) }
            launch { playTransactions.observe() }
        }
    }

    fun foreground() {
        viewModelScope.launch { live.foreground() }
    }

    fun signOut() {
        liveUserId = null
        liveJob?.cancel()
        viewModelScope.launch { session.signOut() }
    }

    fun clearConfirmation() {
        NetworkSignals.confirmation.value = null
    }
}
