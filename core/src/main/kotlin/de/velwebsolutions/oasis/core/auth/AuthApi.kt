package de.velwebsolutions.oasis.core.auth

import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.MessageResponse
import de.velwebsolutions.oasis.core.network.OasisJson
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Answer of a sign-in step: either the token, or a two-factor challenge. */
sealed interface SignInResult {
    data class SignedIn(val token: String, val me: Me) : SignInResult

    data class TwoFactorRequired(val challenge: String) : SignInResult
}

/** Where a sign-in code goes (`channel` of `auth/login-code`). */
enum class LoginCodeChannel(val wire: String) {
    Email("email"),
    Sms("sms")
}

/** The sign-in ways without a password the server offers (`auth/methods`); SMS only while it is set up. */
@Serializable
data class SignInMethods(
    val loginCodeChannels: List<String> = listOf("email"),
    val smsAvailable: Boolean = false,
    val passkeys: Boolean = false
)

/**
 * A WebAuthn ceremony from the server: the challenge ID to send back and the options JSON for Credential Manager
 * (PublicKeyCredentialRequestOptionsJSON for signing in, …CreationOptionsJSON for adding a passkey).
 */
@Serializable
data class PasskeyCeremony(val challenge: String, val options: JsonObject)

/** Registration form values from the server (bot traps). */
@Serializable
data class RegistrationForm(val honeypotField: String, val tokenField: String, val formToken: String)

/** What a new account needs (same fields as /testen). */
data class Registration(
    val displayName: String = "",
    val username: String = "",
    val email: String = "",
    val birthDate: LocalDate = LocalDate.now().minusYears(20),
    val password: String = "",
    val passwordConfirmation: String = "",
    val acceptsTerms: Boolean = false
)

@Serializable
internal data class TokenAnswer(
    val token: String? = null,
    val data: Me? = null,
    val twoFactor: Boolean? = null,
    val challenge: String? = null
)

@Serializable
internal data class LoginBody(val login: String, val password: String, val deviceName: String)

@Serializable
internal data class TwoFactorBody(val challenge: String, val code: String?, val recoveryCode: String?)

@Serializable
internal data class EmailBody(val email: String)

@Serializable
internal data class ResetPasswordBody(val token: String, val email: String, val password: String, val passwordConfirmation: String)

@Serializable
internal data class LoginCodeBody(val login: String, val channel: String)

@Serializable
internal data class LoginCodeVerifyBody(val login: String, val code: String, val deviceName: String)

@Serializable
internal data class PasskeySignInBody(val challenge: String, val credential: JsonObject, val deviceName: String)

/** Calls of `routes/api/v1/auth.php`. */
internal interface AuthService {
    @POST("auth/login")
    suspend fun login(@Body body: LoginBody): TokenAnswer

    @POST("auth/two-factor")
    suspend fun twoFactor(@Body body: TwoFactorBody): TokenAnswer

    @GET("auth/register")
    suspend fun registrationForm(): DataEnvelope<RegistrationForm>

    @POST("auth/register")
    suspend fun register(@Body body: JsonObject): TokenAnswer

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: EmailBody): MessageResponse

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body body: ResetPasswordBody): MessageResponse

    @GET("auth/me")
    suspend fun me(): DataEnvelope<Me>

    @POST("auth/logout")
    suspend fun logout()

    @POST("auth/email/verification-notification")
    suspend fun resendVerification()

    @GET("auth/methods")
    suspend fun methods(): DataEnvelope<SignInMethods>

    @POST("auth/login-code")
    suspend fun requestLoginCode(@Body body: LoginCodeBody)

    @POST("auth/login-code/verify")
    suspend fun verifyLoginCode(@Body body: LoginCodeVerifyBody): TokenAnswer

    @POST("auth/passkey/options")
    suspend fun passkeyOptions(): PasskeyCeremony

    @POST("auth/passkey")
    suspend fun passkey(@Body body: PasskeySignInBody): TokenAnswer
}

/** The sign-in calls the session and the account screens use. */
interface AuthApi {
    suspend fun login(login: String, password: String, deviceName: String): SignInResult

    suspend fun twoFactor(challenge: String, code: String?, recoveryCode: String?): SignInResult

    suspend fun registrationForm(): RegistrationForm

    suspend fun register(registration: Registration, form: RegistrationForm, deviceName: String): SignInResult

    suspend fun forgotPassword(email: String): String

    /** „Neues Passwort“ from the link of the reset mail (no sign-in); answers with the web's message. */
    suspend fun resetPassword(token: String, email: String, password: String, passwordConfirmation: String): String

    suspend fun me(): Me

    suspend fun logout()

    suspend fun resendVerification()

    /** Which ways without a password the sign-in screen offers. */
    suspend fun methods(): SignInMethods

    /** Sends a six-digit code if there is an account for [login]; the answer is the same either way. */
    suspend fun requestLoginCode(login: String, channel: LoginCodeChannel)

    /** Exchanges the code for a token, or the two-factor challenge (like [login]). */
    suspend fun verifyLoginCode(login: String, code: String, deviceName: String): SignInResult

    /** The assertion options of a passkey sign-in (discoverable credentials). */
    suspend fun passkeyOptions(): PasskeyCeremony

    /** Exchanges the assertion of Credential Manager (`authenticationResponseJson`) for a token. */
    suspend fun passkeySignIn(challenge: String, credential: JsonObject, deviceName: String): SignInResult
}

/** [AuthApi] over Retrofit. */
class RemoteAuthApi(client: ApiClient) : AuthApi {
    private val service = client.create<AuthService>()

    override suspend fun login(login: String, password: String, deviceName: String) =
        result(service.login(LoginBody(login, password, deviceName)))

    override suspend fun twoFactor(challenge: String, code: String?, recoveryCode: String?) =
        result(service.twoFactor(TwoFactorBody(challenge, code, recoveryCode)))

    override suspend fun registrationForm() = service.registrationForm().data

    override suspend fun register(registration: Registration, form: RegistrationForm, deviceName: String): SignInResult {
        // The field names are sent as they are (the bot-trap names come from the server).
        val fields = mapOf(
            "display_name" to registration.displayName,
            "username" to registration.username,
            "email" to registration.email,
            "birth_date" to OasisJson.formatDay(registration.birthDate),
            "password" to registration.password,
            "password_confirmation" to registration.passwordConfirmation,
            "terms" to if (registration.acceptsTerms) "1" else "0",
            "device_name" to deviceName,
            form.honeypotField to "",
            form.tokenField to form.formToken
        )
        return result(service.register(JsonObject(fields.mapValues { JsonPrimitive(it.value) })))
    }

    override suspend fun forgotPassword(email: String) = service.forgotPassword(EmailBody(email)).message

    override suspend fun resetPassword(token: String, email: String, password: String, passwordConfirmation: String) =
        service.resetPassword(ResetPasswordBody(token, email, password, passwordConfirmation)).message

    override suspend fun me() = service.me().data

    override suspend fun logout() = service.logout()

    override suspend fun resendVerification() = service.resendVerification()

    override suspend fun methods() = service.methods().data

    override suspend fun requestLoginCode(login: String, channel: LoginCodeChannel) =
        service.requestLoginCode(LoginCodeBody(login, channel.wire))

    override suspend fun verifyLoginCode(login: String, code: String, deviceName: String) =
        result(service.verifyLoginCode(LoginCodeVerifyBody(login, code, deviceName)))

    override suspend fun passkeyOptions() = service.passkeyOptions()

    override suspend fun passkeySignIn(challenge: String, credential: JsonObject, deviceName: String) =
        result(service.passkey(PasskeySignInBody(challenge, credential, deviceName)))

    private fun result(answer: TokenAnswer): SignInResult {
        if (answer.twoFactor == true && answer.challenge != null) return SignInResult.TwoFactorRequired(answer.challenge)
        val token = answer.token
        val me = answer.data
        if (token == null || me == null) throw ApiError.Decoding("Anmeldung ohne Token")
        return SignInResult.SignedIn(token, me)
    }
}
