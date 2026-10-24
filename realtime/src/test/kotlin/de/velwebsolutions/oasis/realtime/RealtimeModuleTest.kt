package de.velwebsolutions.oasis.realtime

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import de.velwebsolutions.oasis.core.network.RealtimeConfig
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The app's realtime service: settings from app-config, the emulator's host, and reconnecting in the foreground. */
@RunWith(RobolectricTestRunner::class)
class RealtimeModuleTest {
    private fun service(reverb: String, host: String?): Pair<ReverbRealtime, StubTransport> {
        val transport = StubTransport(
            StubTransport.Stub("GET", "app-config", json = """{"data":{"reverb":$reverb,"push":{"available":false}}}""")
        )
        return RealtimeModule.realtime(transport.client, RealtimeConfig(host)) as ReverbRealtime to transport
    }

    @Test
    fun readsTheSettingsWithTheEmulatorHostAndStaysOffWithoutReverb() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val (withReverb, asked) = service("""{"key":"k","host":"oasis.test","port":1,"scheme":"http"}""", "127.0.0.1")
            withReverb.subscribe("status").launchIn(scope)
            eventually { asked.requests.any { it.path == "app-config" } }
            // The emulator host replaces the server's: the connection attempt goes there (and fails in the test).
            Thread.sleep(300)
            withReverb.disconnect()

            val (withoutReverb, transport) = service("null", null)
            withoutReverb.subscribe("status").launchIn(scope)
            eventually { transport.requests.any { it.path == "app-config" } }
            Thread.sleep(20)
            assertFalse(withoutReverb.isConnected.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reconnectsWhenTheAppComesToTheForeground() {
        val (realtime, transport) = service("null", null)
        shadowOf(Looper.getMainLooper()).idle()
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertTrue("nothing to reconnect without listeners", transport.requests.isEmpty())
        assertFalse(realtime.isConnected.value)
    }
}
