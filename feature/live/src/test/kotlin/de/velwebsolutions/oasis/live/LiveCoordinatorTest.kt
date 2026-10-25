package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.flow.first
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
}
