package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The chat's timers with a main dispatcher that queues every resumption: a timer that comes due together with a lock,
 * before the chat is closed, neither sweeps nor polls the locked chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationTimersTest {
    @get:Rule val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private val support = ChatTestSupport()
    private val server get() = support.server

    @Test
    fun timersDueWhenTheVaultLocksNeitherSweepNorPoll() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = mira.repository.createConversation("direct", null, listOf("2"), 7).id
        ConversationViewModelTest.open(mira, id)
        val reader = ConversationViewModel(id, jonas, FakeRealtime(connected = false)).apply {
            sweepInterval = 10.milliseconds
            pollInterval = 10.milliseconds
        }
        reader.open()
        assertNotNull(reader.state.value.conversation)
        val asked = CopyOnWriteArrayList<String>()
        server.onRequest = { asked += it }

        // Both timers come due in the moment the app goes to the background: they run after the lock, before the
        // closing of the chat that the lock asked for.
        launch {
            delay(10)
            jonas.vault.lockForBackground()
        }
        testScheduler.advanceTimeBy(10)
        testScheduler.runCurrent()
        settle(testScheduler, server) { true }
        server.onRequest = {}
        assertTrue("nothing is fetched for a locked chat: $asked", asked.isEmpty())
        assertNull(reader.state.value.conversation)
        assertTrue(reader.state.value.timeline.items.isEmpty())
        clear(reader)
    }
}
