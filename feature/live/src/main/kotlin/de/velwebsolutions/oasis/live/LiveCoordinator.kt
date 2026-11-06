package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.di.AppScope
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import de.velwebsolutions.oasis.live.api.QuietMode
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
        @AppScope scope: CoroutineScope,
        quiet: QuietModeState = QuietModeState()
    ) : this(repository, realtime, push, LiveCounts(scope, quiet = quiet))

    @Volatile private var userId: Int? = null

    /** Whether push is set up for [userId]; not while the setup waits for the server, nor after it was cancelled. */
    @Volatile private var pushSetUp = false

    /** Counts the stops, so a push setup that began before one does not count for the member after it. */
    @Volatile private var stops = 0

    /** Unread Hinweise (the bell and the dot at "Mehr"). */
    val hints: StateFlow<Int> = counts.hints

    /** Whether the shell should show Android's notification prompt now (once after sign-in, like iOS). */
    val asksForPushPermission: StateFlow<Boolean> get() = push.asksForPermission

    /** Before Android 13 the shell asks „Push-Hinweise einschalten?“ itself instead of the system dialog. */
    val pushAsksInApp: Boolean get() = push.asksInApp

    /** The member answered the notification prompt of the shell. */
    suspend fun pushPermissionAnswered(granted: Boolean) = push.promptAnswered(granted)

    /** The Ruhemodus for the calm banner on every screen. */
    val quietMode: StateFlow<QuietMode> = counts.quiet.mode

    /** „Beenden“ in the banner: off on every device; the answer is „Der Ruhemodus ist beendet.“ */
    suspend fun endQuietMode(): String? {
        val answer = repository.endQuietMode()
        counts.quiet.update(answer.data.quietMode)
        return answer.message
    }

    /** Conversations with unread messages (the badge at "Chats"). */
    val chats: StateFlow<Int> = counts.chats

    /**
     * Starts once per member (the tab view calls it whenever it appears). The counters run in the app's scope; push is
     * set up in the caller's coroutine, so a start cancelled before push was set up (the activity finished while
     * `app-config` was on its way) leaves push to the next start for the same member instead of skipping it until the app
     * is restarted.
     */
    suspend fun start(userId: Int) {
        if (this.userId != userId) {
            if (this.userId != null) stop()
            this.userId = userId
            counts.start(load = repository::counts, realtime = realtime, userId = userId)
        }
        if (pushSetUp) return
        val started = stops
        push.signedIn(repository)
        // Not after a sign-out (or another member) meanwhile: then the next start sets push up.
        if (stops == started) pushSetUp = true
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
        pushSetUp = false
        stops += 1
    }
}
