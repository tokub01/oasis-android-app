package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.network.api.CarrySource
import de.velwebsolutions.oasis.network.api.Choice
import de.velwebsolutions.oasis.network.api.ComposerContext
import de.velwebsolutions.oasis.network.api.DiscoverContext
import de.velwebsolutions.oasis.network.api.ExternalLink
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.FriendLists
import de.velwebsolutions.oasis.network.api.GroupInput
import de.velwebsolutions.oasis.network.api.GroupLanding
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.InvitationLanding
import de.velwebsolutions.oasis.network.api.InvitationOverview
import de.velwebsolutions.oasis.network.api.Member
import de.velwebsolutions.oasis.network.api.MemberStatistics
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisClosing
import de.velwebsolutions.oasis.network.api.OasisDetail
import de.velwebsolutions.oasis.network.api.OasisDirectory
import de.velwebsolutions.oasis.network.api.OasisEditForm
import de.velwebsolutions.oasis.network.api.OasisInput
import de.velwebsolutions.oasis.network.api.OasisMembers
import de.velwebsolutions.oasis.network.api.OasisOptions
import de.velwebsolutions.oasis.network.api.OasisSlug
import de.velwebsolutions.oasis.network.api.OasisStatistics
import de.velwebsolutions.oasis.network.api.OasisSummary
import de.velwebsolutions.oasis.network.api.OwnProfile
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.PostDetail
import de.velwebsolutions.oasis.network.api.PostEditForm
import de.velwebsolutions.oasis.network.api.PostRevision
import de.velwebsolutions.oasis.network.api.Profile
import de.velwebsolutions.oasis.network.api.PublishResult
import de.velwebsolutions.oasis.network.api.RoleChange
import de.velwebsolutions.oasis.network.api.SearchContext
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.api.SwipeDeck
import de.velwebsolutions.oasis.network.api.TagContext
import de.velwebsolutions.oasis.network.api.TextSegment
import java.time.Instant
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API models with the defaults the server may leave out: every model is built with its defaults, written and read
 * back, and compared like the screens compare state.
 */
class ModelsTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val member = Member(1, "mira", "Mira Sol", "MS")
    private val summary = OasisSummary(2, "garten", "Garten")

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T) {
        val json = OasisJson.snake.encodeToString(serializer, value)
        val read = OasisJson.snake.decodeFromString(serializer, json)
        assertEquals(value, read)
        assertEquals(value.hashCode(), read.hashCode())
        assertTrue(value.toString().isNotEmpty())
        assertNotEquals(value, Any())
    }

    @Test
    fun `the role list names how many members a host cannot see`() {
        assertEquals(null, OasisEditForm(summary).hiddenMembersNote)
        assertEquals(
            "Ein weiteres Mitglied zeigt sich hier nur Freund:innen und Menschen, denen es folgt, oder niemandem. " +
                "Eine Rolle kannst du nur Mitgliedern geben, die du hier siehst.",
            OasisEditForm(summary, hiddenMembers = 1).hiddenMembersNote
        )
        assertTrue(OasisEditForm(summary, hiddenMembers = 3).hiddenMembersNote!!.startsWith("3 weitere Mitglieder zeigen sich hier"))
    }

    @Test
    fun `Tropfen and their parts`() {
        val post = Post(1, "u", "b", createdAt = now, author = member)
        roundTrip(Member.serializer(), member)
        roundTrip(TextSegment.serializer(), TextSegment(text = "Hallo"))
        roundTrip(Post.serializer(), post)
        roundTrip(Post.OasisRef.serializer(), Post.OasisRef("garten", "Garten", "spring"))
        roundTrip(Post.ReplyRef.serializer(), Post.ReplyRef(1, "jonas"))
        roundTrip(Post.Image.serializer(), Post.Image(1, "u"))
        roundTrip(Post.Carried.serializer(), Post.Carried())
        roundTrip(Post.Carried.Original.serializer(), Post.Carried.Original(1, member, "b"))
        roundTrip(Post.Held.serializer(), Post.Held("Beleidigung"))
        roundTrip(Post.Stats.serializer(), Post.Stats())
        roundTrip(Post.Conversation.serializer(), Post.Conversation("Gespräch"))
        roundTrip(Post.Conversation.Reply.serializer(), Post.Conversation.Reply(1, member, "b"))
        roundTrip(Post.Viewer.serializer(), Post.Viewer())
        roundTrip(PostRevision.serializer(), PostRevision(1, "b", now))
        roundTrip(PostDetail.serializer(), PostDetail(post))
        roundTrip(PostEditForm.serializer(), PostEditForm("b", now))
        roundTrip(ExternalLink.serializer(), ExternalLink("https://example.org", "example.org"))
        roundTrip(CarrySource.serializer(), CarrySource(1, member, "b"))
        roundTrip(Choice.serializer(), Choice("public", "Öffentlich"))
        roundTrip(ComposerContext.serializer(), ComposerContext("public"))
        roundTrip(ComposerContext.OasisName.serializer(), ComposerContext.OasisName("garten", "Garten"))
        roundTrip(PublishResult.serializer(), PublishResult("Da.", data = post))
        assertFalse(post.isQuestion)
        assertFalse(post.isMembersOnly)
        assertEquals(1.5f, Post.Image(1, "u", width = 3, height = 2).aspectRatio)
    }

    @Test
    fun `lists, contexts and the swipe deck`() {
        roundTrip(FeedPage.serializer(NoContext.serializer()), FeedPage<NoContext>())
        assertNull(FeedPage<NoContext>().nextCursor)
        roundTrip(NoContext.serializer(), NoContext())
        assertEquals(NoContext().hashCode(), NoContext().hashCode())
        roundTrip(OasisSummary.serializer(), summary)
        roundTrip(SourceContext.serializer(), SourceContext())
        roundTrip(SourceContext.Week.serializer(), SourceContext.Week())
        roundTrip(DiscoverContext.serializer(), DiscoverContext())
        roundTrip(SearchContext.serializer(), SearchContext())
        roundTrip(TagContext.serializer(), TagContext())
        roundTrip(SwipeDeck.serializer(), SwipeDeck())
        roundTrip(SwipeDeck.Chapter.serializer(), SwipeDeck.Chapter("freunde", "Freunde"))
        roundTrip(MemberStatistics.serializer(), MemberStatistics(MemberStatistics.Totals()))
        roundTrip(MemberStatistics.Week.serializer(), MemberStatistics.Week(now))
        roundTrip(MemberStatistics.TopPost.serializer(), MemberStatistics.TopPost(1, "b", createdAt = now))
    }

    @Test
    fun `oases`() {
        roundTrip(OasisOptions.serializer(), OasisOptions())
        roundTrip(OasisDirectory.serializer(), OasisDirectory())
        roundTrip(OasisDetail.serializer(), OasisDetail(summary, "u"))
        roundTrip(OasisMembers.serializer(), OasisMembers())
        roundTrip(OasisMembers.Entry.serializer(), OasisMembers.Entry(1, "mira", "Mira Sol", "MS"))
        roundTrip(OasisEditForm.serializer(), OasisEditForm(summary))
        roundTrip(OasisEditForm.MemberRole.serializer(), OasisEditForm.MemberRole(1, "mira", "Mira Sol", "host", "Gastgeber:in"))
        roundTrip(OasisInput.serializer(), OasisInput())
        roundTrip(OasisClosing.serializer(), OasisClosing("Garten"))
        roundTrip(OasisStatistics.serializer(), OasisStatistics("Garten", OasisStatistics.Totals()))
        roundTrip(OasisStatistics.Week.serializer(), OasisStatistics.Week(now))
        roundTrip(OasisSlug.serializer(), OasisSlug("garten"))
        roundTrip(RoleChange.serializer(), RoleChange())
    }

    @Test
    fun `a held oasis tells only those who read its real texts`() {
        val lenient = OasisJson.snake.decodeFromString(OasisSummary.serializer(), """{"id":2,"slug":"garten","name":"Garten"}""")
        assertEquals("servers before the oasis check", false, lenient.held)
        assertFalse(lenient.showsHeldTexts)
        val held = OasisJson.snake.decodeFromString(OasisSummary.serializer(), """{"id":2,"slug":"garten","name":"Garten","held":true}""")
        assertTrue(held.held)
        assertTrue("the founder, the hosts and staff read the real name", held.showsHeldTexts)
        assertFalse("everyone else got the placeholder", held.copy(name = OasisSummary.HELD_NAME).showsHeldTexts)
    }

    @Test
    fun `profiles, friendships and invitations`() {
        roundTrip(Profile.serializer(), Profile(member, "u", joinedAt = now))
        roundTrip(OwnProfile.serializer(), OwnProfile(member))
        roundTrip(FriendLists.serializer(), FriendLists())
        roundTrip(InvitationOverview.serializer(), InvitationOverview())
        roundTrip(InvitationOverview.Link.serializer(), InvitationOverview.Link(1, expiresAt = now))
        roundTrip(InvitationOverview.Link.Reward.serializer(), InvitationOverview.Link.Reward())
        roundTrip(InvitationLanding.serializer(), InvitationLanding())
        val group = InvitationOverview.Group(1, "WG", target = 3, joined = 1, progress = "1 von 3 haben angenommen", expiresAt = now)
        roundTrip(InvitationOverview.Group.serializer(), group)
        roundTrip(GroupInput.serializer(), GroupInput("WG", 3))
        roundTrip(GroupLanding.serializer(), GroupLanding())
        roundTrip(GroupLanding.Group.serializer(), GroupLanding.Group(member, 1, "WG", progress = "1 dabei", expiresAt = now))
        assertFalse("an open group is not complete", group.isComplete)
        assertFalse("a closed group below its target expired", group.copy(open = false).isComplete)
        assertTrue("a closed group at its target is complete", group.copy(open = false, joined = 3).isComplete)
        assertFalse("a closed group without a target expired", group.copy(open = false, target = null).isComplete)
    }

    @Test
    fun `an image upload compares its bytes, name and type`() {
        val image = ImageUpload(byteArrayOf(1, 2), "a.jpg", "image/jpeg")
        assertEquals(image, ImageUpload(byteArrayOf(1, 2), "a.jpg", "image/jpeg"))
        assertEquals(image.hashCode(), ImageUpload(byteArrayOf(1, 2), "a.jpg", "image/jpeg").hashCode())
        assertNotEquals(image, ImageUpload(byteArrayOf(1, 2), "b.jpg", "image/jpeg"))
        assertNotEquals(image, ImageUpload(byteArrayOf(1, 2), "a.jpg", "image/png"))
        assertNotEquals(image, ImageUpload(byteArrayOf(3), "a.jpg", "image/jpeg"))
        assertNotEquals(image, Any())
    }
}
