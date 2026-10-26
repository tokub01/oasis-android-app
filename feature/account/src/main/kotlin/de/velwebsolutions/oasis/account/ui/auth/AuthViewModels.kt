package de.velwebsolutions.oasis.account.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.auth.Registration
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SignInResult
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.asApiError
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Runs [block] and returns its error as [ApiError] (cancellation passes through). */
internal suspend fun attempt(block: suspend () -> Unit): ApiError? = catching(block).exceptionOrNull()?.asApiError()

/** The sign-in form (/anmelden). */
data class SignInUiState(val login: String = "", val password: String = "", val error: ApiError? = null) {
    val canSubmit: Boolean get() = login.isNotEmpty() && password.isNotEmpty()
}

/**
 * Sign-in with e-mail or username and password, like /anmelden. The app keeps the device token until the member signs
 * out (or ends the sign-in under Geräte), which is what „Angemeldet bleiben“ does on the web.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(private val session: Session) : ViewModel() {
    private val mutableUi = MutableStateFlow(SignInUiState())
    val ui: StateFlow<SignInUiState> = mutableUi.asStateFlow()

    fun setLogin(value: String) = mutableUi.update { it.copy(login = value) }

    fun setPassword(value: String) = mutableUi.update { it.copy(password = value) }

    /** Signs in; returns the two-factor challenge when the code is needed next. */
    suspend fun signIn(): String? {
        val current = ui.value
        if (!current.canSubmit) return null
        mutableUi.update { it.copy(error = null) }
        var challenge: String? = null
        val error = attempt {
            val result = session.signIn(current.login, current.password)
            if (result is SignInResult.TwoFactorRequired) challenge = result.challenge
            mutableUi.update { it.copy(password = "") }
        }
        mutableUi.update { it.copy(error = error) }
        return challenge
    }
}

/** The second factor (/zwei-faktor-anmeldung). */
data class TwoFactorUiState(val code: String = "", val recoveryCode: String = "", val error: ApiError? = null) {
    val canSubmit: Boolean get() = code.isNotEmpty() || recoveryCode.isNotEmpty()
}

/** Second factor: the six-digit code from the authenticator app, or one recovery code. */
@HiltViewModel
class TwoFactorViewModel @Inject constructor(private val session: Session) : ViewModel() {
    private val mutableUi = MutableStateFlow(TwoFactorUiState())
    val ui: StateFlow<TwoFactorUiState> = mutableUi.asStateFlow()

    fun setCode(value: String) = mutableUi.update { it.copy(code = value) }

    fun setRecoveryCode(value: String) = mutableUi.update { it.copy(recoveryCode = value) }

    suspend fun submit(challenge: String) {
        val current = ui.value
        if (!current.canSubmit) return
        mutableUi.update { it.copy(error = null) }
        val code = current.code.trim()
        val recovery = current.recoveryCode.trim()
        val error = attempt {
            session.completeTwoFactor(challenge, code.ifEmpty { null }, if (code.isEmpty()) recovery else null)
        }
        mutableUi.update { it.copy(error = error) }
    }
}

/** „Zwei-Faktor verloren“, step 1. */
data class TwoFactorRecoveryUiState(
    val email: String = "",
    val password: String = "",
    val status: String? = null,
    val error: ApiError? = null
) {
    val canSubmit: Boolean get() = email.isNotEmpty() && password.isNotEmpty()
}

/**
 * „Zwei-Faktor verloren“ (/zwei-faktor-hilfe), step 1 in the app: e-mail and password. The next steps prove access to
 * the mailbox and keep a waiting period with a cancel link, so they happen through the links in the e-mails, which
 * open the website.
 */
@HiltViewModel
class TwoFactorRecoveryViewModel @Inject constructor(private val auth: AuthApi, private val repository: AccountRepository) : ViewModel() {
    private val mutableUi = MutableStateFlow(TwoFactorRecoveryUiState())
    val ui: StateFlow<TwoFactorRecoveryUiState> = mutableUi.asStateFlow()

    fun setEmail(value: String) = mutableUi.update { it.copy(email = value) }

    fun setPassword(value: String) = mutableUi.update { it.copy(password = value) }

    suspend fun submit() {
        val current = ui.value
        if (!current.canSubmit) return
        mutableUi.update { it.copy(error = null, status = null) }
        val error = attempt {
            val form = auth.registrationForm()
            val status = repository.requestTwoFactorRecovery(current.email, current.password, form)
            mutableUi.update { it.copy(status = status, password = "") }
        }
        mutableUi.update { it.copy(error = error) }
    }
}

/** „Passwort vergessen?“. */
data class ForgotPasswordUiState(val email: String = "", val status: String? = null, val error: ApiError? = null)

/** Always the same neutral answer, like the web. The link in the mail opens the website, where the password is set. */
@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(private val auth: AuthApi) : ViewModel() {
    private val mutableUi = MutableStateFlow(ForgotPasswordUiState())
    val ui: StateFlow<ForgotPasswordUiState> = mutableUi.asStateFlow()

    fun setEmail(value: String) = mutableUi.update { it.copy(email = value) }

    suspend fun send() {
        val email = ui.value.email
        if (email.isEmpty()) return
        mutableUi.update { it.copy(error = null) }
        val error = attempt {
            val status = auth.forgotPassword(email)
            mutableUi.update { it.copy(status = status) }
        }
        mutableUi.update { it.copy(error = error) }
    }
}

/** Registration step 1 of /testen. */
data class RegisterUiState(
    val registration: Registration = Registration(),
    /** Empty until the member picks a date, like the web's date field. */
    val birthDate: LocalDate? = null,
    val form: RegistrationForm? = null,
    val error: ApiError? = null,
    /** The birth date is missing (checked here, because the request always carries one). */
    val birthDateMissing: Boolean = false
)

/**
 * Registration, step 1 of 3 of /testen (CreateNewUser): name, username, e-mail, password, birth date (16+), terms.
 * Steps 2 and 3 (oases, people) are the welcome tour after the e-mail is confirmed. The bot-trap token is fetched when
 * the screen opens, like the web form.
 */
@HiltViewModel
class RegisterViewModel @Inject constructor(private val session: Session, private val auth: AuthApi) : ViewModel() {
    private val mutableUi = MutableStateFlow(RegisterUiState())
    val ui: StateFlow<RegisterUiState> = mutableUi.asStateFlow()

    init {
        viewModelScope.launch { loadForm() }
    }

    fun edit(change: (Registration) -> Registration) = mutableUi.update { it.copy(registration = change(it.registration)) }

    fun setBirthDate(date: LocalDate) = mutableUi.update { it.copy(birthDate = date, birthDateMissing = false) }

    suspend fun loadForm() {
        catching { auth.registrationForm() }
            .onSuccess { form -> mutableUi.update { it.copy(form = form) } }
            .onFailure { error -> mutableUi.update { it.copy(error = error.asApiError()) } }
    }

    suspend fun submit() {
        val current = ui.value
        val form = current.form ?: return
        mutableUi.update { it.copy(error = null) }
        val birthDate = current.birthDate
        if (birthDate == null) {
            mutableUi.update { it.copy(birthDateMissing = true) }
            return
        }
        val error = attempt { session.register(current.registration.copy(birthDate = birthDate), form) }
        if (error != null) {
            mutableUi.update { it.copy(error = error) }
            // A refused form token (too old) is replaced, so a second try can succeed.
            // Without a fresh one the old form stays; the error is on screen already.
            catching { auth.registrationForm() }.onSuccess { fresh -> mutableUi.update { it.copy(form = fresh) } }
        }
    }

    companion object {
        /** Laravel's message for the empty field, so both forms say the same. */
        const val BIRTH_DATE_REQUIRED = "Das Geburtsdatum ist erforderlich."
    }
}

/** The e-mail confirmation screen. */
data class VerifyEmailUiState(val sent: Boolean = false, val checked: Boolean = false, val code: String = "", val error: ApiError? = null) {
    /** Six digits typed (spaces and dashes of a pasted code do not count). */
    val canSubmitCode: Boolean get() = code.count { it.isDigit() } >= CODE_DIGITS

    private companion object {
        const val CODE_DIGITS = 6
    }
}

/** Shown to signed-in members whose e-mail address is not confirmed yet (/email/bestaetigen). */
@HiltViewModel
class VerifyEmailViewModel @Inject constructor(
    private val session: Session,
    private val auth: AuthApi,
    private val repository: AccountRepository
) : ViewModel() {
    private val mutableUi = MutableStateFlow(VerifyEmailUiState())
    val ui: StateFlow<VerifyEmailUiState> = mutableUi.asStateFlow()

    val email: String? get() = session.me?.email

    fun setCode(value: String) = mutableUi.update { it.copy(code = value) }

    /** „Bestätigen“: the code of the mail confirms the address; the app moves on with the confirmed member. */
    suspend fun submitCode() {
        mutableUi.update { it.copy(error = null) }
        catching { repository.verifyEmailCode(ui.value.code.trim()) }
            .onSuccess(session::update)
            .onFailure { error -> mutableUi.update { it.copy(error = error.asApiError()) } }
    }

    /** „Ich habe den Link geöffnet“: reload `me`; the app moves on by itself once the address is confirmed. */
    suspend fun check() {
        session.refreshMe()
        mutableUi.update { it.copy(checked = session.me?.emailVerified == false) }
    }

    suspend fun resend() {
        mutableUi.update { it.copy(error = null) }
        val error = attempt { auth.resendVerification() }
        mutableUi.update { if (error == null) it.copy(sent = true) else it.copy(error = error) }
    }

    suspend fun signOut() = session.signOut()
}
