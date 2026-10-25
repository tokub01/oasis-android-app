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

    @Test
    fun registersANewTokenOnlyWhileEverythingAllowsIt() = runTest {
        val transport = transport()
        val system = FakePushSystem()
        val store = MemoryPushStore()
        val registrar = registrar(system, store)
        registrar.tokenChanged()
        assertEquals("not signed in yet", 0, system.tokenRequests)

        system.status = PushAuthorization.Denied
        registrar.signedIn(repository(transport))
        assertNull("no permission, no registration", store.deviceId)

        registrar.refreshAuthorization()
        registrar.tokenChanged()
        assertEquals(0, system.tokenRequests)
        system.status = PushAuthorization.Authorized
        registrar.refreshAuthorization()
        store.optedOut = true
        registrar.tokenChanged()
        assertEquals(0, system.tokenRequests)
        store.optedOut = false
        registrar.tokenChanged()
        assertEquals(5, store.deviceId)

        registrar.signedOut()
        registrar.tokenChanged()
        assertEquals("after sign-out nothing is sent", 1, transport.requests.count { it.path == "push/devices" })
    }

    @Test
    fun readsNoPushWhenTheConfigFailsAndRefusesWithoutToken() = runTest {
        val broken = StubTransport(StubTransport.Stub("GET", "app-config", status = 500, json = """{"message":"Server Error"}"""))
        val registrar = registrar()
        registrar.signedIn(repository(broken))
        assertFalse(registrar.isAvailable)

        val noToken = registrar(FakePushSystem(token = null))
        noToken.signedIn(repository(transport()))
        try {
            noToken.enableOnThisDevice()
            fail("expected a failure without token")
        } catch (failure: PushFailure) {
            assertEquals(PushRegistrar.FAILED_TEXT, failure.message)
        }

        val refusing = transport().apply { add("POST", "push/devices", status = 422, json = """{"message":"x"}""") }
        val refused = registrar()
        refused.signedIn(repository(refusing))
        assertNull(refused.deviceId)

        val offline = registrar()
        try {
            offline.enableOnThisDevice()
            fail("expected a failure without sign-in")
        } catch (failure: PushFailure) {
            assertEquals(PushRegistrar.FAILED_TEXT, failure.message)
        }
    }

    @Test
    fun cancellationOfTheConfigPassesThrough() = runTest {
        val cancelling = object : de.velwebsolutions.oasis.live.data.LiveRepository by repository(transport()) {
            override suspend fun appConfig() = throw kotlinx.coroutines.CancellationException("gone")
        }
        val failure = runCatching { registrar().signedIn(cancelling) }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun switchesOffWithoutRegistrationAndWhenTheServerForgotTheDevice() = runTest {
        val system = FakePushSystem()
        val notRegistered = registrar(system)
        assertEquals("Push-Hinweise sind auf diesem Gerät ausgeschaltet.", notRegistered.disableOnThisDevice())
        assertTrue(notRegistered.isOptedOut)

        val gone = transport().apply { add("DELETE", "push/devices/5", status = 404, json = """{"message":"Not Found"}""") }
        val store = MemoryPushStore(deviceId = 5)
        val withoutSignIn = registrar(store = MemoryPushStore(deviceId = 5))
        withoutSignIn.disableOnThisDevice()
        val registrar = registrar(store = store)
        registrar.signedIn(repository(gone))
        assertEquals("removed on the web already", "Push-Hinweise sind auf diesem Gerät ausgeschaltet.", registrar.disableOnThisDevice())
        assertNull(store.deviceId)
    }

    @Test
    fun staysOnWhenSwitchingOffFails() = runTest {
        val failing = transport().apply { add("DELETE", "push/devices/5", status = 500, json = """{"message":"Server Error"}""") }
        val store = MemoryPushStore()
        val registrar = registrar(store = store)
        registrar.signedIn(repository(failing))
        try {
            registrar.disableOnThisDevice()
            fail("expected a failure")
        } catch (failure: PushFailure) {
            assertEquals(PushRegistrar.FAILED_TEXT, failure.message)
        }
        assertFalse(registrar.isOptedOut)
        assertEquals(5, store.deviceId)
    }

    @Test
    fun forgetsOnlyThisInstallationsDeviceAndOpensTheSettings() {
        val system = FakePushSystem()
        val store = MemoryPushStore(deviceId = 5)
        val registrar = registrar(system, store)
        registrar.forgetDevice(9)
        assertEquals(5, store.deviceId)
        registrar.forgetDevice(5)
        assertNull(store.deviceId)
        registrar.forgetDevice(5)
        registrar.openSystemSettings()
        assertEquals(1, system.openedSettings)
    }
}
