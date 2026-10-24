package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Registration
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.auth.SignInResult
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.ApiError
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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

        override suspend fun registrationForm() = RegistrationForm("hp", "tf", "t", 14)

        override suspend fun register(registration: Registration, form: RegistrationForm, deviceName: String) = login("", "", "")

        override suspend fun forgotPassword(email: String) = "ok"

        override suspend fun me() = meResult.getOrThrow()

        override suspend fun logout() {
            loggedOut = true
        }

        override suspend fun resendVerification() = Unit
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
}
