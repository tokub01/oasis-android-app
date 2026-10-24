package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.auth.Registration
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.core.auth.SignInResult
import de.velwebsolutions.oasis.core.model.PushPreview
import de.velwebsolutions.oasis.core.model.ToneHint
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.RemoteCommonApi
import de.velwebsolutions.oasis.testing.StubTransport
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CommonApiTest {
    private val me = """{"id":7,"username":"mira","display_name":"Mira","initials":"M","email":"m@example.org"}"""

    @Test
    fun `tone check and push previews`() = runTest {
        val stub = StubTransport(
            StubTransport.Stub("POST", "network/tone", json = """{"data":{"hint":"Ganz schön scharf.","nudge":true}}"""),
            StubTransport.Stub("GET", "push/previews/abc", json = """{"data":{"title":"Jonas","body":"Hat geantwortet."}}""")
        )
        val api = RemoteCommonApi(stub.client)
        assertEquals(ToneHint("Ganz schön scharf.", true), api.toneHint("Du Idiot", "post"))
        assertEquals("post", stub.lastBody("network/tone")!!.jsonObject["field"]!!.jsonPrimitive.content)
        assertEquals(PushPreview("Jonas", "Hat geantwortet."), api.pushPreview("abc"))
        assertNull(api.pushPreview("gone"))
    }

    @Test
    fun `every auth call`() = runTest {
        val stub = StubTransport(
            StubTransport.Stub("POST", "auth/two-factor", json = """{"token":"t","data":$me}"""),
            StubTransport.Stub(
                "GET",
                "auth/register",
                json = """{"data":{"honeypot_field":"website","token_field":"form_token","form_token":"ft","trial_days":30}}"""
            ),
            StubTransport.Stub("POST", "auth/register", json = """{"token":"t","data":$me}"""),
            StubTransport.Stub("POST", "auth/forgot-password", json = """{"message":"Wenn es das Konto gibt, kommt eine Mail."}"""),
            StubTransport.Stub("POST", "auth/logout", status = 204, json = ""),
            StubTransport.Stub("POST", "auth/email/verification-notification", status = 202, json = ""),
            StubTransport.Stub("POST", "auth/login", json = """{"token":null}""")
        )
        val auth = RemoteAuthApi(stub.client)
        assertEquals("t", (auth.twoFactor("ch", null, "rec-1") as SignInResult.SignedIn).token)
        val form = auth.registrationForm()
        assertEquals(RegistrationForm("website", "form_token", "ft", 30), form)
        auth.register(
            Registration("Mira", "mira", "m@example.org", LocalDate.of(2000, 1, 2), "pw", "pw", acceptsTerms = true),
            form,
            "Pixel"
        )
        val body = stub.lastBody("auth/register")!!.jsonObject
        assertEquals("2000-01-02", body["birth_date"]!!.jsonPrimitive.content)
        assertEquals("1", body["terms"]!!.jsonPrimitive.content)
        assertEquals("", body["website"]!!.jsonPrimitive.content)
        assertEquals("ft", body["form_token"]!!.jsonPrimitive.content)
        auth.register(Registration(), form, "Pixel")
        assertEquals("0", stub.lastBody("auth/register")!!.jsonObject["terms"]!!.jsonPrimitive.content)
        assertEquals("Wenn es das Konto gibt, kommt eine Mail.", auth.forgotPassword("m@example.org"))
        auth.logout()
        auth.resendVerification()
        try {
            auth.login("mira", "x", "Pixel")
            fail("expected an error")
        } catch (error: ApiError) {
            assertEquals(ApiError.Decoding("Anmeldung ohne Token"), error)
        }
    }

    @Test
    fun `maps every status and failure`() {
        assertEquals(ApiError.Forbidden("Nein"), ApiClient.errorFor(403, """{"message":"Nein"}""", null))
        assertEquals(ApiError.NotFound, ApiClient.errorFor(404, null, null))
        assertEquals(ApiError.Conflict(""), ApiClient.errorFor(409, "kein json", null))
        assertEquals(ApiError.PayloadTooLarge, ApiClient.errorFor(413, null, null))
        assertEquals(ApiError.TooManyRequests(30), ApiClient.errorFor(429, null, "30"))
        assertEquals(ApiError.TooManyRequests(null), ApiClient.errorFor(429, null, "bald"))
        assertEquals(ApiError.Server(500, null), ApiClient.errorFor(500, "{}", null))
        assertEquals(ApiError.Validation("x", emptyMap()), ApiClient.errorFor(422, """{"message":"x","errors":[]}""", null))
        assertEquals(
            ApiError.Validation("x", mapOf("a" to emptyList())),
            ApiClient.errorFor(422, """{"message":"x","errors":{"a":"y"}}""", null)
        )
        assertEquals(ApiError.NotFound, ApiClient.map(ApiError.NotFound))
        assertEquals(ApiError.Decoding("kaputt"), ApiClient.map(SerializationException("kaputt")))
        assertEquals(ApiError.Decoding("Unerwartete Antwort"), ApiClient.map(SerializationException()))
        assertEquals(ApiError.Decoding("falsch"), ApiClient.map(IllegalArgumentException("falsch")))
        assertEquals(ApiError.Decoding("Unerwartete Antwort"), ApiClient.map(IllegalArgumentException()))
        assertEquals(ApiError.Transport("weg"), ApiClient.map(java.io.IOException("weg")))
        assertEquals(ApiError.Transport("java.io.IOException"), ApiClient.map(java.io.IOException()))
    }

    @Test
    fun `downloads with the token and reports errors`() = runTest {
        val stub = StubTransport(StubTransport.Stub("GET", "x", json = "PDF"))
        stub.add(StubTransport.Stub("GET", "/rechnung/1", json = "%PDF"))
        stub.add(StubTransport.Stub("GET", "/rechnung/2", status = 404, json = "{}"))
        val client = stub.client
        assertArrayEquals("%PDF".toByteArray(), client.download("/rechnung/1"))
        assertEquals("*/*", stub.requests.last().request.header("Accept"))
        try {
            client.download("/rechnung/2")
            fail("expected an error")
        } catch (error: ApiError) {
            assertEquals(ApiError.NotFound, error)
        }
        try {
            client.download("/rechnung/3")
            fail("expected an error")
        } catch (error: ApiError) {
            assertEquals(true, error is ApiError.Transport)
        }
        assertEquals("http://localhost/api/v1/network/feed", client.url("/network/feed").toString())
    }
}
