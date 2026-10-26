package de.velwebsolutions.oasis.account.ui.auth

import androidx.lifecycle.ViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.auth.LoginCodeChannel
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SignInResult
import de.velwebsolutions.oasis.core.network.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** „Mit Code anmelden“: first the login, then – once a code is on its way – the six digits. */
data class LoginCodeUiState(
    val login: String,
    val channel: LoginCodeChannel,
    val code: String = "",
    /** A code was requested; the screen shows the neutral hint and the code field. */
    val sent: Boolean = false,
    val error: ApiError? = null
) {
    val canSend: Boolean get() = login.isNotBlank()

    /** Six digits typed (spaces of a pasted „123 456“ do not count). */
    val canSignIn: Boolean get() = code.count { it.isDigit() } >= CODE_DIGITS

    private companion object {
        const val CODE_DIGITS = 6
    }
}

/**
 * Sign-in without a password (/anmelden/code): a six-digit code by e-mail or SMS, valid ten minutes and five tries.
 * The server answers every request the same way, so the screen never reveals whether an account exists. A right code
 * signs in like the password: the second factor may follow.
 */
@HiltViewModel(assistedFactory = LoginCodeViewModel.Factory::class)
class LoginCodeViewModel @AssistedInject constructor(
    @Assisted channel: LoginCodeChannel,
    @Assisted login: String,
    private val session: Session,
    private val auth: AuthApi
) : ViewModel() {
    private val mutableUi = MutableStateFlow(LoginCodeUiState(login, channel))
    val ui: StateFlow<LoginCodeUiState> = mutableUi.asStateFlow()

    fun setLogin(value: String) = mutableUi.update { it.copy(login = value) }

    fun setCode(value: String) = mutableUi.update { it.copy(code = value) }

    /** „Code senden“ and „Neuen Code senden“. */
    suspend fun send() {
        val current = ui.value
        if (!current.canSend) return
        mutableUi.update { it.copy(error = null) }
        val error = attempt { auth.requestLoginCode(current.login.trim(), current.channel) }
        mutableUi.update { if (error == null) it.copy(sent = true, code = "") else it.copy(error = error) }
    }

    /** „Anmelden“; returns the two-factor challenge when the authenticator code is needed next. */
    suspend fun signIn(): String? {
        val current = ui.value
        if (!current.canSignIn) return null
        mutableUi.update { it.copy(error = null) }
        var challenge: String? = null
        val error = attempt {
            val result = session.signInWithCode(current.login.trim(), current.code.trim())
            if (result is SignInResult.TwoFactorRequired) challenge = result.challenge
        }
        mutableUi.update { it.copy(error = error) }
        return challenge
    }

    /** Back to the login field (another address or username). */
    fun changeLogin() = mutableUi.update { it.copy(sent = false, code = "", error = null) }

    /** Creates the view model for the channel and the login the sign-in screen hands over. */
    @AssistedFactory
    interface Factory {
        fun create(channel: LoginCodeChannel, login: String): LoginCodeViewModel
    }
}
