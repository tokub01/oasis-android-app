package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.core.auth.SignInResult
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiClientTest {
    private val me = """{"id":7,"username":"mira","display_name":"Mira Sommer","initials":"MS","email":"mira@example.org",
        "email_verified":true,"is_staff":false,"trial_ends_at":"2026-11-02T10:00:00.000000Z"}"""

    @Test
    fun `signs in with snake case body and decodes me`() = runTest {
        val stub = StubTransport(StubTransport.Stub("POST", "auth/login", json = """{"token":"abc","data":$me}"""))
        val result = RemoteAuthApi(stub.client).login("mira", "geheim", "Pixel")

        val signedIn = result as SignInResult.SignedIn
        assertEquals("abc", signedIn.token)
        assertEquals("Mira Sommer", signedIn.me.displayName)
        assertEquals(1_793_613_600L, signedIn.me.trialEndsAt?.epochSecond)
        val body = stub.lastBody("auth/login")!!.jsonObject
        assertEquals("Pixel", body["device_name"]!!.jsonPrimitive.content)
        val request = stub.requests.single().request
        assertEquals("Bearer token", request.header("Authorization"))
        assertEquals("de", request.header("Accept-Language"))
        assertTrue(request.header("User-Agent")!!.startsWith("OasisApp/"))
    }

    @Test
    fun `two factor answer asks for the code`() = runTest {
        val stub = StubTransport(StubTransport.Stub("POST", "auth/login", json = """{"two_factor":true,"challenge":"ch"}"""))
        assertEquals(SignInResult.TwoFactorRequired("ch"), RemoteAuthApi(stub.client).login("mira", "x", "Pixel"))
    }

    @Test
    fun `maps validation errors per field`() = runTest {
        val stub = StubTransport(
            StubTransport.Stub("POST", "auth/login", 422, """{"message":"Falsch","errors":{"login":["Diese Angaben passen nicht."]}}"""),
        )
        try {
            RemoteAuthApi(stub.client).login("mira", "x", "Pixel")
            fail("expected an error")
        } catch (error: ApiError) {
            assertEquals("Diese Angaben passen nicht.", error.message("login"))
            assertEquals("Diese Angaben passen nicht.", error.userMessage)
        }
    }

    @Test
    fun `401 reports the session as signed out`() = runTest {
        val stub = StubTransport(StubTransport.Stub("GET", "auth/me", 401, """{"message":"Unauthenticated."}"""))
        try {
            RemoteAuthApi(stub.client).me()
            fail("expected an error")
        } catch (error: ApiError) {
            assertEquals(ApiError.Unauthenticated, error)
        }
        assertEquals(1, stub.unauthenticatedCount)
    }

    @Test
    fun `unexpected shapes become decoding errors`() = runTest {
        val stub = StubTransport(StubTransport.Stub("GET", "auth/me", json = """{"data":{"id":"x"}}"""))
        try {
            RemoteAuthApi(stub.client).me()
            fail("expected an error")
        } catch (error: ApiError) {
            assertTrue(error is ApiError.Decoding)
        }
    }

    @Test
    fun `resolves relative urls against the server`() {
        val client = StubTransport().client
        assertEquals("http://localhost/tropfen/1/bild/2", client.absoluteUrl("/tropfen/1/bild/2").toString())
        assertEquals("https://example.org/a.png", client.absoluteUrl("https://example.org/a.png").toString())
    }
}
