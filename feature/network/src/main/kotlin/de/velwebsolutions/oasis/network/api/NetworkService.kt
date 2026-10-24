package de.velwebsolutions.oasis.network.api

import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.MessageResponse
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
internal data class BodyInput(val body: String)

@Serializable
internal data class ProfileInput(val bio: String, val location: String, val avatarColor: String, val toneConfirmed: Boolean)

@Serializable
internal data class RoleInput(val role: String)

@Serializable
internal data class ConfirmInput(val confirm: Boolean)

/** The network endpoints (`/api/v1/network`, Laravel `routes/api/v1/members/network.php`). */
internal interface NetworkService {
    // Feeds

    @GET("network/source")
    suspend fun source(@Query("view") view: String?, @Query("cursor") cursor: String?): FeedPage<SourceContext>

    @GET("network/discover")
    suspend fun discover(@Query("cursor") cursor: String?): FeedPage<DiscoverContext>

    @GET("network/swipe")
    suspend fun swipe(): DataEnvelope<SwipeDeck>

    @GET("network/bookmarks")
    suspend fun bookmarks(@Query("cursor") cursor: String?): FeedPage<NoContext>

    @GET("network/statistics")
    suspend fun statistics(): DataEnvelope<MemberStatistics>

    @GET("network/search")
    suspend fun search(@Query("q") query: String, @Query("cursor") cursor: String?): FeedPage<SearchContext>

    @GET("network/tags/{tag}")
    suspend fun tag(@Path("tag") tag: String, @Query("cursor") cursor: String?): FeedPage<TagContext>

    // Tropfen

    @GET("network/posts/{id}")
    suspend fun post(@Path("id") id: Int): DataEnvelope<PostDetail>

    @GET("network/posts/{id}/edit")
    suspend fun editForm(@Path("id") id: Int): DataEnvelope<PostEditForm>

    @PUT("network/posts/{id}")
    suspend fun updatePost(@Path("id") id: Int, @Body body: BodyInput): MessageResponse

    @GET("network/posts/{id}/carry")
    suspend fun carrySource(@Path("id") id: Int): DataEnvelope<CarrySource>

    @GET("network/composer")
    suspend fun composer(
        @Query("oasis") oasis: String?,
        @Query("reply_to") replyTo: Int?,
        @Query("carried") carried: Int?,
    ): DataEnvelope<ComposerContext>

    @Multipart
    @POST("network/posts")
    suspend fun publish(@Part parts: List<MultipartBody.Part>): PublishResult

    @DELETE("network/posts/{id}")
    suspend fun deletePost(@Path("id") id: Int): MessageResponse

    @POST("network/posts/{id}/reaction")
    suspend fun react(@Path("id") id: Int): MessageResponse

    @DELETE("network/posts/{id}/reaction")
    suspend fun unreact(@Path("id") id: Int): MessageResponse

    @POST("network/posts/{id}/bookmark")
    suspend fun bookmark(@Path("id") id: Int): MessageResponse

    @DELETE("network/posts/{id}/bookmark")
    suspend fun unbookmark(@Path("id") id: Int): MessageResponse

    @POST("network/posts/{id}/remove-from-oasis")
    suspend fun removeFromOasis(@Path("id") id: Int): MessageResponse

    // Protecting oneself

    @POST("network/users/{id}/block")
    suspend fun block(@Path("id") userId: Int): MessageResponse

    @POST("network/users/{id}/mute")
    suspend fun mute(@Path("id") userId: Int): MessageResponse

    @DELETE("network/users/{id}/mute")
    suspend fun unmute(@Path("id") userId: Int): MessageResponse

    @POST("network/users/{id}/restrict")
    suspend fun restrict(@Path("id") userId: Int): MessageResponse

    @DELETE("network/users/{id}/restrict")
    suspend fun unrestrict(@Path("id") userId: Int): MessageResponse

    // Profiles

    @GET("network/profiles/{handle}")
    suspend fun profile(@Path("handle") handle: String): DataEnvelope<Profile>

    @GET("network/profiles/{handle}/posts")
    suspend fun profilePosts(@Path("handle") handle: String, @Query("cursor") cursor: String?): FeedPage<NoContext>

    @POST("network/profiles/{handle}/follow")
    suspend fun follow(@Path("handle") handle: String): MessageResponse

    @DELETE("network/profiles/{handle}/follow")
    suspend fun unfollow(@Path("handle") handle: String): MessageResponse

    @POST("network/profiles/{handle}/friendship")
    suspend fun requestFriendship(@Path("handle") handle: String): MessageResponse

    @POST("network/profiles/{handle}/friendship/accept")
    suspend fun acceptFriendship(@Path("handle") handle: String): MessageResponse

    @DELETE("network/profiles/{handle}/friendship")
    suspend fun removeFriendship(@Path("handle") handle: String): MessageResponse

    @GET("network/profile")
    suspend fun ownProfile(): DataEnvelope<OwnProfile>

    @PUT("network/profile")
    suspend fun updateOwnProfile(@Body body: ProfileInput): Confirmed<OwnProfile>

    @Multipart
    @POST("network/profile/photo")
    suspend fun uploadPhoto(@Part photo: MultipartBody.Part): Confirmed<OwnProfile>

    @DELETE("network/profile/photo")
    suspend fun removePhoto(): Confirmed<OwnProfile>

    @Multipart
    @POST("network/profile/banner")
    suspend fun uploadProfileBanner(@Part banner: MultipartBody.Part): Confirmed<OwnProfile>

    @DELETE("network/profile/banner")
    suspend fun removeProfileBanner(): Confirmed<OwnProfile>

    @GET("network/friends")
    suspend fun friends(): DataEnvelope<FriendLists>

    @POST("network/friends/{id}/accept")
    suspend fun acceptFriend(@Path("id") userId: Int): MessageResponse

    @DELETE("network/friends/{id}")
    suspend fun removeFriend(@Path("id") userId: Int): MessageResponse

    // Oases

    @GET("network/oases")
    suspend fun oases(): DataEnvelope<OasisDirectory>

    @GET("network/oases/{slug}")
    suspend fun oasis(@Path("slug") slug: String): DataEnvelope<OasisDetail>

    @GET("network/oases/{slug}/posts")
    suspend fun oasisPosts(@Path("slug") slug: String, @Query("cursor") cursor: String?): FeedPage<NoContext>

    @GET("network/oases/{slug}/members")
    suspend fun oasisMembers(@Path("slug") slug: String, @Query("q") query: String?): DataEnvelope<OasisMembers>

    @POST("network/oases/{slug}/follow")
    suspend fun followOasis(@Path("slug") slug: String): MessageResponse

    @DELETE("network/oases/{slug}/follow")
    suspend fun unfollowOasis(@Path("slug") slug: String): MessageResponse

    @Multipart
    @POST("network/oases")
    suspend fun createOasis(@Part parts: List<MultipartBody.Part>): DataEnvelope<OasisSlug>

    @GET("network/oases/{slug}/edit")
    suspend fun oasisEditForm(@Path("slug") slug: String): DataEnvelope<OasisEditForm>

    @PUT("network/oases/{slug}")
    suspend fun updateOasis(@Path("slug") slug: String, @Body input: OasisInput): DataEnvelope<OasisSlug>

    @Multipart
    @POST("network/oases/{slug}/banner")
    suspend fun uploadBanner(@Path("slug") slug: String, @Part banner: MultipartBody.Part): MessageResponse

    @DELETE("network/oases/{slug}/banner")
    suspend fun removeBanner(@Path("slug") slug: String): MessageResponse

    @PUT("network/oases/{slug}/members/{id}/role")
    suspend fun setRole(@Path("slug") slug: String, @Path("id") userId: Int, @Body body: RoleInput): Confirmed<RoleChange>

    @GET("network/oases/{slug}/close")
    suspend fun oasisClosing(@Path("slug") slug: String): DataEnvelope<OasisClosing>

    @HTTP(method = "DELETE", path = "network/oases/{slug}", hasBody = true)
    suspend fun closeOasis(@Path("slug") slug: String, @Body body: ConfirmInput): MessageResponse

    @GET("network/oases/{slug}/statistics")
    suspend fun oasisStatistics(@Path("slug") slug: String): DataEnvelope<OasisStatistics>

    // Invitations

    @GET("network/invitations")
    suspend fun invitations(): DataEnvelope<InvitationOverview>

    @POST("network/invitations")
    suspend fun createInvitation(): MessageResponse

    @GET("network/invitations/{token}")
    suspend fun invitation(@Path("token") token: String): DataEnvelope<InvitationLanding>
}
