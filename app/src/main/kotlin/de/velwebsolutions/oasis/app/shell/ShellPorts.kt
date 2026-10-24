package de.velwebsolutions.oasis.app.shell

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.velwebsolutions.oasis.live.LiveCoordinator
import de.velwebsolutions.oasis.membership.PlayTransactions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** What the tab shell needs from the live module: the counters, and going live for a member. */
interface LiveShell {
    /** Unread Hinweise (the bell and the dot at "Mehr"). */
    val hints: StateFlow<Int>

    /** Conversations with unread messages (the badge at "Chats"). */
    val chats: StateFlow<Int>

    /** Connects and counts for this member. */
    suspend fun start(userId: Int)

    /** The app is back in front: reconnect and refresh. */
    suspend fun foreground()

    /** Whether the chats are in front (no in-app notice then). */
    fun setShowingChats(showing: Boolean)

    /** Signed out: stop everything. */
    fun stop()
}

/** Listens for store purchases that arrive outside the checkout while a member is signed in. */
fun interface PurchaseObserver {
    suspend fun observe()
}

/** [LiveShell] over the live module's coordinator. */
class CoordinatorLiveShell @Inject constructor(private val coordinator: LiveCoordinator) : LiveShell {
    override val hints: StateFlow<Int> get() = coordinator.hints
    override val chats: StateFlow<Int> get() = coordinator.chats

    override suspend fun start(userId: Int) = coordinator.start(userId)

    override suspend fun foreground() = coordinator.foreground()

    override fun setShowingChats(showing: Boolean) = coordinator.setShowingChats(showing)

    override fun stop() = coordinator.stop()
}

/** [PurchaseObserver] over Google Play's transactions. */
class PlayPurchaseObserver @Inject constructor(private val transactions: PlayTransactions) : PurchaseObserver {
    override suspend fun observe() = transactions.observe()
}

/** Binds the shell's ports to the feature modules. */
@Module
@InstallIn(SingletonComponent::class)
interface ShellModule {
    @Binds
    @Singleton
    fun liveShell(shell: CoordinatorLiveShell): LiveShell

    @Binds
    fun purchaseObserver(observer: PlayPurchaseObserver): PurchaseObserver
}
