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
    val hasRestricted: Boolean = false
)

/** The own profile form ("Dein Profil"). */
@Serializable
data class OwnProfile(
    val member: Member,
    val bio: String = "",
    val location: String = "",
    val avatarColor: String = "sun",
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

/** "Menschen einladen". */
@Serializable
data class InvitationOverview(
    val invitations: List<Link> = emptyList(),
    val remaining: Int = 0,
    val perMonth: Int = 0,
    val validDays: Int = 0,
    val freeMonthsThisYear: Int = 0,
    val freeMonthsPerYear: Int = 0,
    val freeMonthsWaiting: Int = 0,
    val trialDays: Int = 0,
    val canCreate: Boolean = false
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
}

/** The page behind an invitation link: who invites, or null for unknown, used and expired links. */
@Serializable
data class InvitationLanding(val inviter: Member? = null)
