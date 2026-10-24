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

/** Registration form values from the server (bot traps, trial length). */
@Serializable
data class RegistrationForm(val honeypotField: String, val tokenField: String, val formToken: String, val trialDays: Int)

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

    @GET("auth/me")
    suspend fun me(): DataEnvelope<Me>

    @POST("auth/logout")
    suspend fun logout()

    @POST("auth/email/verification-notification")
    suspend fun resendVerification()
}

/** The sign-in calls the session and the account screens use. */
interface AuthApi {
    suspend fun login(login: String, password: String, deviceName: String): SignInResult

    suspend fun twoFactor(challenge: String, code: String?, recoveryCode: String?): SignInResult

    suspend fun registrationForm(): RegistrationForm

    suspend fun register(registration: Registration, form: RegistrationForm, deviceName: String): SignInResult

    suspend fun forgotPassword(email: String): String

    suspend fun me(): Me

    suspend fun logout()

    suspend fun resendVerification()
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

    override suspend fun me() = service.me().data

    override suspend fun logout() = service.logout()

    override suspend fun resendVerification() = service.resendVerification()

    private fun result(answer: TokenAnswer): SignInResult {
        if (answer.twoFactor == true && answer.challenge != null) return SignInResult.TwoFactorRequired(answer.challenge)
        val token = answer.token
        val me = answer.data
        if (token == null || me == null) throw ApiError.Decoding("Anmeldung ohne Token")
        return SignInResult.SignedIn(token, me)
    }
}
