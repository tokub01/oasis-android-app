package de.velwebsolutions.oasis.account.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.account.platform.PasskeyAuthenticator
import de.velwebsolutions.oasis.account.platform.PasskeyFailure
import de.velwebsolutions.oasis.account.platform.SIGN_IN_CANCELLED
import de.velwebsolutions.oasis.account.platform.credentialObject
import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.auth.Registration
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SignInMethods
import de.velwebsolutions.oasis.core.auth.SignInResult
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.asApiError
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Runs [block] and returns its error as [ApiError] (cancellation passes through). */
internal suspend fun attempt(block: suspend () -> Unit): ApiError? = catching(block).exceptionOrNull()?.asApiError()

/**
 * A bot-trap token for a form that goes out right away. The server refuses a form sent within three seconds of its
 * token (BotProtection::MIN_SECONDS), so the token waits that long here. The forms fetch theirs when their screen opens;
 * this is for a try without one.
 */
internal suspend fun AuthApi.registrationFormToSend(): RegistrationForm = registrationForm().also { delay(BOT_TRAP_MIN_AGE_MILLIS) }

/** BotProtection::MIN_SECONDS of the server, in milliseconds. */
private const val BOT_TRAP_MIN_AGE_MILLIS = 3_000L

/** The sign-in form (/anmelden). */
data class SignInUiState(
    val login: String = "",
    val password: String = "",
    val error: ApiError? = null,
    /** The ways without a password the server offers; null until `auth/methods` answered (then: e-mail code only). */
    val methods: SignInMethods? = null,
    /** The last password was refused: the screen offers a code by e-mail instead. */
    val passwordRefused: Boolean = false,
    /** A passkey sign-in that did not work, for the snackbar. */
    val notice: String? = null
) {
    val canSubmit: Boolean get() = login.isNotEmpty() && password.isNotEmpty()

    /** „Code per SMS“ only while the server can send SMS. */
    val offersSms: Boolean get() = methods?.smsAvailable == true

    /** „Mit Passkey anmelden“, if the device can, too. */
    val offersPasskeys: Boolean get() = methods?.passkeys == true
}

/**
 * Sign-in with e-mail or username and password, like /anmelden. The app keeps the device token until the member signs
 * out (or ends the sign-in under Geräte), which is what „Angemeldet bleiben“ does on the web. Without a password: a code
 * by e-mail or SMS (its own step) or a passkey. The screen asks which of those the server offers each time it is shown
 * until the server answered ([loadMethodsIfMissing]), so a first try while offline does not hide them for good.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(private val session: Session, private val auth: AuthApi) : ViewModel() {
    private val mutableUi = MutableStateFlow(SignInUiState())
    val ui: StateFlow<SignInUiState> = mutableUi.asStateFlow()

    fun setLogin(value: String) = mutableUi.update { it.copy(login = value) }

    fun setPassword(value: String) = mutableUi.update { it.copy(password = value) }

    /** Which ways without a password to offer; without an answer only the e-mail code (always on the server). */
    suspend fun loadMethods() {
        catching { auth.methods() }.onSuccess { methods -> mutableUi.update { it.copy(methods = methods) } }
    }

    /** [loadMethods] while the server has not answered yet: the form is shown (again), or the app came back. */
    suspend fun loadMethodsIfMissing() {
        if (ui.value.methods == null) loadMethods()
    }

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
        mutableUi.update { it.copy(error = error, passwordRefused = error is ApiError.Validation) }
        return challenge
    }

    /**
     * „Mit Passkey anmelden“: the server's options go to the system sheet, its assertion back to the server. A cancelled
     * sheet says so, like the web; everything else ends in [SignInUiState.notice] as well.
     */
    suspend fun signInWithPasskey(passkeys: PasskeyAuthenticator) {
        mutableUi.update { it.copy(notice = null) }
        val notice = try {
            val ceremony = auth.passkeyOptions()
            val assertion = passkeys.authenticate(ceremony.options.toString())
            session.signInWithPasskey(ceremony.challenge, credentialObject(assertion))
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PasskeyFailure) {
            failure.userMessage ?: SIGN_IN_CANCELLED
        } catch (error: Exception) {
            val failure = error.asApiError()
            if (failure is ApiError.TooManyRequests) PASSKEY_TOO_MANY else failure.userMessage
        }
        mutableUi.update { it.copy(notice = notice) }
    }

    fun dismissNotice() = mutableUi.update { it.copy(notice = null) }

    companion object {
        /** The web's text for a passkey sign-in refused with 429 (resources/js/passkeys.js). */
        const val PASSKEY_TOO_MANY = "Zu viele Versuche in kurzer Zeit. Bitte warte eine Minute."
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
    /** The bot-trap token, fetched when the screen opens; null until it arrived. */
    val form: RegistrationForm? = null,
    val status: String? = null,
    val error: ApiError? = null
) {
    val canSubmit: Boolean get() = email.isNotEmpty() && password.isNotEmpty()
}

/**
 * „Zwei-Faktor verloren“ (/zwei-faktor-hilfe), step 1 in the app: e-mail and password. The next steps prove access to
 * the mailbox and keep a waiting period with a cancel link, so they happen through the links in the e-mails, which
 * open the website. The bot-trap token is fetched when the screen opens, like the web form: the server refuses a form
 * sent within three seconds of its token, so a token fetched only for the request would always be refused; one fetched
 * for a try without it (offline when the screen opened) is kept for the next try.
 */
@HiltViewModel
class TwoFactorRecoveryViewModel @Inject constructor(private val auth: AuthApi, private val repository: AccountRepository) : ViewModel() {
    private val mutableUi = MutableStateFlow(TwoFactorRecoveryUiState())
    val ui: StateFlow<TwoFactorRecoveryUiState> = mutableUi.asStateFlow()

    init {
        viewModelScope.launch { loadForm() }
    }

    fun setEmail(value: String) = mutableUi.update { it.copy(email = value) }

    fun setPassword(value: String) = mutableUi.update { it.copy(password = value) }

    suspend fun submit() {
        val current = ui.value
        if (!current.canSubmit) return
        mutableUi.update { it.copy(error = null, status = null) }
        val error = attempt {
            // Without a token from the start (offline then), one is fetched now and waits until the server takes it.
            val form = current.form ?: auth.registrationFormToSend().also { fresh -> mutableUi.update { it.copy(form = fresh) } }
            val status = repository.requestTwoFactorRecovery(current.email, current.password, form)
            mutableUi.update { it.copy(status = status, password = "") }
        }
        mutableUi.update { it.copy(error = error) }
        // A refused token (too old, or sent too soon) is replaced, so the next try can succeed. Any other refusal keeps
        // it: a token is good for 24 hours, and a fresh one would have to wait three seconds again.
        if (error?.message("form") != null) loadForm()
    }

    /** A fresh token; without one the next try fetches its own. */
    private suspend fun loadForm() {
        catching { auth.registrationForm() }.onSuccess { form -> mutableUi.update { it.copy(form = form) } }
    }
}

/** „Passwort vergessen?“. */
data class ForgotPasswordUiState(val email: String = "", val status: String? = null, val error: ApiError? = null)

/** Always the same neutral answer, like the web. The link in the mail opens „Neues Passwort“ in the app (or the website). */
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

/** The three small pages of step 1 „Konto anlegen“ (docs/oasis-accounts.md, „Registrierung: Schritt 1 …“). */
enum class RegisterPage(val title: String, val fields: Set<String>) {
    /** 1a: display name and username. */
    Name("Wie sollen andere dich nennen?", setOf("display_name", "username")),

    /** 1b: e-mail address and password. */
    SignIn("Wie meldest du dich an?", setOf("email", "password")),

    /** 1c: birth date and consent, then „Konto anlegen“; also where refusals of no field of 1a or 1b show. */
    Finish("Fast geschafft", setOf("birth_date", "terms"));

    companion object {
        /** The first page with a field the server refused; refusals of no page's field (`form`) show on 1c. */
        fun of(fields: Set<String>): RegisterPage = entries.firstOrNull { page -> fields.any { it in page.fields } } ?: Finish
    }
}

/** What the live check says under the username of page 1a. */
sealed interface UsernameCheck {
    /** „@mira ist frei.“ */
    data class Free(val username: String) : UsernameCheck

    /** „@mira ist schon vergeben – wie wäre es mit @mira2?“ ([suggestion] null: only that it is taken). */
    data class Taken(val username: String, val suggestion: String?) : UsernameCheck

    /** The format is invalid: the server's message, shown at the field. */
    data class Invalid(val username: String, val message: String) : UsernameCheck
}

/** The rules of a username (CreateNewUser / UsernameAvailability on the server) and the suggestion from a name. */
object UsernameRules {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 30

    private val umlauts = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ß' to "ss")

    /** „Lena Bürger“ → „lenabuerger“: lower case, umlauts spelled out, only a–z, 0–9 and _, at most 30; "" below 3. */
    fun suggestion(displayName: String): String {
        val spelled = displayName.lowercase(Locale.GERMAN).map { umlauts[it] ?: it.toString() }.joinToString("")
        val kept = spelled.filter(::allowed).take(MAX_LENGTH)
        return if (kept.length < MIN_LENGTH) "" else kept
    }

    /** The server's message for a username of the wrong form, or null when the form is right. */
    fun formatProblem(username: String): String? = when {
        username.isEmpty() -> "Der Benutzername ist erforderlich."
        username.length < MIN_LENGTH -> "Der Benutzername muss mindestens $MIN_LENGTH Zeichen lang sein."
        username.length > MAX_LENGTH -> "Der Benutzername darf höchstens $MAX_LENGTH Zeichen lang sein."
        !username.all(::allowed) -> "Der Benutzername darf nur Buchstaben von a bis z, Zahlen und Unterstriche enthalten."
        else -> null
    }

    private fun allowed(char: Char) = char in 'a'..'z' || char in '0'..'9' || char == '_'
}

/** Registration, the first step of /testen, in three small pages. */
data class RegisterUiState(
    val registration: Registration = Registration(),
    val page: RegisterPage = RegisterPage.Name,
    /** The member typed in the username field: it no longer follows the display name. */
    val usernameEdited: Boolean = false,
    /** The live check of the username; null while nothing was checked (or the check failed). */
    val usernameCheck: UsernameCheck? = null,
    /** Empty until the member picks a date, like the web's date field. */
    val birthDate: LocalDate? = null,
    /** The bot-trap token, fetched when the screen opens; null until it arrived. */
    val form: RegistrationForm? = null,
    /** The checks of this device before „Weiter“ and „Konto anlegen“, by field. */
    val fieldErrors: Map<String, String> = emptyMap(),
    val error: ApiError? = null
) {
    /** The message at [field]: this device's check first, then the server's. */
    fun fieldError(field: String): String? = fieldErrors[field] ?: error?.message(field)

    /** „Mindestens 10 Zeichen“ of the live checklist is ticked. */
    val passwordLongEnough: Boolean get() = registration.password.length >= RegisterViewModel.PASSWORD_MIN_LENGTH
}

/**
 * Registration, the first step of /testen (CreateNewUser), in three small pages (round J): 1a name and username, with a
 * suggestion from the name and a live check; 1b e-mail and password, entered once; 1c birth date (18+) and terms. Each
 * page checks its own fields before „Weiter“; „Zurück“ keeps everything. Only „Konto anlegen“ on 1c sends the account;
 * refused fields lead back to the first page holding one. Confirming the e-mail address and the mobile number and
 * choosing the contribution follow. The bot-trap token is fetched when the screen opens, like the web form; when that
 * failed (offline), „Konto anlegen“ fetches one itself and keeps it. Only a token the server refused is replaced.
 */
@HiltViewModel
class RegisterViewModel @Inject constructor(private val session: Session, private val auth: AuthApi) : ViewModel() {
    private val mutableUi = MutableStateFlow(RegisterUiState())
    val ui: StateFlow<RegisterUiState> = mutableUi.asStateFlow()

    /** How long typing has to pause before the username is checked. */
    var checkDelay: Duration = 400.milliseconds

    private var checkJob: Job? = null

    /** The day the age is counted on (the server counts in Europe/Berlin). */
    var today: () -> LocalDate = { LocalDate.now(ZoneId.of("Europe/Berlin")) }

    init {
        viewModelScope.launch { loadForm() }
    }

    fun edit(change: (Registration) -> Registration) = mutableUi.update { it.copy(registration = change(it.registration)) }

    /** The display name; the username follows it as a suggestion until the member edits that. */
    fun setDisplayName(value: String) {
        mutableUi.update {
            val registration = it.registration.copy(displayName = value)
            it.copy(
                registration = if (it.usernameEdited) registration else registration.copy(username = UsernameRules.suggestion(value)),
                fieldErrors = it.fieldErrors - "display_name"
            )
        }
        if (!ui.value.usernameEdited) scheduleCheck()
    }

    /** Typed into the username field: from now on it stays as typed. */
    fun setUsername(value: String) {
        mutableUi.update {
            it.copy(registration = it.registration.copy(username = value), usernameEdited = true, fieldErrors = it.fieldErrors - "username")
        }
        scheduleCheck()
    }

    /** A tap on the suggestion „@mira2“. */
    fun takeSuggestion(username: String) = setUsername(username)

    fun setBirthDate(date: LocalDate) = mutableUi.update { it.copy(birthDate = date, fieldErrors = it.fieldErrors - "birth_date") }

    /** Checks the username after a pause in typing; an invalid form says so without asking the server. */
    private fun scheduleCheck() {
        checkJob?.cancel()
        val username = ui.value.registration.username
        val problem = UsernameRules.formatProblem(username)
        if (problem != null) {
            mutableUi.update { it.copy(usernameCheck = if (username.isEmpty()) null else UsernameCheck.Invalid(username, problem)) }
            return
        }
        mutableUi.update { it.copy(usernameCheck = null) }
        checkJob = viewModelScope.launch {
            delay(checkDelay)
            checkUsername(username)
        }
    }

    /** One check of [username]; a network error shows nothing (the final submit checks again). */
    internal suspend fun checkUsername(username: String) {
        val check = catching { auth.usernameAvailable(username) }.fold(
            onSuccess = { answer ->
                if (answer.available) UsernameCheck.Free(username) else UsernameCheck.Taken(username, answer.suggestion)
            },
            onFailure = { error -> error.asApiError().message("username")?.let { UsernameCheck.Invalid(username, it) } }
        )
        mutableUi.update { if (it.registration.username == username) it.copy(usernameCheck = check) else it }
    }

    /** „Weiter“: the page's own fields are checked here first. */
    fun next() {
        val current = ui.value
        val problems = problems(current, current.page)
        mutableUi.update { it.copy(fieldErrors = problems) }
        val taken = current.page == RegisterPage.Name && current.usernameCheck is UsernameCheck.Taken
        if (problems.isEmpty() && !taken) go(RegisterPage.entries[current.page.ordinal + 1])
    }

    /** „Zurück“: everything typed stays. */
    fun back() = go(RegisterPage.entries[(ui.value.page.ordinal - 1).coerceAtLeast(0)])

    private fun go(page: RegisterPage) = mutableUi.update { it.copy(page = page, fieldErrors = emptyMap()) }

    /** What this device finds wrong with the fields of [page]. */
    private fun problems(state: RegisterUiState, page: RegisterPage): Map<String, String> {
        val registration = state.registration
        val birthDate = state.birthDate
        val found = when (page) {
            RegisterPage.Name -> mapOf(
                "display_name" to (if (registration.displayName.isBlank()) "Der Anzeigename ist erforderlich." else null),
                "username" to UsernameRules.formatProblem(registration.username)
            )

            RegisterPage.SignIn -> mapOf(
                "email" to when {
                    registration.email.isBlank() -> "Die E-Mail-Adresse ist erforderlich."
                    !EMAIL.matches(registration.email.trim()) -> "Bitte gib eine gültige E-Mail-Adresse ein."
                    else -> null
                },
                "password" to when {
                    registration.password.isEmpty() -> "Das Passwort ist erforderlich."
                    !state.passwordLongEnough -> "Das Passwort muss mindestens $PASSWORD_MIN_LENGTH Zeichen lang sein."
                    else -> null
                }
            )

            RegisterPage.Finish -> mapOf(
                "birth_date" to when {
                    birthDate == null -> BIRTH_DATE_REQUIRED
                    birthDate.isAfter(today().minusYears(MIN_AGE)) -> TOO_YOUNG
                    else -> null
                },
                "terms" to (if (registration.acceptsTerms) null else TERMS_REQUIRED)
            )
        }
        return found.mapNotNull { (field, message) -> message?.let { field to it } }.toMap()
    }

    suspend fun loadForm() {
        catching { auth.registrationForm() }
            .onSuccess { form -> mutableUi.update { it.copy(form = form) } }
            .onFailure { error -> mutableUi.update { it.copy(error = error.asApiError()) } }
    }

    /** „Konto anlegen“ on page 1c: the only request that creates the account. */
    suspend fun submit() {
        val current = ui.value
        val problems = problems(current, RegisterPage.Finish)
        mutableUi.update { it.copy(error = null, fieldErrors = problems) }
        // A missing birth date is one of the problems.
        val birthDate = current.birthDate?.takeIf { problems.isEmpty() } ?: return
        val error = attempt {
            // Without a token from the start (offline then), one is fetched now and waits until the server takes it.
            val form = current.form ?: auth.registrationFormToSend().also { fresh -> mutableUi.update { it.copy(form = fresh) } }
            session.register(current.registration.copy(birthDate = birthDate), form)
        }
        if (error != null) {
            // Back to the first page holding a refused field, where its message shows.
            mutableUi.update { it.copy(error = error, page = RegisterPage.of(error.fieldMessages.keys)) }
            // A refused token (too old, or sent too soon) is replaced, so a second try can succeed; without a fresh one
            // the old one stays. Any other refusal (a field, the server) keeps it: a token is good for 24 hours, and a
            // fresh one would refuse a second try within three seconds (ticking the terms and sending again).
            if (error.message("form") != null) {
                catching { auth.registrationForm() }.onSuccess { fresh -> mutableUi.update { it.copy(form = fresh) } }
            }
        }
    }

    companion object {
        /** Laravel's message for the empty field, so both forms say the same. */
        const val BIRTH_DATE_REQUIRED = "Das Geburtsdatum ist erforderlich."

        /** The server's message below 18 (Oasis is 18+). */
        const val TOO_YOUNG = "Du musst mindestens 18 Jahre alt sein, um Oasis zu nutzen."

        /** Without the consent; names the documents like the checkbox (the server's CreateNewUser says the same). */
        const val TERMS_REQUIRED = "Bitte akzeptiere die Community-Regeln und die AGB."

        /** `Password::min(10)` of the server, the only password rule. */
        const val PASSWORD_MIN_LENGTH = 10

        const val MIN_AGE = 18L

        /** Something before and after one @ and a dot in the domain; the server checks the rest. */
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
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
