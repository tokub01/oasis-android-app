package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.auth.LoginCodeChannel
import de.velwebsolutions.oasis.core.auth.PasskeyCeremony
import de.velwebsolutions.oasis.core.auth.Registration
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.core.auth.SignInMethods
import de.velwebsolutions.oasis.core.auth.SignInResult
import de.velwebsolutions.oasis.core.model.PushPreview
import de.velwebsolutions.oasis.core.model.ToneHint
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.OasisJson
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
                json = """{"data":{"honeypot_field":"website","token_field":"form_token","form_token":"ft"}}"""
            ),
            StubTransport.Stub("POST", "auth/register", json = """{"token":"t","data":$me}"""),
            StubTransport.Stub("POST", "auth/forgot-password", json = """{"message":"Wenn es das Konto gibt, kommt eine Mail."}"""),
            StubTransport.Stub("POST", "auth/reset-password", json = """{"message":"Dein Passwort wurde zurückgesetzt."}"""),
            StubTransport.Stub("POST", "auth/logout", status = 204, json = ""),
            StubTransport.Stub("POST", "auth/email/verification-notification", status = 202, json = ""),
            StubTransport.Stub("POST", "auth/login", json = """{"token":null}""")
        )
        val auth = RemoteAuthApi(stub.client)
        assertEquals("t", (auth.twoFactor("ch", null, "rec-1") as SignInResult.SignedIn).token)
        val form = auth.registrationForm()
        assertEquals(RegistrationForm("website", "form_token", "ft"), form)
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
        assertEquals("Dein Passwort wurde zurückgesetzt.", auth.resetPassword("tok", "m@example.org", "neu-und-lang", "neu-und-lang"))
        assertEquals(
            """{"token":"tok","email":"m@example.org","password":"neu-und-lang","password_confirmation":"neu-und-lang"}""",
            stub.lastBody("auth/reset-password").toString()
        )
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
    fun `sign-in without a password`() = runTest {
        val stub = StubTransport(
            StubTransport.Stub(
                "GET",
                "auth/methods",
                json = """{"data":{"login_code_channels":["email","sms"],"sms_available":true,"passkeys":true}}"""
            ),
            StubTransport.Stub("POST", "auth/login-code", status = 204, json = ""),
            StubTransport.Stub("POST", "auth/login-code/verify", json = """{"two_factor":true,"challenge":"ch"}"""),
            StubTransport.Stub(
                "POST",
                "auth/passkey/options",
                json = """{"challenge":"pk","options":{"challenge":"abc","rpId":"localhost","allowCredentials":[]}}"""
            ),
            StubTransport.Stub("POST", "auth/passkey", json = """{"token":"t","data":$me}""")
        )
        val auth = RemoteAuthApi(stub.client)
        assertEquals(SignInMethods(listOf("email", "sms"), smsAvailable = true, passkeys = true), auth.methods())
        auth.requestLoginCode("mira", LoginCodeChannel.Sms)
        assertEquals("""{"login":"mira","channel":"sms"}""", stub.lastBody("auth/login-code").toString())
        assertEquals(SignInResult.TwoFactorRequired("ch"), auth.verifyLoginCode("mira", "123 456", "Pixel"))
        assertEquals("""{"login":"mira","code":"123 456","device_name":"Pixel"}""", stub.lastBody("auth/login-code/verify").toString())
        val ceremony = auth.passkeyOptions()
        assertEquals(PasskeyCeremony("pk", ceremony.options), ceremony)
        assertEquals("""{"challenge":"abc","rpId":"localhost","allowCredentials":[]}""", ceremony.options.toString())
        val credential = OasisJson.verbatim.parseToJsonElement("""{"id":"x","rawId":"x","response":{"clientDataJSON":"c"}}""").jsonObject
        assertEquals("t", (auth.passkeySignIn("pk", credential, "Pixel") as SignInResult.SignedIn).token)
        assertEquals(
            """{"challenge":"pk","credential":{"id":"x","rawId":"x","response":{"clientDataJSON":"c"}},"device_name":"Pixel"}""",
            stub.lastBody("auth/passkey").toString()
        )
        assertEquals(LoginCodeChannel.Email, LoginCodeChannel.entries.first { it.wire == "email" })
        assertEquals(SignInMethods(listOf("email")), OasisJson.snake.decodeFromString<SignInMethods>("{}"))
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

    @Test
    fun `odd answers and addresses`() = runTest {
        assertEquals(ApiError.Validation("", emptyMap()), ApiClient.errorFor(422, null, null))
        assertEquals(ApiError.Validation("x", emptyMap()), ApiClient.errorFor(422, """{"message":"x"}""", null))
        assertEquals(ApiError.TooManyRequests(null), ApiClient.errorFor(429, null, null))
        assertEquals(ApiError.Server(500, "Wartung"), ApiClient.errorFor(500, """{"message":"Wartung"}""", null))
        val client = StubTransport().client
        assertEquals("http://localhost/api/v1/", client.url("ftp:x").toString())
        assertNull(client.absoluteUrl("http://"))
        try {
            client.download("ftp:x")
            fail("expected an error")
        } catch (error: ApiError) {
            assertEquals(ApiError.Transport("Ungültige Adresse"), error)
        }
        val auth = RemoteAuthApi(
            StubTransport(
                StubTransport.Stub("POST", "auth/login", json = """{"two_factor":true}"""),
                StubTransport.Stub("POST", "auth/two-factor", json = """{"token":"t"}""")
            ).client
        )
        for (call in listOf<suspend () -> Unit>({ auth.login("m", "p", "d") }, { auth.twoFactor("c", "1", null) })) {
            try {
                call()
                fail("expected an error")
            } catch (error: ApiError) {
                assertEquals(ApiError.Decoding("Anmeldung ohne Token"), error)
            }
        }
        assertEquals("ok", de.velwebsolutions.oasis.core.network.MessageResponse("ok").message)
        assertNull(de.velwebsolutions.oasis.core.auth.TokenAnswer().token)
        assertNull(de.velwebsolutions.oasis.core.realtime.RealtimeEvent("x").string("kind"))
    }

    @Test
    fun `connection failures without a message still read as no connection`() = runTest {
        val failing = okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException() }.build()
        val tokens = object : de.velwebsolutions.oasis.core.network.TokenProvider {
            override fun currentToken(): String? = null

            override fun onUnauthenticated() = Unit
        }
        val client = ApiClient(de.velwebsolutions.oasis.core.network.ApiConfig("http://localhost", "UA", gateKey = ""), tokens, failing)
        try {
            client.download("/bild.png")
            fail("expected an error")
        } catch (error: ApiError) {
            assertEquals(ApiError.Transport("Keine Verbindung"), error)
        }
    }

    interface NotACall {
        @retrofit2.http.GET("x")
        fun plain(): String
    }

    @Test(expected = IllegalArgumentException::class)
    fun `services return calls or suspend`() {
        StubTransport().client.create<NotACall>().plain()
    }
}
