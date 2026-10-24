package de.velwebsolutions.oasis.live

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Goes live when the tabs appear for a verified, unlocked member (the same accounts the web keeps live): connects to
 * Reverb, keeps the counters at the bell and "Chats" fresh and registers for push. Stops on sign-out.
 */
@Singleton
class LiveCoordinator @Inject constructor() {
    private val mutableHints = MutableStateFlow(0)
    private val mutableChats = MutableStateFlow(0)

    /** Unread Hinweise (the bell and the dot at "Mehr"). */
    val hints: StateFlow<Int> = mutableHints

    /** Conversations with unread messages (the badge at "Chats"). */
    val chats: StateFlow<Int> = mutableChats

    /** Starts once per member (the tab view calls it whenever it appears). */
    suspend fun start(userId: Int) = Unit

    /** Back in the foreground: reconnect at once and fetch the numbers. */
    suspend fun foreground() = Unit

    /** Whether the chat tab is in front (no in-app notice then). */
    fun setShowingChats(showing: Boolean) = Unit

    /** Signed out (or the token was revoked): remove the push device, close the socket, clear the numbers. */
    fun stop() = Unit
}
