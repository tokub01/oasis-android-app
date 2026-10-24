package de.velwebsolutions.oasis.chat.api

import de.velwebsolutions.oasis.chatcrypto.KeyGrant
import de.velwebsolutions.oasis.core.network.DataEnvelope
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The chat endpoints (`/api/v1/chat/…`, routes/api/v1/members/chat.php – the web's `/app/chat/api` behind the app token;
 * contract in docs/oasis-chat.md). Created with `KeyCoding.Verbatim`; request bodies are JSON objects built by
 * [de.velwebsolutions.oasis.chat.data.DefaultChatRepository], so explicit `null`s (no passphrase copy, no group name)
 * reach the server.
 */
interface ChatService {
    @GET("chat/identity")
    suspend fun identity(): OptionalIdentity

    @PUT("chat/identity")
    suspend fun saveIdentity(@Body body: JsonObject): DataEnvelope<StoredIdentity>

    @GET("chat/device-unlocks")
    suspend fun deviceUnlocks(): DataEnvelope<List<DeviceUnlockRow>>

    @POST("chat/device-unlocks")
    suspend fun saveDeviceUnlock(@Body body: JsonObject): DataEnvelope<DeviceUnlockRow>

    @DELETE("chat/device-unlocks/{id}")
    suspend fun deleteDeviceUnlock(@Path("id") id: String)

    @POST("chat/device-links")
    suspend fun requestDeviceLink(@Body body: JsonObject): DataEnvelope<CreatedDeviceLink>

    @GET("chat/device-links/{id}")
    suspend fun deviceLink(@Path("id") id: String): OptionalLinkApproval

    @GET("chat/device-links")
    suspend fun deviceLinks(): DataEnvelope<List<DeviceLinkRequest>>

    @POST("chat/device-links/{id}/approve")
    suspend fun approveDeviceLink(@Path("id") id: String, @Body body: JsonObject)

    @DELETE("chat/device-links/{id}")
    suspend fun rejectDeviceLink(@Path("id") id: String)

    @GET("chat/users")
    suspend fun findUser(@Query("username") username: String): DataEnvelope<List<PublicUser>>

    @GET("chat/settings")
    suspend fun settings(): ChatSettings

    @PATCH("chat/settings")
    suspend fun updateSetting(@Body body: JsonObject): ChatSettings

    @POST("chat/blocks")
    suspend fun block(@Body body: JsonObject)

    @GET("chat/safety")
    suspend fun safety(): DataEnvelope<SafetyLists>

    @GET("chat/conversations")
    suspend fun conversations(@Query("page") page: Int): ConversationPage

    @POST("chat/conversations")
    suspend fun createConversation(@Body body: JsonObject): DataEnvelope<ChatConversation>

    @GET("chat/conversations/{id}")
    suspend fun conversation(@Path("id") id: String): DataEnvelope<ChatConversation>

    @PATCH("chat/conversations/{id}")
    suspend fun updateConversation(@Path("id") id: String, @Body body: JsonObject): DataEnvelope<ChatConversation>

    @GET("chat/conversations/{id}/public-keys")
    suspend fun publicKeys(@Path("id") conversationId: String): DataEnvelope<List<PublicUser>>

    @POST("chat/conversations/{id}/members")
    suspend fun addMember(@Path("id") conversationId: String, @Body body: JsonObject): DataEnvelope<ChatConversation>

    @DELETE("chat/conversations/{id}/members/{user}")
    suspend fun removeMember(@Path("id") conversationId: String, @Path("user") userId: String): DataEnvelope<ChatConversation>

    @GET("chat/conversations/{id}/key-grants")
    suspend fun keyGrants(@Path("id") conversationId: String): DataEnvelope<List<KeyGrant>>

    @POST("chat/conversations/{id}/key-grants")
    suspend fun storeKeyGrants(@Path("id") conversationId: String, @Body body: JsonObject)

    @POST("chat/conversations/{id}/rotate")
    suspend fun activateKey(@Path("id") conversationId: String, @Body body: JsonObject): DataEnvelope<ChatConversation>

    @GET("chat/conversations/{id}/messages")
    suspend fun messages(@Path("id") conversationId: String, @Query("before_id") beforeId: String?): MessagePage

    @POST("chat/conversations/{id}/messages")
    suspend fun sendMessage(@Path("id") conversationId: String, @Body body: JsonObject)

    @POST("chat/conversations/{id}/read")
    suspend fun markRead(@Path("id") conversationId: String): UnreadCount

    @POST("chat/conversations/{id}/accept")
    suspend fun accept(@Path("id") conversationId: String)

    @PUT("chat/conversations/{id}/mute")
    suspend fun mute(@Path("id") conversationId: String, @Body body: JsonObject): DataEnvelope<ChatConversation>

    @DELETE("chat/conversations/{id}/mute")
    suspend fun unmute(@Path("id") conversationId: String): DataEnvelope<ChatConversation>

    @POST("chat/conversations/{id}/leave")
    suspend fun leave(@Path("id") conversationId: String)

    @POST("chat/conversations/{id}/reports")
    suspend fun report(@Path("id") conversationId: String, @Body body: JsonObject)
}
