package de.velwebsolutions.oasis.network.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import kotlinx.serialization.Serializable

/** A member profile as the viewer may see it (`/profil/{handle}`). */
@Serializable
data class Profile(
    val member: Member,
    val url: String,
    /**
     * The 3:1 banner, null when the profile shows the strip in the avatar colour (none, or images of this member are not
     * shown to the viewer).
     */
    val bannerUrl: String? = null,
    val joinedAt: OasisInstant,
    val bio: String? = null,
    val location: String? = null,
    /** The short status line under the name (max. 80 characters); null when empty, held or hidden from the viewer. */
    val status: String? = null,
    val bioHeld: Boolean = false,
    /** The Karawane size, null when its audience excludes the viewer. */
    val followers: Int? = null,
    /** Null when the oases audience excludes the viewer. */
    val oases: List<OasisSummary>? = null,
    val isMe: Boolean = false,
    /** The own profile audience label, only on the own profile. */
    val profileAudience: String? = null,
    val following: Boolean = false,
    val canFollow: Boolean = false,
    /** "friends", "incoming", "outgoing" or null. */
    val friendship: String? = null,
    val acceptsFriendRequests: Boolean = false,
    val canWrite: Boolean = false,
    /**
     * "… nimmt gerade nur Nachrichten von bestimmten Personen an." when the member's chat conditions keep the viewer
     * out: neutral on purpose, it never says which condition applied. Null when the viewer may write or has no chat.
     */
    val chatHint: String? = null,
    val hasMuted: Boolean = false,
    val hasRestricted: Boolean = false,
    /** A Mäzen who switched on the supporter mark ("Trägt Mildcrowd mit"); servers before the tiers leave it out. */
    val supporter: Boolean = false,
    /** A founding member who switched on the founding mark („Gründungsmitglied“, privacy setting `founding_badge`). */
    val foundingBadge: Boolean = false
)

/** The own profile form ("Dein Profil"). */
@Serializable
data class OwnProfile(
    val member: Member,
    val bio: String = "",
    val location: String = "",
    /** The status line under the name, "" when empty. */
    val status: String = "",
    val avatarColor: String = "sun",
    /** The chosen colour of the initials; null: „Automatisch“ ([avatarColor] is then the resolved one). */
    val avatarColorChoice: String? = null,
    val hasPhoto: Boolean = false,
    val bannerUrl: String? = null,
    val bioHeld: Boolean = false,
    val colors: List<Choice> = emptyList()
)

/** "Freund:innen": the three lists. */
@Serializable
data class FriendLists(
    val incoming: List<Member> = emptyList(),
    val friends: List<Member> = emptyList(),
    val outgoing: List<Member> = emptyList(),
    val acceptsRequests: Boolean = true
)

/** "Menschen einladen": the personal links and the group starts („Gemeinsam starten“). */
@Serializable
data class InvitationOverview(
    val invitations: List<Link> = emptyList(),
    val remaining: Int = 0,
    val perMonth: Int = 0,
    val validDays: Int = 0,
    val freeMonthsThisYear: Int = 0,
    val freeMonthsPerYear: Int = 0,
    val freeMonthsWaiting: Int = 0,
    val canCreate: Boolean = false,
    /** The own groups, newest first; servers before „Gemeinsam starten“ leave them out. */
    val groups: List<Group> = emptyList(),
    /** Participation and fewer than the allowed number of open groups. */
    val canCreateGroup: Boolean = false,
    val groupValidDays: Int = 90,
    val groupMaxTarget: Int = 100
) {
    @Serializable
    data class Link(
        val id: Int,
        val url: String? = null,
        val expiresAt: OasisInstant,
        val expired: Boolean = false,
        val acceptedAt: OasisInstant? = null,
        val reward: Reward? = null
    ) {
        @Serializable
        data class Reward(val appliedAt: OasisInstant? = null)
    }

    /**
     * One group start as its founder sees it: the shared link ([url], null once the link is closed), the progress
     * („3 von 12 haben angenommen“ or „3 dabei“) and the user names of those who joined.
     */
    @Serializable
    data class Group(
        val id: Int,
        val name: String,
        val target: Int? = null,
        val joined: Int = 0,
        val progress: String,
        val open: Boolean = true,
        val expiresAt: OasisInstant,
        val token: String? = null,
        val url: String? = null,
        val members: List<String> = emptyList()
    ) {
        /** A closed group that reached its target (the other way to close is the expiry). */
        val isComplete: Boolean get() = !open && target != null && joined >= target
    }
}

/** What founding a group sends: the name (2–60 characters) and the optional target size. */
@Serializable
data class GroupInput(val name: String, val target: Int? = null)

/** The page behind a group link (/gemeinsam/{token}): the group, or null for unknown, expired and full links. */
@Serializable
data class GroupLanding(val group: Group? = null) {
    /** What a visitor may see: the founder, the name and the progress – never the link or the members. */
    @Serializable
    data class Group(
        val founder: Member,
        val id: Int,
        val name: String,
        val target: Int? = null,
        val joined: Int = 0,
        val progress: String,
        val open: Boolean = true,
        val expiresAt: OasisInstant
    )
}

/** The page behind an invitation link: who invites, or null for unknown, used and expired links. */
@Serializable
data class InvitationLanding(val inviter: Member? = null)
