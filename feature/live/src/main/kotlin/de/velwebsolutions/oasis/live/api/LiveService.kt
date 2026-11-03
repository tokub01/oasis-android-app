package de.velwebsolutions.oasis.live.api

import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.MessageResponse
import de.velwebsolutions.oasis.core.network.Page
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** The body of `PUT hints/settings`; `kinds` keys stay as they are (`friend_request`). */
@Serializable
internal data class HintSettingsBody(
    val kinds: Map<String, Boolean>,
    val digest: Boolean,
    /** Left out when null: the stored choice stays. */
    val weeklyLetterEmail: Boolean?,
    val quietEnabled: Boolean,
    val quietFrom: String,
    val quietUntil: String
)

@Serializable
internal data class PauseBody(val days: Int)

@Serializable
internal data class SwitchBody(val value: Boolean)

@Serializable
internal data class QuietModeBody(val mode: String)

/** The FCM registration of this installation (`platform` android; Laravel's PushDeviceController checks the token). */
@Serializable
internal data class FcmRegistrationBody(val token: String, val platform: String)

/**
 * The Live endpoints of the Laravel API (routes/api/v1/members/live.php). Like `app-config`, the hint settings in every
 * answer (`push.available`, whether the `chat_push` switch is there) are answered for the asking `platform`.
 */
internal interface LiveService {
    @GET("hints")
    suspend fun hints(@Query("page") page: Int): Page<Hint>

    @POST("hints/read-all")
    suspend fun markAllRead(): MessageResponse

    @GET("hints/settings")
    suspend fun settings(@Query("platform") platform: String): DataEnvelope<HintSettings>

    @PUT("hints/settings")
    suspend fun save(@Query("platform") platform: String, @Body body: HintSettingsBody): Confirmed<HintSettings>

    @POST("hints/pause")
    suspend fun pause(@Query("platform") platform: String, @Body body: PauseBody): Confirmed<HintSettings>

    @DELETE("hints/pause")
    suspend fun resume(@Query("platform") platform: String): Confirmed<HintSettings>

    @POST("hints/quiet-mode")
    suspend fun startQuietMode(@Query("platform") platform: String, @Body body: QuietModeBody): Confirmed<HintSettings>

    @DELETE("hints/quiet-mode")
    suspend fun endQuietMode(@Query("platform") platform: String): Confirmed<HintSettings>

    @PUT("privacy/settings/{key}")
    suspend fun setChatSwitch(@Path("key") key: String, @Body body: SwitchBody): MessageResponse

    @DELETE("push/devices/{id}")
    suspend fun removeAppDevice(@Path("id") id: Int): MessageResponse

    @DELETE("push/browsers/{id}")
    suspend fun removeBrowser(@Path("id") id: Int): MessageResponse

    @POST("push/devices")
    suspend fun registerDevice(@Body body: FcmRegistrationBody): DataEnvelope<PushDevice>

    @GET("live/counts")
    suspend fun counts(): DataEnvelope<LiveCountsData>

    /** `push.available` is answered for the asking platform. */
    @GET("app-config")
    suspend fun appConfig(@Query("platform") platform: String): DataEnvelope<LiveAppConfig>
}
