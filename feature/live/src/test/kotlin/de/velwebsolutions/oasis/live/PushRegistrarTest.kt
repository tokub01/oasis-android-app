package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.live.push.PushAuthorization
import de.velwebsolutions.oasis.live.push.PushFailure
import de.velwebsolutions.oasis.live.push.PushRegistrar
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Push on this device: prepared for FCM, but silent while the server takes APNs tokens only. */
class PushRegistrarTest {
    private fun transport() = StubTransport(
        StubTransport.Stub("GET", "app-config", json = LiveJson.CONFIG),
        StubTransport.Stub("POST", "push/devices", json = LiveJson.DEVICE),
        StubTransport.Stub("DELETE", "push/devices/5", json = """{"message":"Das Gerät bekommt keine Push-Hinweise mehr."}""")
    )

    @Test
    fun sendsNothingWhileTheServerDoesNotAcceptFcmOrFirebaseIsMissing() = runTest {
        val transport = transport()
        val system = FakePushSystem()
        val today = PushRegistrar(system, MemoryPushStore())
        assertFalse("Laravel takes APNs tokens only", PushRegistrar.BACKEND_ACCEPTS_FCM)
        today.signedIn(repository(transport))
        assertFalse(today.isSupported)
        assertTrue(today.isAvailable)
        assertTrue(transport.requests.none { it.path == "push/devices" })
        assertEquals(0, system.tokenRequests)

        val withoutFirebase = registrar(FakePushSystem(isConfigured = false))
        withoutFirebase.signedIn(repository(transport))
        assertFalse(withoutFirebase.isSupported)
        assertTrue(transport.requests.none { it.path == "push/devices" })
    }

    @Test
    fun registersTheTokenAfterSignInOnceSupported() = runTest {
        val transport = transport()
        val store = MemoryPushStore()
        val registrar = registrar(store = store)
        registrar.signedIn(repository(transport))
        assertEquals(5, registrar.deviceId)
        assertEquals(
            JsonObject(mapOf("token" to JsonPrimitive("fcm-token"), "platform" to JsonPrimitive("android"))),
            transport.lastBody("push/devices")
        )

        registrar.signedOut()
        assertNull(store.deviceId)
        assertTrue("revoking the sign-in's token removes the device", transport.requests.none { it.request.method == "DELETE" })
    }

    @Test
    fun staysQuietWhileTheServerHasNoPushOrTheMemberSwitchedItOff() = runTest {
        val off = StubTransport(StubTransport.Stub("GET", "app-config", json = """{"data":{"reverb":null,"push":{"available":false}}}"""))
        val system = FakePushSystem()
        registrar(system).signedIn(repository(off))
        assertEquals(0, system.tokenRequests)

        registrar(system, MemoryPushStore(optedOut = true)).signedIn(repository(transport()))
        assertEquals(0, system.tokenRequests)
    }

    @Test
    fun switchesOffAndOnAgainOnThisDevice() = runTest {
        val transport = transport()
        val system = FakePushSystem()
        val registrar = registrar(system)
        registrar.signedIn(repository(transport))
        assertEquals(5, registrar.deviceId)

        assertEquals("Push-Hinweise sind auf diesem Gerät ausgeschaltet.", registrar.disableOnThisDevice())
        assertTrue(registrar.isOptedOut)
        assertNull(registrar.deviceId)
        assertEquals(1, system.deletedTokens)
        assertTrue(transport.requests.any { it.request.method == "DELETE" && it.path == "push/devices/5" })

        assertEquals("Push-Hinweise sind auf diesem Gerät eingeschaltet.", registrar.enableOnThisDevice())
        assertFalse(registrar.isOptedOut)
        assertEquals(5, registrar.deviceId)
    }

    @Test
    fun explainsADeniedPermission() = runTest {
        val system = FakePushSystem(status = PushAuthorization.NotDetermined)
        val registrar = registrar(system)
        registrar.permissionAnswered(false)
        assertEquals(PushAuthorization.Denied, registrar.authorization.value)
        try {
            registrar.enableOnThisDevice()
            fail("expected a refusal")
        } catch (failure: PushFailure) {
            assertEquals(PushRegistrar.DENIED_TEXT, failure.message)
        }
    }
}
