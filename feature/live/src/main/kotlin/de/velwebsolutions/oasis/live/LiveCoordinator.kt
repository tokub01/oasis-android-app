package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.di.AppScope
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import de.velwebsolutions.oasis.live.data.LiveRepository
import de.velwebsolutions.oasis.live.push.PushRegistrar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Goes live when the tabs appear for a verified, unlocked member (the same accounts the web keeps live): connects to
 * Reverb, keeps the counters at the bell and "Chats" fresh and registers for push. Stops on sign-out.
 * (OasisAppEnvironment.LiveCoordinator on iOS.) The socket opens with the inbox subscription and closes with it; the
 * realtime service reconnects on its own when the app returns to the foreground.
 */
@Singleton
class LiveCoordinator internal constructor(
    private val repository: LiveRepository,
    private val realtime: RealtimeService,
    private val push: PushRegistrar,
    val counts: LiveCounts
) {
    @Inject constructor(
        repository: LiveRepository,
        realtime: RealtimeService,
        push: PushRegistrar,
        @AppScope scope: CoroutineScope
    ) : this(repository, realtime, push, LiveCounts(scope))

    @Volatile private var userId: Int? = null

    /** Unread Hinweise (the bell and the dot at "Mehr"). */
    val hints: StateFlow<Int> = counts.hints

    /** Conversations with unread messages (the badge at "Chats"). */
    val chats: StateFlow<Int> = counts.chats

    /** Starts once per member (the tab view calls it whenever it appears). */
    suspend fun start(userId: Int) {
        if (this.userId == userId) return
        if (this.userId != null) stop()
        this.userId = userId
        counts.start(load = repository::counts, realtime = realtime, userId = userId)
        push.signedIn(repository)
    }

    /** Back in the foreground: fetch the numbers (the realtime service reconnects by itself). */
    suspend fun foreground() {
        if (userId == null) return
        counts.refresh()
    }

    /** Ask the server for the numbers again (after reading hints, when a push arrives). */
    suspend fun refresh() = counts.refresh()

    /** Whether the chat tab is in front (no in-app notice then). */
    fun setShowingChats(showing: Boolean) {
        counts.isShowingChats = showing
    }

    /** Signed out (or the token was revoked): remove the push device, close the socket, clear the numbers. */
    fun stop() {
        if (userId == null) return
        push.signedOut()
        counts.stop()
        userId = null
    }
}
