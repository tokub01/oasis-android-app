package de.velwebsolutions.oasis.network.data

import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.network.api.BodyInput
import de.velwebsolutions.oasis.network.api.CapsuleInvitation
import de.velwebsolutions.oasis.network.api.CarrySource
import de.velwebsolutions.oasis.network.api.CollectPostBody
import de.velwebsolutions.oasis.network.api.CollectionInput
import de.velwebsolutions.oasis.network.api.CollectionSettings
import de.velwebsolutions.oasis.network.api.ComposerContext
import de.velwebsolutions.oasis.network.api.ConfirmInput
import de.velwebsolutions.oasis.network.api.ContributionBody
import de.velwebsolutions.oasis.network.api.DiscoverContext
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.FriendLists
import de.velwebsolutions.oasis.network.api.GroupInput
import de.velwebsolutions.oasis.network.api.GroupLanding
import de.velwebsolutions.oasis.network.api.Idea
import de.velwebsolutions.oasis.network.api.IdeaInput
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.InvitationLanding
import de.velwebsolutions.oasis.network.api.InvitationOverview
import de.velwebsolutions.oasis.network.api.MapRegion
import de.velwebsolutions.oasis.network.api.Meetup
import de.velwebsolutions.oasis.network.api.MeetupAnswerBody
import de.velwebsolutions.oasis.network.api.MeetupInput
import de.velwebsolutions.oasis.network.api.MeetupSettings
import de.velwebsolutions.oasis.network.api.Member
import de.velwebsolutions.oasis.network.api.MemberStatistics
import de.velwebsolutions.oasis.network.api.MemoryYear
import de.velwebsolutions.oasis.network.api.NetworkService
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisClosing
import de.velwebsolutions.oasis.network.api.OasisCollection
import de.velwebsolutions.oasis.network.api.OasisCollections
import de.velwebsolutions.oasis.network.api.OasisDetail
import de.velwebsolutions.oasis.network.api.OasisDirectory
import de.velwebsolutions.oasis.network.api.OasisEditForm
import de.velwebsolutions.oasis.network.api.OasisInput
import de.velwebsolutions.oasis.network.api.OasisMeetups
import de.velwebsolutions.oasis.network.api.OasisMembers
import de.velwebsolutions.oasis.network.api.OasisStatistics
import de.velwebsolutions.oasis.network.api.OasisSummary
import de.velwebsolutions.oasis.network.api.OwnProfile
import de.velwebsolutions.oasis.network.api.PollVoteBody
import de.velwebsolutions.oasis.network.api.Post
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
import de.velwebsolutions.oasis.network.api.TimeCapsuleInput
import de.velwebsolutions.oasis.network.api.TimeCapsules
import de.velwebsolutions.oasis.network.api.WeeklyLetter
import de.velwebsolutions.oasis.network.api.WeeklyLetterSummary
import de.velwebsolutions.oasis.network.api.WelcomeVolunteer
import de.velwebsolutions.oasis.network.api.Yearbook
import javax.inject.Inject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** The network area's calls (NetworkAPI on iOS): feeds, Posts, profiles, friends, crowds and invitations. */
interface NetworkRepository {
    // Feeds
    suspend fun source(view: String?, cursor: String?): FeedPage<SourceContext>

    suspend fun discover(cursor: String?): FeedPage<DiscoverContext>

    /** „Heute vor einem Jahr“: the member's own Posts of this calendar day in earlier years (empty when dismissed today). */
    suspend fun memories(): List<MemoryYear>

    /** „Ausblenden“ until the end of the day on every device; „Bis morgen ausgeblendet.“ */
    suspend fun dismissMemories(): String

    suspend fun swipe(): SwipeDeck

    suspend fun bookmarks(cursor: String?): FeedPage<NoContext>

    suspend fun statistics(): MemberStatistics

    // Zeitkapseln and Jahrbuch
    suspend fun timeCapsules(): TimeCapsules

    /** Seals a Zeitkapsel; returns the server's confirmation. */
    suspend fun sealTimeCapsule(input: TimeCapsuleInput): String

    /** Opens a waiting Zeitkapsel now; the confirmation says whether it opened or why not. */
    suspend fun openTimeCapsule(id: Int): String

    suspend fun deleteTimeCapsule(id: Int): String

    /** The Jahrbuch of [year], the current year when null. */
    suspend fun yearbook(year: Int?): Yearbook

    /** The Bundesländer of the Karte der Crowds with the number of crowds the viewer can see. */
    suspend fun regions(): List<MapRegion>

    /** The crowds of Bundesland [code] (404 for an unknown one). */
    suspend fun regionOases(code: String): List<OasisSummary>

    /** The last 12 Wochenbriefe, newest first. */
    suspend fun weeklyLetters(): List<WeeklyLetterSummary>

    /** Wochenbrief [id], or the newest without one (null before the first). */
    suspend fun weeklyLetter(id: Int?): WeeklyLetter?

    /** A page of „Ideen für Mildcrowd“ in [sort] order, of one [status] or all. */
    suspend fun ideas(sort: String, status: String?, page: Int): Page<Idea>

    suspend fun idea(id: Int): Idea

    suspend fun suggestIdea(input: IdeaInput): Confirmed<Idea>

    suspend fun deleteIdea(id: Int): String

    /** Gives ([on]) or takes back the own vote. */
    suspend fun voteIdea(id: Int, on: Boolean): Confirmed<Idea>

    suspend fun search(query: String, cursor: String?): FeedPage<SearchContext>

    suspend fun tag(tag: String, cursor: String?): FeedPage<TagContext>

    // Posts
    suspend fun post(id: Int): PostDetail

    suspend fun editForm(id: Int): PostEditForm

    suspend fun updatePost(id: Int, body: String): String

    suspend fun carrySource(id: Int): CarrySource

    suspend fun composer(oasisSlug: String?, replyTo: Int?, carried: Int?): ComposerContext

    /** Publishes a Post as multipart (`images[]`, `alts[]` in the same order). */
    suspend fun publish(draft: PostDraft): PublishResult

    // Sammlungen (docs/mildcrowd-network.md)

    suspend fun collections(slug: String): OasisCollections

    suspend fun createCollection(slug: String, input: CollectionInput): Confirmed<OasisCollection>

    suspend fun updateCollection(id: Int, input: CollectionInput): Confirmed<OasisCollection>

    /** „Sammlung löschen“: its Posts stay in the crowd. */
    suspend fun deleteCollection(id: Int): String

    /** A page of 20 Posts of a collection, newest added first. */
    suspend fun collectionPosts(id: Int, page: Int): Page<Post>

    /** „Zu Sammlung hinzufügen“. */
    suspend fun collectPost(id: Int, postId: Int): Confirmed<OasisCollection>

    /** „Aus „X“ entfernen“. */
    suspend fun uncollectPost(id: Int, postId: Int): Confirmed<OasisCollection>

    /** „Mitglieder dürfen Sammlungen anlegen“ (hosts). */
    suspend fun setMembersCreateCollections(slug: String, allowed: Boolean): CollectionSettings

    // Gemeinsame Zeitkapseln

    /** Who may be invited: friends, and the members of [oasisId] for a capsule that opens there. */
    suspend fun capsuleInvitees(oasisId: Int?): List<Member>

    /** Write or change the own text of an invitation (until the deadline). */
    suspend fun contribute(id: Int, body: String): Confirmed<CapsuleInvitation>

    suspend fun removeContribution(id: Int): Confirmed<CapsuleInvitation>

    /** „Ablehnen“: final, the own text goes. */
    suspend fun declineInvitation(id: Int): Confirmed<CapsuleInvitation>

    // Treffen and Willkommenspaten (docs/mildcrowd-network.md)

    suspend fun meetups(slug: String): OasisMeetups

    /** A new meetup → „Das Treffen ist angelegt.“ (the server's words) and the meetup. */
    suspend fun createMeetup(slug: String, input: MeetupInput): Confirmed<Meetup>

    suspend fun updateMeetup(id: Int, input: MeetupInput): Confirmed<Meetup>

    /** „Treffen absagen“. */
    suspend fun cancelMeetup(id: Int): Confirmed<Meetup>

    /** „Ich komme“ (`yes`), „Vielleicht“ (`maybe`), „Nein“ (`no`) → „Deine Antwort: …“. */
    suspend fun answerMeetup(id: Int, answer: String): Confirmed<Meetup>

    /** „Mitglieder dürfen Treffen anlegen“ (hosts); returns the setting as stored. */
    suspend fun setMembersCreateMeetups(slug: String, allowed: Boolean): MeetupSettings

    /** „Nächste Treffen“ on the Start: the own oases' meetups within 30 days. */
    suspend fun upcomingMeetups(): List<Meetup>

    /** „Ich bin gern Willkommenspate oder -patin“; returns the setting as stored. */
    suspend fun setWelcomeVolunteer(slug: String, volunteer: Boolean): WelcomeVolunteer

    /** A vote in a Post's poll ([options] empty takes it back) → „Abgestimmt.“ / „Stimme zurückgenommen.“ and the Post. */
    suspend fun votePoll(id: Int, options: List<Int>): Confirmed<Post>

    /** „Umfrage beenden“ (the author) → „Die Umfrage ist beendet.“ and the Post. */
    suspend fun closePoll(id: Int): Confirmed<Post>

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

    /** Saves bio, location, colour and the status line ("" clears it). */
    suspend fun updateOwnProfile(
        bio: String,
        location: String,
        avatarColor: String,
        toneConfirmed: Boolean,
        status: String = ""
    ): Confirmed<OwnProfile>

    suspend fun uploadPhoto(image: ImageUpload): Confirmed<OwnProfile>

    suspend fun removePhoto(): Confirmed<OwnProfile>

    suspend fun uploadProfileBanner(image: ImageUpload): Confirmed<OwnProfile>

    suspend fun removeProfileBanner(): Confirmed<OwnProfile>

    suspend fun friends(): FriendLists

    suspend fun acceptFriend(userId: Int): String

    suspend fun removeFriend(userId: Int): String

    // Crowds
    suspend fun oases(): OasisDirectory

    suspend fun oasis(slug: String): OasisDetail

    suspend fun oasisPosts(slug: String, cursor: String?): FeedPage<NoContext>

    suspend fun oasisMembers(slug: String, query: String): OasisMembers

    suspend fun followOasis(slug: String, on: Boolean): String

    /** Founds a crowd (multipart with the optional banner) and returns its slug. */
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

    /** „Gemeinsam starten“: found a group → the server's confirmation and the group with its link. */
    suspend fun createGroup(name: String, target: Int?): Confirmed<InvitationOverview.Group>

    /** The group behind a shared link (/gemeinsam/{token}), null when it no longer takes anyone. */
    suspend fun invitationGroup(token: String): GroupLanding
}

/** [NetworkRepository] over Retrofit. */
class DefaultNetworkRepository @Inject constructor(client: ApiClient) : NetworkRepository {
    private val service = client.create<NetworkService>()

    override suspend fun source(view: String?, cursor: String?) = service.source(view, cursor)

    override suspend fun discover(cursor: String?) = service.discover(cursor)

    override suspend fun memories() = service.memories().data

    override suspend fun dismissMemories() = service.dismissMemories().message

    override suspend fun swipe() = service.swipe().data

    override suspend fun bookmarks(cursor: String?) = service.bookmarks(cursor)

    override suspend fun statistics() = service.statistics().data

    override suspend fun timeCapsules() = service.timeCapsules().data

    override suspend fun sealTimeCapsule(input: TimeCapsuleInput) = service.sealTimeCapsule(input).message

    override suspend fun openTimeCapsule(id: Int) = service.openTimeCapsule(id).message

    override suspend fun deleteTimeCapsule(id: Int) = service.deleteTimeCapsule(id).message

    override suspend fun yearbook(year: Int?) = (if (year == null) service.yearbook() else service.yearbook(year)).data

    override suspend fun regions() = service.regions().data

    override suspend fun regionOases(code: String) = service.regionOases(escape(code)).data

    override suspend fun weeklyLetters() = service.weeklyLetters().data

    override suspend fun weeklyLetter(id: Int?) = if (id == null) service.latestWeeklyLetter().data else service.weeklyLetter(id).data

    override suspend fun ideas(sort: String, status: String?, page: Int) = service.ideas(sort, status, page)

    override suspend fun idea(id: Int) = service.idea(id).data

    override suspend fun suggestIdea(input: IdeaInput) = service.suggestIdea(input)

    override suspend fun deleteIdea(id: Int) = service.deleteIdea(id).message

    override suspend fun voteIdea(id: Int, on: Boolean) = if (on) service.voteIdea(id) else service.unvoteIdea(id)

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
            draft.poll?.let { poll ->
                add("poll[question]" to poll.question)
                poll.options.forEach { add("poll[options][]" to it) }
                add("poll[multiple]" to if (poll.multiple) "1" else "0")
                poll.duration?.let { add("poll[duration]" to it) }
            }
        }
        val parts =
            fields.map { (name, value) -> MultipartBody.Part.createFormData(name, value) } + draft.images.map { file("images[]", it) }
        return service.publish(parts)
    }

    override suspend fun collections(slug: String) = service.collections(slug).data

    override suspend fun createCollection(slug: String, input: CollectionInput) = service.createCollection(slug, input)

    override suspend fun updateCollection(id: Int, input: CollectionInput) = service.updateCollection(id, input)

    override suspend fun deleteCollection(id: Int) = service.deleteCollection(id).message

    override suspend fun collectionPosts(id: Int, page: Int) = service.collectionPosts(id, page)

    override suspend fun collectPost(id: Int, postId: Int) = service.collectPost(id, CollectPostBody(postId))

    override suspend fun uncollectPost(id: Int, postId: Int) = service.uncollectPost(id, postId)

    override suspend fun setMembersCreateCollections(slug: String, allowed: Boolean) =
        service.collectionSettings(slug, CollectionSettings(allowed)).data

    override suspend fun capsuleInvitees(oasisId: Int?) = service.capsuleInvitees(oasisId).data

    override suspend fun contribute(id: Int, body: String) = service.contribute(id, ContributionBody(body))

    override suspend fun removeContribution(id: Int) = service.removeContribution(id)

    override suspend fun declineInvitation(id: Int) = service.declineInvitation(id)

    override suspend fun meetups(slug: String) = service.meetups(slug).data

    override suspend fun createMeetup(slug: String, input: MeetupInput) = service.createMeetup(slug, input)

    override suspend fun updateMeetup(id: Int, input: MeetupInput) = service.updateMeetup(id, input)

    override suspend fun cancelMeetup(id: Int) = service.cancelMeetup(id)

    override suspend fun answerMeetup(id: Int, answer: String) = service.answerMeetup(id, MeetupAnswerBody(answer))

    override suspend fun setMembersCreateMeetups(slug: String, allowed: Boolean) =
        service.meetupSettings(slug, MeetupSettings(allowed)).data

    override suspend fun upcomingMeetups() = service.upcomingMeetups().data

    override suspend fun setWelcomeVolunteer(slug: String, volunteer: Boolean) =
        service.welcomeVolunteer(slug, WelcomeVolunteer(volunteer)).data

    override suspend fun votePoll(id: Int, options: List<Int>) = service.votePoll(id, PollVoteBody(options))

    override suspend fun closePoll(id: Int) = service.closePoll(id)

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

    override suspend fun updateOwnProfile(bio: String, location: String, avatarColor: String, toneConfirmed: Boolean, status: String) =
        service.updateOwnProfile(ProfileInput(bio, location, avatarColor, toneConfirmed, status))

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

    override suspend fun updateOasis(slug: String, input: OasisInput) = service.updateOasis(escape(slug), input.update).data.slug

    override suspend fun uploadBanner(slug: String, image: ImageUpload) = service.uploadBanner(escape(slug), file("banner", image)).message

    override suspend fun removeBanner(slug: String) = service.removeBanner(escape(slug)).message

    override suspend fun setRole(slug: String, userId: Int, role: String) = service.setRole(escape(slug), userId, RoleInput(role))

    override suspend fun oasisClosing(slug: String) = service.oasisClosing(escape(slug)).data

    override suspend fun closeOasis(slug: String, confirm: Boolean) = service.closeOasis(escape(slug), ConfirmInput(confirm)).message

    override suspend fun oasisStatistics(slug: String) = service.oasisStatistics(escape(slug)).data

    override suspend fun invitations() = service.invitations().data

    override suspend fun createInvitation() = service.createInvitation().message

    override suspend fun invitation(token: String) = service.invitation(escape(token)).data

    override suspend fun createGroup(name: String, target: Int?) = service.createGroup(GroupInput(name, target))

    override suspend fun invitationGroup(token: String) = service.invitationGroup(escape(token)).data

    private fun file(field: String, image: ImageUpload): MultipartBody.Part =
        MultipartBody.Part.createFormData(field, image.filename, image.data.toRequestBody(image.mimeType.toMediaType()))

    companion object {
        /** Path segments from user input (handles, slugs, topics) stay one segment; Retrofit percent-encodes them. */
        fun escape(segment: String): String = segment.replace("/", "")
    }
}
