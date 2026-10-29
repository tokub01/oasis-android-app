package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.ui.auth.ForgotPasswordViewModel
import de.velwebsolutions.oasis.account.ui.auth.RegisterViewModel
import de.velwebsolutions.oasis.account.ui.auth.SignInViewModel
import de.velwebsolutions.oasis.account.ui.auth.TwoFactorRecoveryViewModel
import de.velwebsolutions.oasis.account.ui.auth.TwoFactorViewModel
import de.velwebsolutions.oasis.account.ui.auth.VerifyEmailViewModel
import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Signing in (with the second factor), registering and the e-mail confirmation (iOS AccountUITests, on the JVM). */
class AuthViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val form = """{"data":{"honeypot_field":"website_url","token_field":"form_started","form_token":"abc","trial_days":30}}"""

    @Test
    fun signsInWithTheSecondFactor() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/register", form)
            .stub("POST", "auth/login", """{"two_factor":true,"challenge":"chal-123"}""")
            .stub("POST", "auth/two-factor", """{"token":"new-token","data":${Fixtures.ME}}""")
        val session = session(transport, backgroundScope)
        val signIn = SignInViewModel(session, RemoteAuthApi(transport.client))
        signIn.setLogin("mira")
        signIn.setPassword("password")
        assertEquals("chal-123", signIn.signIn())
        assertEquals(
            JsonObject(
                mapOf(
                    "login" to JsonPrimitive("mira"),
                    "password" to JsonPrimitive("password"),
                    "device_name" to JsonPrimitive("Pixel 9 von Mira")
                )
            ),
            transport.lastBody("auth/login")
        )
        assertTrue(session.state.value !is SessionState.SignedIn)

        val twoFactor = TwoFactorViewModel(session)
        twoFactor.setCode(" 123456 ")
        twoFactor.submit("chal-123")
        assertEquals(
            JsonObject(mapOf("challenge" to JsonPrimitive("chal-123"), "code" to JsonPrimitive("123456"))),
            transport.lastBody("auth/two-factor")
        )
        assertEquals("mira", (session.state.value as SessionState.SignedIn).me.username)
        assertEquals("new-token", session.currentToken())
    }

    @Test
    fun usesARecoveryCodeWhenNoCodeIsTypedAndShowsTheRefusal() = runTest {
        val transport = StubTransport()
            .stub("POST", "auth/two-factor", """{"message":"x","errors":{"recovery_code":["Dieser Code stimmt nicht."]}}""", status = 422)
        val model = TwoFactorViewModel(session(transport, backgroundScope))
        model.setRecoveryCode("aaaa-bbbb")
        model.submit("chal")
        assertEquals(JsonPrimitive("aaaa-bbbb"), transport.lastBody("auth/two-factor")?.jsonObject?.get("recovery_code"))
        assertNull(transport.lastBody("auth/two-factor")?.jsonObject?.get("code"))
        assertEquals("Dieser Code stimmt nicht.", model.ui.value.error?.message("recovery_code"))
    }

    @Test
    fun showsTheSignInRefusalAtTheField() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/register", form)
            .stub("POST", "auth/login", """{"message":"x","errors":{"login":["Diese Zugangsdaten stimmen nicht."]}}""", status = 422)
        val model = SignInViewModel(session(transport, backgroundScope), RemoteAuthApi(transport.client))
        model.setLogin("mira")
        model.setPassword("falsch")
        assertNull(model.signIn())
        assertEquals("Diese Zugangsdaten stimmen nicht.", model.ui.value.error?.message("login"))
        assertEquals("falsch", model.ui.value.password)
    }

    @Test
    fun registersWithTheBotTrapAndTheBirthDate() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/register", form)
            .stub(
                "POST",
                "auth/register",
                """{"token":"t","data":${Fixtures.ME.replace("\"email_verified\":true", "\"email_verified\":false")}}"""
            )
        val session = session(transport, backgroundScope)
        val model = RegisterViewModel(session, RemoteAuthApi(transport.client))
        model.ui.first { it.form != null }
        model.edit {
            it.copy(
                displayName = "Lena Berger",
                username = "lena",
                email = "lena@beispiel.tld",
                password = "ein-langes-passwort",
                passwordConfirmation = "ein-langes-passwort",
                acceptsTerms = true
            )
        }
        model.submit()
        assertTrue("the birth date is required", model.ui.value.birthDateMissing)
        assertTrue(transport.requests.none { it.request.method == "POST" })

        model.setBirthDate(LocalDate.of(2004, 5, 17))
        assertFalse(model.ui.value.birthDateMissing)
        model.submit()
        val body = transport.lastBody("auth/register")!!.jsonObject
        assertEquals(JsonPrimitive("2004-05-17"), body["birth_date"])
        assertEquals(JsonPrimitive("1"), body["terms"])
        assertEquals(JsonPrimitive(""), body["website_url"])
        assertEquals(JsonPrimitive("abc"), body["form_started"])
        assertFalse((session.state.value as SessionState.SignedIn).me.emailVerified)
    }

    @Test
    fun keepsTheFieldErrorsAndTheFormTokenUntilTheServerRefusesTheToken() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/register", form)
            .stub(
                "POST",
                "auth/register",
                """{"message":"x","errors":{"username":["Dieser Benutzername ist schon vergeben."]}}""",
                status = 422
            )
        val model = RegisterViewModel(session(transport, backgroundScope), RemoteAuthApi(transport.client))
        model.ui.first { it.form != null }
        model.setBirthDate(LocalDate.of(2000, 1, 1))
        model.submit()
        assertEquals("Dieser Benutzername ist schon vergeben.", model.ui.value.error?.message("username"))
        // A token is good for 24 hours; a fresh one would refuse a second try within three seconds (BotProtection).
        assertEquals(1, transport.requests.count { it.request.method == "GET" && it.path == "auth/register" })

        transport.add("POST", "auth/register", status = 422, json = """{"message":"x","errors":{"form":["Das hat nicht geklappt."]}}""")
        transport.add("GET", "auth/register", json = form.replace("abc", "def"))
        model.submit()
        assertEquals("Das hat nicht geklappt.", model.ui.value.error?.message("form"))
        assertEquals("a refused token is replaced", "def", model.ui.value.form?.formToken)

        // Refused again, and no fresh token comes (offline now): the one there is stays for the next try.
        transport.add("GET", "auth/register", status = 503, json = """{"message":"Gleich wieder da."}""")
        model.submit()
        assertEquals("Das hat nicht geklappt.", model.ui.value.error?.message("form"))
        assertEquals("def", model.ui.value.form?.formToken)
    }

    @Test
    fun aRegistrationFormThatDidNotLoadIsFetchedWhenItIsSent() = runTest {
        val transport = StubTransport().stub("GET", "auth/register", """{"message":"Server Error"}""", status = 500)
        val session = session(transport, backgroundScope)
        val model = RegisterViewModel(session, RemoteAuthApi(transport.client))
        model.ui.first { it.error != null }
        transport.add("GET", "auth/register", json = form)
        transport.add("POST", "auth/register", json = """{"token":"t","data":${Fixtures.ME}}""")
        model.setBirthDate(LocalDate.of(2000, 1, 1))
        val before = testScheduler.currentTime
        model.submit()
        assertTrue("the fresh token waits until the server takes it", testScheduler.currentTime - before >= 3_000)
        assertEquals(JsonPrimitive("abc"), transport.lastBody("auth/register")!!.jsonObject["form_started"])
        assertTrue(session.state.value is SessionState.SignedIn)
    }

    /** The lost second factor with the e-mail and password typed in. */
    private fun recovery(transport: StubTransport, auth: AuthApi = RemoteAuthApi(transport.client)) =
        TwoFactorRecoveryViewModel(auth, repository(transport)).apply {
            setEmail("mira@demo.oasis.tld")
            setPassword("geheim")
        }

    private fun StubTransport.sentToken() = lastBody("auth/two-factor-recovery")!!.jsonObject["form_started"]

    private fun StubTransport.tokenFetches() = requests.count { it.request.method == "GET" && it.path == "auth/register" }

    @Test
    fun theLostSecondFactorSendsTheTokenFetchedWhenTheScreenOpened() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/register", form)
            .stub("POST", "auth/two-factor-recovery", """{"message":"Wir haben dir einen Link geschickt."}""")
        val model = recovery(transport)
        model.ui.first { it.form != null }
        model.submit()
        assertEquals("Wir haben dir einen Link geschickt.", model.ui.value.status)
        assertEquals("the server's time trap needs a token older than three seconds", JsonPrimitive("abc"), transport.sentToken())
        assertEquals("no fresh token right before sending", 1, transport.tokenFetches())
        assertEquals("", model.ui.value.password)
    }

    @Test
    fun aRefusedTokenIsReplacedButAnotherRefusalKeepsIt() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/register", form)
            .stub(
                "POST",
                "auth/two-factor-recovery",
                """{"message":"x","errors":{"form":["Das hat nicht geklappt. Bitte schick das Formular noch einmal ab."]}}""",
                status = 422
            )
        val model = recovery(transport)
        model.ui.first { it.form != null }
        transport.add("GET", "auth/register", json = form.replace("abc", "def"))
        model.submit()
        assertEquals("Das hat nicht geklappt. Bitte schick das Formular noch einmal ab.", model.ui.value.error?.message("form"))
        assertEquals("def", model.ui.value.form?.formToken)
        transport.add("POST", "auth/two-factor-recovery", status = 500, json = """{"message":"Server Error"}""")
        model.submit()
        assertEquals(JsonPrimitive("def"), transport.sentToken())
        assertEquals("a token is good for 24 hours", 2, transport.tokenFetches())
    }

    @Test
    fun withoutATokenFromTheStartTheLostSecondFactorFetchesOneAndLetsItAge() = runTest {
        val transport = StubTransport()
            .stub("POST", "auth/two-factor-recovery", """{"message":"Wir haben dir einen Link geschickt."}""")
        var fetches = 0
        val offlineAtFirst = object : AuthApi by RemoteAuthApi(transport.client) {
            override suspend fun registrationForm(): RegistrationForm {
                fetches++
                if (fetches == 1) throw ApiError.Transport("offline")
                return RegistrationForm("website_url", "form_started", "abc")
            }
        }
        val model = recovery(transport, offlineAtFirst)
        assertEquals("fetched when the screen opened, in vain", 1, fetches)
        val before = testScheduler.currentTime
        model.submit()
        assertEquals(2, fetches)
        assertTrue("the fresh token waits until the server takes it", testScheduler.currentTime - before >= 3_000)
        assertEquals(JsonPrimitive("abc"), transport.sentToken())
        assertEquals("Wir haben dir einen Link geschickt.", model.ui.value.status)
        model.setPassword("geheim")
        model.submit()
        assertEquals("the token fetched for the first try is kept for the next", 2, fetches)
    }

    @Test
    fun answersTheForgottenPasswordNeutrally() = runTest {
        val transport = StubTransport().stub(
            "POST",
            "auth/forgot-password",
            """{"message":"Wenn es ein Konto gibt, bekommst du eine E-Mail."}"""
        )
        val model = ForgotPasswordViewModel(RemoteAuthApi(transport.client))
        model.setEmail("mira@demo.oasis.tld")
        model.send()
        assertEquals("Wenn es ein Konto gibt, bekommst du eine E-Mail.", model.ui.value.status)
    }

    @Test
    fun checksTheConfirmationAndSendsTheLinkAgain() = runTest {
        val unverified = Fixtures.ME.replace("\"email_verified\":true", "\"email_verified\":false")
        val transport = StubTransport()
            .stub("POST", "auth/login", """{"token":"t","data":$unverified}""")
            .stub("GET", "auth/me", """{"data":$unverified}""")
            .stub("POST", "auth/email/verification-notification", "{}")
        val session = session(transport, backgroundScope)
        session.signIn("mira", "password")
        val model = VerifyEmailViewModel(session, RemoteAuthApi(transport.client), repository(transport))
        assertEquals("mira@demo.oasis.tld", model.email)
        model.check()
        assertTrue("still not confirmed", model.ui.value.checked)
        model.resend()
        assertTrue(model.ui.value.sent)

        transport.add("GET", "auth/me", json = """{"data":${Fixtures.ME}}""")
        model.check()
        assertFalse(model.ui.value.checked)
        assertTrue((session.state.value as SessionState.SignedIn).me.emailVerified)
    }
}
