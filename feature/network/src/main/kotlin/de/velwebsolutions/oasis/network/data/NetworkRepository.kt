package de.velwebsolutions.oasis.network.data

import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.network.api.BodyInput
import de.velwebsolutions.oasis.network.api.CarrySource
import de.velwebsolutions.oasis.network.api.ComposerContext
import de.velwebsolutions.oasis.network.api.ConfirmInput
import de.velwebsolutions.oasis.network.api.DiscoverContext
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.FriendLists
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.InvitationLanding
import de.velwebsolutions.oasis.network.api.InvitationOverview
import de.velwebsolutions.oasis.network.api.MemberStatistics
import de.velwebsolutions.oasis.network.api.NetworkService
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisClosing
import de.velwebsolutions.oasis.network.api.OasisDetail
import de.velwebsolutions.oasis.network.api.OasisDirectory
import de.velwebsolutions.oasis.network.api.OasisEditForm
import de.velwebsolutions.oasis.network.api.OasisInput
import de.velwebsolutions.oasis.network.api.OasisMembers
import de.velwebsolutions.oasis.network.api.OasisStatistics
import de.velwebsolutions.oasis.network.api.OwnProfile
import de.velwebsolutions.oasis.network.api.PostDetail
import de.velwebsolutions.oasis.network.api.PostDraft
import de.velwebsolutions.oasis.network.api.PostEditForm
import de.velwebsolutions.oasis.network.api.Profile
import de.velwebsolutions.oasis.network.api.ProfileInput
import de.velwebsolutions.oasis.network.api.PublishResult
import de.velwebsolutions.oasis.network.api.RoleChange
import de.velwebsolutions.oasis.network.api.RoleInput
import de.velwebsolutions.oasis.network.api.SearchContext
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.api.SwipeDeck
import de.velwebsolutions.oasis.network.api.TagContext
import javax.inject.Inject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** The network area's calls (NetworkAPI on iOS): feeds, Tropfen, profiles, friends, oases and invitations. */
interface NetworkRepository {
    // Feeds
    suspend fun source(view: String?, cursor: String?): FeedPage<SourceContext>

    suspend fun discover(cursor: String?): FeedPage<DiscoverContext>

    suspend fun swipe(): SwipeDeck

    suspend fun bookmarks(cursor: String?): FeedPage<NoContext>

    suspend fun statistics(): MemberStatistics

    suspend fun search(query: String, cursor: String?): FeedPage<SearchContext>

    suspend fun tag(tag: String, cursor: String?): FeedPage<TagContext>

    // Tropfen
    suspend fun post(id: Int): PostDetail

    suspend fun editForm(id: Int): PostEditForm

    suspend fun updatePost(id: Int, body: String): String

    suspend fun carrySource(id: Int): CarrySource

    suspend fun composer(oasisSlug: String?, replyTo: Int?, carried: Int?): ComposerContext

    /** Publishes a Tropfen as multipart (`images[]`, `alts[]` in the same order). */
    suspend fun publish(draft: PostDraft): PublishResult

    suspend fun deletePost(id: Int): String

    suspend fun react(id: Int, on: Boolean): String

    suspend fun bookmark(id: Int, on: Boolean): String

    suspend fun removeFromOasis(id: Int): String

    // Protecting oneself
    suspend fun block(userId: Int): String

    suspend fun mute(userId: Int, on: Boolean): String

    suspend fun restrict(userId: Int, on: Boolean): String

    // Profiles
    suspend fun profile(handle: String): Profile

    suspend fun profilePosts(handle: String, cursor: String?): FeedPage<NoContext>

    suspend fun follow(handle: String, on: Boolean): String

    suspend fun requestFriendship(handle: String): String

    suspend fun acceptFriendship(handle: String): String

    suspend fun removeFriendship(handle: String): String

    suspend fun ownProfile(): OwnProfile

    suspend fun updateOwnProfile(bio: String, location: String, avatarColor: String, toneConfirmed: Boolean): Confirmed<OwnProfile>

    suspend fun uploadPhoto(image: ImageUpload): Confirmed<OwnProfile>

    suspend fun removePhoto(): Confirmed<OwnProfile>

    suspend fun uploadProfileBanner(image: ImageUpload): Confirmed<OwnProfile>

    suspend fun removeProfileBanner(): Confirmed<OwnProfile>

    suspend fun friends(): FriendLists

    suspend fun acceptFriend(userId: Int): String

    suspend fun removeFriend(userId: Int): String

    // Oases
    suspend fun oases(): OasisDirectory

    suspend fun oasis(slug: String): OasisDetail

    suspend fun oasisPosts(slug: String, cursor: String?): FeedPage<NoContext>

    suspend fun oasisMembers(slug: String, query: String): OasisMembers

    suspend fun followOasis(slug: String, on: Boolean): String

    /** Founds an oasis (multipart with the optional banner) and returns its slug. */
    suspend fun createOasis(input: OasisInput, banner: ImageUpload?): String

    suspend fun oasisEditForm(slug: String): OasisEditForm

    suspend fun updateOasis(slug: String, input: OasisInput): String

    suspend fun uploadBanner(slug: String, image: ImageUpload): String

    suspend fun removeBanner(slug: String): String

    /** Returns the confirmation and whether the acting member is still a host. */
    suspend fun setRole(slug: String, userId: Int, role: String): Confirmed<RoleChange>

    suspend fun oasisClosing(slug: String): OasisClosing

    suspend fun closeOasis(slug: String, confirm: Boolean): String

    suspend fun oasisStatistics(slug: String): OasisStatistics

    // Invitations
    suspend fun invitations(): InvitationOverview

    suspend fun createInvitation(): String

    suspend fun invitation(token: String): InvitationLanding
}

/** [NetworkRepository] over Retrofit. */
class DefaultNetworkRepository @Inject constructor(client: ApiClient) : NetworkRepository {
    private val service = client.create<NetworkService>()

    override suspend fun source(view: String?, cursor: String?) = service.source(view, cursor)

    override suspend fun discover(cursor: String?) = service.discover(cursor)

    override suspend fun swipe() = service.swipe().data

    override suspend fun bookmarks(cursor: String?) = service.bookmarks(cursor)

    override suspend fun statistics() = service.statistics().data

    override suspend fun search(query: String, cursor: String?) = service.search(query, cursor)

    override suspend fun tag(tag: String, cursor: String?) = service.tag(escape(tag), cursor)

    override suspend fun post(id: Int) = service.post(id).data

    override suspend fun editForm(id: Int) = service.editForm(id).data

    override suspend fun updatePost(id: Int, body: String) = service.updatePost(id, BodyInput(body)).message

    override suspend fun carrySource(id: Int) = service.carrySource(id).data

    override suspend fun composer(oasisSlug: String?, replyTo: Int?, carried: Int?) = service.composer(oasisSlug, replyTo, carried).data

    override suspend fun publish(draft: PostDraft): PublishResult {
        val fields = buildList {
            add("body" to draft.body)
            add("visibility" to draft.visibility)
            add("kind" to draft.kind)
            draft.oasisSlug?.let { add("oasis" to it) }
            draft.replyTo?.let { add("reply_to" to it.toString()) }
            draft.carried?.let { add("carried" to it.toString()) }
            add("link_read" to if (draft.linkRead) "1" else "0")
            add("tone_confirmed" to if (draft.toneConfirmed) "1" else "0")
            draft.alts.forEach { add("alts[]" to it) }
        }
        val parts = fields.map { (name, value) -> MultipartBody.Part.createFormData(name, value) } + draft.images.map { file("images[]", it) }
        return service.publish(parts)
    }

    override suspend fun deletePost(id: Int) = service.deletePost(id).message

    override suspend fun react(id: Int, on: Boolean) = (if (on) service.react(id) else service.unreact(id)).message

    override suspend fun bookmark(id: Int, on: Boolean) = (if (on) service.bookmark(id) else service.unbookmark(id)).message

    override suspend fun removeFromOasis(id: Int) = service.removeFromOasis(id).message

    override suspend fun block(userId: Int) = service.block(userId).message

    override suspend fun mute(userId: Int, on: Boolean) = (if (on) service.mute(userId) else service.unmute(userId)).message

    override suspend fun restrict(userId: Int, on: Boolean) = (if (on) service.restrict(userId) else service.unrestrict(userId)).message

    override suspend fun profile(handle: String) = service.profile(escape(handle)).data

    override suspend fun profilePosts(handle: String, cursor: String?) = service.profilePosts(escape(handle), cursor)

    override suspend fun follow(handle: String, on: Boolean) =
        (if (on) service.follow(escape(handle)) else service.unfollow(escape(handle))).message

    override suspend fun requestFriendship(handle: String) = service.requestFriendship(escape(handle)).message

    override suspend fun acceptFriendship(handle: String) = service.acceptFriendship(escape(handle)).message

    override suspend fun removeFriendship(handle: String) = service.removeFriendship(escape(handle)).message

    override suspend fun ownProfile() = service.ownProfile().data

    override suspend fun updateOwnProfile(bio: String, location: String, avatarColor: String, toneConfirmed: Boolean) =
        service.updateOwnProfile(ProfileInput(bio, location, avatarColor, toneConfirmed))

    override suspend fun uploadPhoto(image: ImageUpload) = service.uploadPhoto(file("photo", image))

    override suspend fun removePhoto() = service.removePhoto()

    override suspend fun uploadProfileBanner(image: ImageUpload) = service.uploadProfileBanner(file("banner", image))

    override suspend fun removeProfileBanner() = service.removeProfileBanner()

    override suspend fun friends() = service.friends().data

    override suspend fun acceptFriend(userId: Int) = service.acceptFriend(userId).message

    override suspend fun removeFriend(userId: Int) = service.removeFriend(userId).message

    override suspend fun oases() = service.oases().data

    override suspend fun oasis(slug: String) = service.oasis(escape(slug)).data

    override suspend fun oasisPosts(slug: String, cursor: String?) = service.oasisPosts(escape(slug), cursor)

    override suspend fun oasisMembers(slug: String, query: String) = service.oasisMembers(escape(slug), query.ifEmpty { null }).data

    override suspend fun followOasis(slug: String, on: Boolean) =
        (if (on) service.followOasis(escape(slug)) else service.unfollowOasis(escape(slug))).message

    override suspend fun createOasis(input: OasisInput, banner: ImageUpload?): String {
        val parts = input.fields.map { (name, value) -> MultipartBody.Part.createFormData(name, value) } +
            listOfNotNull(banner?.let { file("banner", it) })
        return service.createOasis(parts).data.slug
    }

    override suspend fun oasisEditForm(slug: String) = service.oasisEditForm(escape(slug)).data

    override suspend fun updateOasis(slug: String, input: OasisInput) = service.updateOasis(escape(slug), input).data.slug

    override suspend fun uploadBanner(slug: String, image: ImageUpload) = service.uploadBanner(escape(slug), file("banner", image)).message

    override suspend fun removeBanner(slug: String) = service.removeBanner(escape(slug)).message

    override suspend fun setRole(slug: String, userId: Int, role: String) = service.setRole(escape(slug), userId, RoleInput(role))

    override suspend fun oasisClosing(slug: String) = service.oasisClosing(escape(slug)).data

    override suspend fun closeOasis(slug: String, confirm: Boolean) = service.closeOasis(escape(slug), ConfirmInput(confirm)).message

    override suspend fun oasisStatistics(slug: String) = service.oasisStatistics(escape(slug)).data

    override suspend fun invitations() = service.invitations().data

    override suspend fun createInvitation() = service.createInvitation().message

    override suspend fun invitation(token: String) = service.invitation(escape(token)).data

    private fun file(field: String, image: ImageUpload): MultipartBody.Part =
        MultipartBody.Part.createFormData(field, image.filename, image.data.toRequestBody(image.mimeType.toMediaType()))

    companion object {
        /** Path segments from user input (handles, slugs, topics) stay one segment; Retrofit percent-encodes them. */
        fun escape(segment: String): String = segment.replace("/", "")
    }
}

