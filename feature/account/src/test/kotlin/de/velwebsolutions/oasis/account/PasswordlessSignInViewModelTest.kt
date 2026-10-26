package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.api.Passkey
import de.velwebsolutions.oasis.account.platform.PasskeyAuthenticator
import de.velwebsolutions.oasis.account.platform.PasskeyFailure
import de.velwebsolutions.oasis.account.ui.auth.LoginCodeViewModel
import de.velwebsolutions.oasis.account.ui.auth.SignInViewModel
import de.velwebsolutions.oasis.account.ui.settings.PasskeysViewModel
import de.velwebsolutions.oasis.account.ui.settings.PasswordGate
import de.velwebsolutions.oasis.account.ui.settings.PasswordPrompt
import de.velwebsolutions.oasis.account.ui.settings.PhoneNumberViewModel
import de.velwebsolutions.oasis.core.auth.LoginCodeChannel
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.auth.SignInMethods
import de.velwebsolutions.oasis.core.di.DeviceName
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Sign-in by code and passkey: what the server offers, the neutral code request, the wrong code, the second factor. */
class PasswordlessSignInViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val wrongCode =
        """{"message":"x","errors":{"code":["Der Code stimmt nicht oder ist abgelaufen. Prüf ihn noch einmal oder lass dir einen neuen schicken."]}}"""

    @Test
    fun theSignInOffersWhatTheServerHas() = runTest {
        val transport = StubTransport().stub("GET", "auth/methods", "{}", status = 500)
        val model = SignInViewModel(session(transport, backgroundScope), RemoteAuthApi(transport.client))
        assertNull("without an answer: only the e-mail code", model.ui.value.methods)
        assertFalse(model.ui.value.offersSms || model.ui.value.offersPasskeys)
        transport.add("GET", "auth/methods", json = """{"data":{"login_code_channels":["email"],"sms_available":false,"passkeys":false}}""")
        model.loadMethods()
        assertEquals(SignInMethods(listOf("email")), model.ui.value.methods)
        assertFalse(model.ui.value.offersSms || model.ui.value.offersPasskeys)
        transport.add(
            "GET",
            "auth/methods",
            json = """{"data":{"login_code_channels":["email","sms"],"sms_available":true,"passkeys":true}}"""
        )
        model.loadMethods()
        assertTrue(model.ui.value.offersSms && model.ui.value.offersPasskeys)
    }

    @Test
    fun aRefusedPasswordOffersTheCodeAndAnotherErrorDoesNot() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/methods", "{}", status = 500)
            .stub("POST", "auth/login", """{"message":"x","errors":{"login":["Diese Zugangsdaten stimmen nicht."]}}""", status = 422)
        val model = SignInViewModel(session(transport, backgroundScope), RemoteAuthApi(transport.client))
        model.setLogin("mira")
        model.setPassword("falsch")
        model.signIn()
        assertTrue(model.ui.value.passwordRefused)
        transport.add("POST", "auth/login", 429, "{}")
        model.signIn()
        assertFalse(model.ui.value.passwordRefused)
        assertEquals(ApiError.TooManyRequests(null), model.ui.value.error)
    }

    @Test
    fun aPasskeySignsInWithoutASecondFactor() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/methods", "{}", status = 500)
            .stub("POST", "auth/passkey/options", PasswordlessFixtures.REQUEST)
            .stub("POST", "auth/passkey", """{"token":"pk-token","data":${Fixtures.ME}}""")
        val session = session(transport, backgroundScope)
        val model = SignInViewModel(session, RemoteAuthApi(transport.client))
        val passkeys = FakePasskeys()
        model.signInWithPasskey(passkeys)
        assertEquals(
            """{"challenge":"Y2g","rpId":"localhost","allowCredentials":[],"userVerification":"required"}""",
            passkeys.requests.single()
        )
        val body = transport.lastBody("auth/passkey")!!.jsonObject
        assertEquals("login-1", body["challenge"]!!.jsonPrimitive.content)
        assertEquals(PasswordlessFixtures.ASSERTION, body["credential"].toString())
        assertEquals("Pixel 9 von Mira", body["device_name"]!!.jsonPrimitive.content)
        assertEquals("pk-token", session.currentToken())
        assertTrue(session.state.value is SessionState.SignedIn)
        assertNull(model.ui.value.notice)
    }

    @Test
    fun aCancelledPasskeyStaysSilentAndOtherFailuresAreTold() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/methods", "{}", status = 500)
            .stub("POST", "auth/passkey/options", PasswordlessFixtures.REQUEST)
            .stub(
                "POST",
                "auth/passkey",
                """{"message":"x","errors":{"credential":["Dieser Passkey ist uns nicht bekannt."]}}""",
                status = 422
            )
        val model = SignInViewModel(session(transport, backgroundScope), RemoteAuthApi(transport.client))
        model.signInWithPasskey(FakePasskeys(failure = PasskeyFailure.Cancelled()))
        assertNull(model.ui.value.notice)
        model.signInWithPasskey(FakePasskeys(failure = PasskeyFailure.NoPasskey()))
        assertEquals(PasskeyFailure.NoPasskey().userMessage, model.ui.value.notice)
        model.signInWithPasskey(FakePasskeys())
        assertEquals("Dieser Passkey ist uns nicht bekannt.", model.ui.value.notice)
        model.signInWithPasskey(FakePasskeys(answer = "kaputt"))
        assertEquals(PasskeyFailure.Failed().userMessage, model.ui.value.notice)
        model.dismissNotice()
        assertNull(model.ui.value.notice)
        assertNull("the password form keeps its state", model.ui.value.error)
    }

    @Test
    fun aCancelledSignInStopsTheCeremony() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/methods", "{}", status = 500)
            .stub("POST", "auth/passkey/options", PasswordlessFixtures.REQUEST)
        val model = SignInViewModel(session(transport, backgroundScope), RemoteAuthApi(transport.client))
        val stopped = runCatching { model.signInWithPasskey(FakePasskeys(failure = kotlinx.coroutines.CancellationException("weg"))) }
        assertTrue(stopped.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(model.ui.value.notice)
    }

    /** Fails at once, without a request: the settings blocks report it the same way. */
    @Test
    fun theCodeIsSentNeutrallyAndSignsIn() = runTest {
        val transport = StubTransport()
            .stub("POST", "auth/login-code", "", status = 204)
            .stub("POST", "auth/login-code/verify", wrongCode, status = 422)
        val session = session(transport, backgroundScope)
        val model = LoginCodeViewModel(LoginCodeChannel.Email, "", session, RemoteAuthApi(transport.client))
        model.send()
        assertTrue("nothing is sent without a login", transport.requests.isEmpty())
        model.setLogin(" mira ")
        model.send()
        assertEquals("""{"login":"mira","channel":"email"}""", transport.lastBody("auth/login-code").toString())
        assertTrue(model.ui.value.sent)
        assertNull(model.signIn())
        assertTrue("nothing is sent without six digits", transport.requests.none { it.path == "auth/login-code/verify" })
        model.setCode("123 45")
        assertNull(model.signIn())
        model.setCode("123 456")
        assertNull(model.signIn())
        assertEquals(
            "Der Code stimmt nicht oder ist abgelaufen. Prüf ihn noch einmal oder lass dir einen neuen schicken.",
            model.ui.value.error?.message("code")
        )
        transport.add("POST", "auth/login-code/verify", json = """{"token":"code-token","data":${Fixtures.ME}}""")
        assertNull(model.signIn())
        assertEquals("code-token", session.currentToken())
        assertNull(model.ui.value.error)
    }

    @Test
    fun aRightCodeCanStillAskForTheSecondFactor() = runTest {
        val transport = StubTransport().stub("POST", "auth/login-code/verify", """{"two_factor":true,"challenge":"chal-9"}""")
        val session = session(transport, backgroundScope)
        val model = LoginCodeViewModel(LoginCodeChannel.Sms, "mira", session, RemoteAuthApi(transport.client))
        model.setCode("654321")
        assertEquals("chal-9", model.signIn())
        assertNull(session.currentToken())
    }

    @Test
    fun refusalsOfTheCodeRequestStayAndTheLoginCanBeChanged() = runTest {
        val transport = StubTransport()
            .stub("POST", "auth/login-code", """{"message":"x","errors":{"channel":["SMS ist gerade nicht verfügbar."]}}""", status = 422)
        val model = LoginCodeViewModel(LoginCodeChannel.Sms, "mira", session(transport, backgroundScope), RemoteAuthApi(transport.client))
        model.send()
        assertFalse(model.ui.value.sent)
        assertEquals("SMS ist gerade nicht verfügbar.", model.ui.value.error?.message("channel"))
        assertEquals("""{"login":"mira","channel":"sms"}""", transport.lastBody("auth/login-code").toString())
        transport.add("POST", "auth/login-code", 429, "{}")
        model.send()
        assertEquals(ApiError.TooManyRequests(null), model.ui.value.error)
        transport.add("POST", "auth/login-code", 204, "")
        model.send()
        model.setCode("1")
        model.changeLogin()
        val ui = model.ui.value
        assertFalse(ui.sent)
        assertEquals("", ui.code)
        assertNull(ui.error)
        assertEquals("mira", ui.login)
    }
}
