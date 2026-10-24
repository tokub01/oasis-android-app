package de.velwebsolutions.oasis.core.auth

import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.TokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** The app-wide sign-in state. */
sealed interface SessionState {
    data object Loading : SessionState

    data object SignedOut : SessionState

    data class SignedIn(val me: Me) : SessionState
}

/**
 * The app-wide sign-in state: owns the token, restores the session at start-up, signs in, out and registers, and keeps
 * `me` fresh. A 401 from any request signs the app out (the token was revoked, e.g. under Privatsphäre › Geräte or by
 * signing out on another device).
 */
class Session(
    private val tokenStore: TokenStore,
    /** The device name sent with sign-ins (shown in the token list), e.g. "Pixel 9 von Mira". */
    private val deviceName: String,
    private val scope: CoroutineScope,
) : TokenProvider {
    /** Set once by the DI graph: the API client needs the session for its token, the session needs the API. */
    lateinit var auth: AuthApi

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val signOutListeners = CopyOnWriteArrayList<() -> Unit>()

    /** The member, when signed in. */
    val me: Me?
        get() = (state.value as? SessionState.SignedIn)?.me

    /** Called on sign-out and when the token is revoked, so the live connection and push stop. */
    fun addOnSignedOut(listener: () -> Unit) {
        signOutListeners += listener
    }

    override fun currentToken(): String? = tokenStore.read()

    override fun onUnauthenticated() {
        scope.launch { handleUnauthenticated() }
    }

    /** Restore the stored token at start-up: signed in if the server still knows it, otherwise signed out. */
    suspend fun restore() {
        if (tokenStore.read() == null) {
            mutableState.value = SessionState.SignedOut
            return
        }
        mutableState.value = try {
            SessionState.SignedIn(auth.me())
        } catch (_: ApiError.Unauthenticated) {
            tokenStore.write(null)
            SessionState.SignedOut
        } catch (_: Exception) {
            // Offline at start-up: stay signed out of the UI but keep the token; the next start retries.
            SessionState.SignedOut
        }
    }

    /** First sign-in step. Returns [SignInResult.TwoFactorRequired] when the code is needed next. */
    suspend fun signIn(login: String, password: String): SignInResult =
        auth.login(login, password, deviceName).also(::apply)

    /** Second step with the TOTP code or a recovery code. */
    suspend fun completeTwoFactor(challenge: String, code: String?, recoveryCode: String?) {
        apply(auth.twoFactor(challenge, code, recoveryCode))
    }

    /** Creates the account (same rules as /testen) and signs this device in. */
    suspend fun register(registration: Registration, form: RegistrationForm) {
        apply(auth.register(registration, form, deviceName))
    }

    /** Reload `me` (after changes to profile, membership, 2FA, verification …). */
    suspend fun refreshMe() {
        if (me == null) return
        runCatching { auth.me() }.getOrNull()?.let { mutableState.value = SessionState.SignedIn(it) }
    }

    /** Replace `me` locally when an endpoint already returned the new state. */
    fun update(me: Me) {
        mutableState.value = SessionState.SignedIn(me)
    }

    /** Sign out this device: revoke the token on the server (best effort) and forget it. */
    suspend fun signOut() {
        signOutListeners.forEach { it() }
        runCatching { auth.logout() }
        tokenStore.write(null)
        mutableState.value = SessionState.SignedOut
    }

    internal fun handleUnauthenticated() {
        if (me == null) return
        signOutListeners.forEach { it() }
        tokenStore.write(null)
        mutableState.value = SessionState.SignedOut
    }

    private fun apply(result: SignInResult) {
        if (result is SignInResult.SignedIn) {
            tokenStore.write(result.token)
            mutableState.value = SessionState.SignedIn(result.me)
        }
    }
}
