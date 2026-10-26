package de.velwebsolutions.oasis.account.api

import de.velwebsolutions.oasis.core.auth.PasskeyCeremony
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.MessageResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
internal data class ProfileBody(val displayName: String, val email: String)

@Serializable
internal data class PasswordBody(val currentPassword: String, val password: String, val passwordConfirmation: String)

@Serializable
internal data class ConfirmPasswordBody(val password: String)

@Serializable
internal data class CodeBody(val code: String)

@Serializable
internal data class DeleteBody(val confirm: Boolean)

@Serializable
internal data class StatementBody(val statement: String)

@Serializable
internal data class ReasonBody(val reason: String)

@Serializable
internal data class WordBody(val word: String)

@Serializable
internal data class ReportBody(val url: String, val category: String?, val comment: String)

@Serializable
internal data class WelcomeProfileBody(val bio: String, val location: String, val avatarColor: String, val toneConfirmed: Boolean)

@Serializable
internal data class WelcomeVisibilityBody(val profileAudience: String, val defaultPostVisibility: String)

@Serializable
internal data class BallotBody(val option: Int?)

@Serializable
internal data class NewPasskeyBody(val challenge: String, val credential: JsonObject, val name: String?)

@Serializable
internal data class NameBody(val name: String)

@Serializable
internal data class PhoneBody(val phoneNumber: String)

/**
 * The account area's endpoints (`routes/api/v1/members/account.php`: `api.v1.account.*`, `api.v1.suspension.*`,
 * `api.v1.complaints.*`, `api.v1.privacy.*`, `api.v1.safety.*`, `api.v1.report.*`, `api.v1.onboarding.*`,
 * `api.v1.votes.*`, `api.v1.glossary`, the passkeys and the mobile number) and the guest step of the two-factor
 * recovery.
 */
internal interface AccountService {
    @GET("account")
    suspend fun settings(): DataEnvelope<AccountSettings>

    @PUT("account/profile")
    suspend fun updateProfile(@Body body: ProfileBody): ProfileChange

    @PUT("account/password")
    suspend fun updatePassword(@Body body: PasswordBody): MessageResponse

    @POST("account/confirm-password")
    suspend fun confirmPassword(@Body body: ConfirmPasswordBody): DataEnvelope<AccountSettings>

    @POST("account/two-factor")
    suspend fun enableTwoFactor(): SettingsChange

    @POST("account/two-factor/confirm")
    suspend fun confirmTwoFactor(@Body body: CodeBody): SettingsChange

    @POST("auth/email/verify-code")
    suspend fun verifyEmailCode(@Body body: CodeBody): DataEnvelope<Me>

    @POST("account/two-factor/recovery-codes")
    suspend fun regenerateRecoveryCodes(): SettingsChange

    @DELETE("account/two-factor")
    suspend fun disableTwoFactor(): SettingsChange

    @HTTP(method = "DELETE", path = "account", hasBody = true)
    suspend fun deleteAccount(@Body body: DeleteBody)

    /** The body's keys are sent as they are (the bot-trap names come from the server). */
    @POST("auth/two-factor-recovery")
    suspend fun requestTwoFactorRecovery(@Body body: JsonObject): MessageResponse

    /** A list, by Laravel's resource collection wrapped in `data` (both shapes are read). */
    @GET("account/passkeys")
    suspend fun passkeys(): JsonElement

    @POST("account/passkeys/options")
    suspend fun passkeyOptions(): PasskeyCeremony

    @POST("account/passkeys")
    suspend fun addPasskey(@Body body: NewPasskeyBody)

    @PATCH("account/passkeys/{id}")
    suspend fun renamePasskey(@Path("id") id: Int, @Body body: NameBody)

    @DELETE("account/passkeys/{id}")
    suspend fun removePasskey(@Path("id") id: Int)

    @GET("account/phone")
    suspend fun phone(): DataEnvelope<PhoneNumberStatus>

    @POST("account/phone")
    suspend fun requestPhoneCode(@Body body: PhoneBody): DataEnvelope<PhoneNumberStatus>

    @POST("account/phone/confirm")
    suspend fun confirmPhone(@Body body: CodeBody): DataEnvelope<PhoneNumberStatus>

    @DELETE("account/phone")
    suspend fun removePhone()

    @GET("suspension")
    suspend fun suspension(): DataEnvelope<Suspension>

    @PUT("suspension/statement")
    suspend fun saveStatement(@Body body: StatementBody): StatementSaved

    @GET("complaints/{decision}")
    suspend fun complaintDecision(@Path("decision") id: Int): DataEnvelope<ComplaintDecision>

    @POST("complaints/{decision}")
    suspend fun submitComplaint(@Path("decision") id: Int, @Body body: ReasonBody)

    @GET("privacy")
    suspend fun privacy(): DataEnvelope<PrivacyCentre>

    @PUT("privacy/settings/{key}")
    suspend fun updatePrivacy(@Path("key") key: String, @Body body: JsonObject): Confirmed<PrivacySettingItem>

    @POST("privacy/limit-past-posts")
    suspend fun limitPastPosts(): MessageResponse

    @GET("privacy/words")
    suspend fun hiddenWords(): DataEnvelope<HiddenWords>

    @POST("privacy/words")
    suspend fun addHiddenWord(@Body body: WordBody): Confirmed<HiddenWords>

    @HTTP(method = "DELETE", path = "privacy/words", hasBody = true)
    suspend fun removeHiddenWord(@Body body: WordBody): Confirmed<HiddenWords>

    @GET("privacy/devices")
    suspend fun devices(): DataEnvelope<DeviceOverview>

    @DELETE("privacy/devices/apps/{id}")
    suspend fun signOutApp(@Path("id") id: Int): MessageResponse

    @DELETE("privacy/devices/sessions/{key}")
    suspend fun endSession(@Path("key") key: String): MessageResponse

    @POST("privacy/devices/sign-out-others")
    suspend fun signOutEverywhereElse(): MessageResponse

    @DELETE("privacy/devices/known/{id}")
    suspend fun forgetKnownDevice(@Path("id") id: Int): MessageResponse

    @DELETE("privacy/devices/chat/{id}")
    suspend fun forgetChatDevice(@Path("id") id: Int): MessageResponse

    @GET("safety")
    suspend fun safety(): DataEnvelope<SafetyLists>

    @DELETE("safety/{list}/{user}")
    suspend fun lift(@Path("list") list: String, @Path("user") userId: Int): MessageResponse

    @GET("report")
    suspend fun reportForm(@Query("url") url: String): DataEnvelope<ReportForm>

    @POST("report")
    suspend fun report(@Body body: ReportBody)

    @GET("onboarding")
    suspend fun welcome(): DataEnvelope<WelcomeTour>

    @PUT("onboarding/profile")
    suspend fun saveWelcomeProfile(@Body body: WelcomeProfileBody)

    @PUT("onboarding/visibility")
    suspend fun saveWelcomeVisibility(@Body body: WelcomeVisibilityBody)

    @POST("onboarding/oases/{id}")
    suspend fun toggleStarterOasis(@Path("id") id: Int): OasisJoined

    @POST("onboarding/finish")
    suspend fun finishWelcome()

    @GET("votes")
    suspend fun votes(): DataEnvelope<VotesOverview>

    @POST("votes/{id}")
    suspend fun cast(@Path("id") id: Int, @Body body: BallotBody): MessageResponse

    @GET("glossary")
    suspend fun glossary(): DataEnvelope<List<GlossaryTerm>>
}
