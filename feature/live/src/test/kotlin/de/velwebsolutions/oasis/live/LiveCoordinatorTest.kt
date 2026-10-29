package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.live.api.LiveAppConfig
import de.velwebsolutions.oasis.live.data.LiveRepository
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Going live once per member: counters, the inbox channel and push; stopping on sign-out. */
class LiveCoordinatorTest {
    private fun transport() = StubTransport(
        StubTransport.Stub("GET", "live/counts", json = LiveJson.COUNTS),
        StubTransport.Stub("GET", "app-config", json = """{"data":{"reverb":null,"push":{"available":false}}}""")
    )

    @Test
    fun startsOncePerMemberAndSwitchesMembers() = runTest {
        val transport = transport()
        val realtime = FakeRealtime()
        val live = LiveCoordinator(repository(transport), realtime, registrar(), backgroundScope)
        live.foreground()
        live.stop()
        assertTrue("nothing happens before the start", transport.requests.isEmpty())

        live.start(5)
        live.start(5)
        runCurrent()
        assertEquals(listOf("private-inbox.5"), realtime.channels)
        live.hints.first { it == 3 }
        assertEquals(2, live.chats.value)

        live.start(6)
        runCurrent()
        assertEquals(listOf("private-inbox.5", "private-inbox.6"), realtime.channels)

        val before = transport.requests.count { it.path == "live/counts" }
        live.foreground()
        live.refresh()
        assertTrue(transport.requests.count { it.path == "live/counts" } >= before + 2)

        live.setShowingChats(true)
        assertTrue(live.counts.isShowingChats)
        live.stop()
        assertEquals(0, live.hints.value)
    }

    /** The stubbed API, except that `app-config` answers with [config] for the call with this number (from 1). */
    private class ConfigRepository(transport: StubTransport, val config: suspend (call: Int) -> LiveAppConfig) :
        LiveRepository by repository(transport) {
        var configs = 0

        override suspend fun appConfig(): LiveAppConfig = config(++configs)
    }

    @Test
    fun setsPushUpAtTheNextStartWhenAStartEndedBeforeIt() = runTest {
        val repository = ConfigRepository(transport()) { call -> if (call == 1) awaitCancellation() else LiveAppConfig() }
        val realtime = FakeRealtime()
        val live = LiveCoordinator(repository, realtime, registrar(), backgroundScope)
        // The activity finished while app-config was on its way: its view model's scope ended the start.
        val starting = launch { live.start(5) }
        runCurrent()
        starting.cancelAndJoin()

        live.start(5)
        assertEquals("the next start sets push up", 2, repository.configs)
        live.start(5)
        assertEquals("once set up, push is not set up again", 2, repository.configs)
        runCurrent()
        assertEquals("the counters kept running", listOf("private-inbox.5"), realtime.channels)
        live.stop()
    }

    @Test
    fun aSignOutWhilePushIsSetUpLeavesItToTheNextSignIn() = runTest {
        val config = CompletableDeferred<LiveAppConfig>()
        val repository = ConfigRepository(transport()) { call -> if (call == 1) config.await() else LiveAppConfig() }
        val live = LiveCoordinator(repository, FakeRealtime(), registrar(), backgroundScope)
        val starting = launch { live.start(5) }
        runCurrent()
        live.stop()
        config.complete(LiveAppConfig())
        starting.join()

        live.start(5)
        assertEquals("signed in again, push is set up again", 2, repository.configs)
        live.stop()
    }
}
