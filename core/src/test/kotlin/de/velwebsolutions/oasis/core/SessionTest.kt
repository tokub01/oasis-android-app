package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.LoginCodeChannel
import de.velwebsolutions.oasis.core.auth.PasskeyCeremony
import de.velwebsolutions.oasis.core.auth.Registration
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.auth.SignInMethods
import de.velwebsolutions.oasis.core.auth.SignInResult
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.ApiError
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTest {
    private val mira = Me(7, "mira", "Mira Sommer", "MS", "mira@example.org")

    private class FakeAuth(var meResult: Result<Me>) : AuthApi {
        var loggedOut = false

        override suspend fun login(login: String, password: String, deviceName: String) =
            SignInResult.SignedIn("new", meResult.getOrThrow())

        override suspend fun twoFactor(challenge: String, code: String?, recoveryCode: String?) = login("", "", "")

        override suspend fun registrationForm() = RegistrationForm("hp", "tf", "t")

        override suspend fun register(registration: Registration, form: RegistrationForm, deviceName: String) = login("", "", "")

        override suspend fun forgotPassword(email: String) = "ok"

        override suspend fun me() = meResult.getOrThrow()

        override suspend fun logout() {
            loggedOut = true
        }

        override suspend fun resendVerification() = Unit

        override suspend fun methods() = SignInMethods()

        override suspend fun requestLoginCode(login: String, channel: LoginCodeChannel) = Unit

        override suspend fun verifyLoginCode(login: String, code: String, deviceName: String) = login("", "", "")

        override suspend fun passkeyOptions() = PasskeyCeremony("c", JsonObject(emptyMap()))

        override suspend fun passkeySignIn(challenge: String, credential: JsonObject, deviceName: String) = login("", "", "")
    }

    @Test
    fun `restores a stored token`() = runTest {
        val session = Session(InMemoryTokenStore("t"), "Pixel", TestScope()).apply { auth = FakeAuth(Result.success(mira)) }
        session.restore()
        assertEquals(SessionState.SignedIn(mira), session.state.value)
    }

    @Test
    fun `forgets a revoked token at start`() = runTest {
        val tokens = InMemoryTokenStore("t")
        val session = Session(tokens, "Pixel", TestScope()).apply { auth = FakeAuth(Result.failure(ApiError.Unauthenticated)) }
        session.restore()
        assertEquals(SessionState.SignedOut, session.state.value)
        assertNull(tokens.read())
    }

    @Test
    fun `keeps the token while offline`() = runTest {
        val tokens = InMemoryTokenStore("t")
        val session = Session(tokens, "Pixel", TestScope()).apply { auth = FakeAuth(Result.failure(ApiError.Transport("offline"))) }
        session.restore()
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals("t", tokens.read())
    }

    @Test
    fun `sign in stores the token and sign out forgets it`() = runTest {
        val tokens = InMemoryTokenStore()
        val auth = FakeAuth(Result.success(mira))
        var stopped = 0
        val session = Session(tokens, "Pixel", TestScope()).apply { this.auth = auth }
        session.addOnSignedOut { stopped++ }
        session.signIn("mira", "geheim")
        assertEquals("new", tokens.read())
        session.signOut()
        assertNull(tokens.read())
        assertEquals(1, stopped)
        assertEquals(true, auth.loggedOut)
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun `two factor, registration and refresh keep the session current`() = runTest {
        val tokens = InMemoryTokenStore()
        val auth = FakeAuth(Result.success(mira))
        val session = Session(tokens, "Pixel", TestScope()).apply { this.auth = auth }
        session.refreshMe()
        assertEquals(SessionState.Loading, session.state.value)
        session.completeTwoFactor("ch", "123456", null)
        assertEquals("new", tokens.read())
        session.register(Registration(), RegistrationForm("hp", "tf", "t"))
        assertEquals(mira, session.me)
        val renamed = mira.copy(displayName = "Mira S.")
        auth.meResult = Result.success(renamed)
        session.refreshMe()
        assertEquals(renamed, session.me)
        auth.meResult = Result.failure(ApiError.Transport("offline"))
        session.refreshMe()
        assertEquals(renamed, session.me)
        session.update(mira)
        assertEquals(mira, session.me)
        assertEquals("new", session.currentToken())
    }

    @Test
    fun `a code or a passkey signs in like the password`() = runTest {
        val tokens = InMemoryTokenStore()
        val session = Session(tokens, "Pixel", TestScope()).apply { auth = FakeAuth(Result.success(mira)) }
        assertEquals(SignInResult.SignedIn("new", mira), session.signInWithCode("mira", "123 456"))
        assertEquals("new", tokens.read())
        tokens.write(null)
        session.signInWithPasskey("c", JsonObject(emptyMap()))
        assertEquals("new", tokens.read())
        assertEquals(mira, session.me)
    }

    @Test
    fun `a two factor challenge does not sign in yet`() = runTest {
        val tokens = InMemoryTokenStore()
        val session = Session(tokens, "Pixel", TestScope()).apply {
            auth = object : AuthApi by FakeAuth(Result.success(mira)) {
                override suspend fun login(login: String, password: String, deviceName: String) = SignInResult.TwoFactorRequired("ch")
            }
        }
        assertEquals(SignInResult.TwoFactorRequired("ch"), session.signIn("mira", "pw"))
        assertNull(tokens.read())
    }

    @Test
    fun `a revoked token signs out once, wherever it was noticed`() = runTest {
        val tokens = InMemoryTokenStore("t")
        val scope = TestScope(testScheduler)
        var stopped = 0
        val session = Session(tokens, "Pixel", scope).apply { auth = FakeAuth(Result.success(mira)) }
        session.addOnSignedOut { stopped++ }
        session.onUnauthenticated()
        scope.testScheduler.advanceUntilIdle()
        assertEquals(0, stopped)
        session.restore()
        session.onUnauthenticated()
        scope.testScheduler.advanceUntilIdle()
        assertEquals(1, stopped)
        assertNull(tokens.read())
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun `sign out still forgets the token when the server cannot be reached`() = runTest {
        val tokens = InMemoryTokenStore("t")
        val session = Session(tokens, "Pixel", TestScope()).apply {
            auth = object : AuthApi by FakeAuth(Result.success(mira)) {
                override suspend fun logout() = throw ApiError.Transport("offline")
            }
        }
        session.signOut()
        assertNull(tokens.read())
    }

    @Test
    fun `the sign-in calls come from their source once, on first use`() = runTest {
        var asked = 0
        val session = Session(InMemoryTokenStore("t"), "Pixel", TestScope()) {
            asked++
            FakeAuth(Result.success(mira))
        }
        assertEquals(0, asked)
        session.restore()
        session.refreshMe()
        assertEquals(1, asked)
        assertEquals(SessionState.SignedIn(mira), session.state.value)
    }

    @Test(expected = IllegalStateException::class)
    fun `a session without a source for its calls says so`() {
        Session(InMemoryTokenStore(), "Pixel", TestScope()).auth
    }
}
