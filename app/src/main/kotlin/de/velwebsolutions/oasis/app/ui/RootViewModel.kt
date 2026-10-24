package de.velwebsolutions.oasis.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.app.shell.LiveShell
import de.velwebsolutions.oasis.app.shell.PurchaseObserver
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.network.NetworkSignals
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The app shell's state: the session, the live counters and the network confirmation (OasisAppEnvironment on iOS). */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val session: Session,
    val live: LiveShell,
    private val purchases: PurchaseObserver
) : ViewModel() {
    /** Signed in, out or still restoring. */
    val state: StateFlow<SessionState> = session.state

    /** The confirmation of a network action whose screen has closed. */
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
            launch { purchases.observe() }
        }
    }

    /** The app came to the front. */
    fun foreground() {
        viewModelScope.launch { live.foreground() }
    }

    /** "Abmelden" in the "Mehr" menu. */
    fun signOut() {
        liveUserId = null
        liveJob?.cancel()
        viewModelScope.launch { session.signOut() }
    }

    /** The confirmation snackbar was shown. */
    fun clearConfirmation() {
        NetworkSignals.confirmation.value = null
    }
}
