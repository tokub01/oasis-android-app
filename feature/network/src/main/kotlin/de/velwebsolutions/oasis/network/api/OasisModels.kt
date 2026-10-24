package de.velwebsolutions.oasis.network.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The form choices for founding and editing an oasis. */
@Serializable
data class OasisOptions(val visibilities: List<Choice> = emptyList(), val colors: List<Choice> = emptyList())

/** "Oasen": own oases and all others. */
@Serializable
data class OasisDirectory(
    val mine: List<OasisSummary> = emptyList(),
    val others: List<OasisSummary> = emptyList(),
    val canCreate: Boolean = false,
    val options: OasisOptions = OasisOptions(),
)

/** An oasis page without its Tropfen. */
@Serializable
data class OasisDetail(
    val oasis: OasisSummary,
    val url: String,
    val houseRules: List<String> = emptyList(),
    val hosts: List<Member> = emptyList(),
    val isMember: Boolean = false,
    val isHost: Boolean = false,
    val canFollow: Boolean = false,
    val canPost: Boolean = false,
)

/** The member list of an oasis, for its members. */
@Serializable
data class OasisMembers(
    val members: List<Entry> = emptyList(),
    val hiddenMembers: Int = 0,
    /** The viewer's own list audience when it limits them: "contacts" or "nobody". */
    val ownListAudience: String? = null,
) {
    @Serializable
    data class Entry(
        val id: Int,
        val username: String,
        val displayName: String,
        val initials: String,
        val avatarColor: String = "sun",
        val photoUrl: String? = null,
        val canWrite: Boolean = false,
    ) {
        val member: Member get() = Member(id, username, displayName, initials, avatarColor, photoUrl)
    }
}

/** The edit form of an oasis for hosts. */
@Serializable
data class OasisEditForm(
    val oasis: OasisSummary,
    val houseRules: String = "",
    val members: List<MemberRole> = emptyList(),
    val options: OasisOptions = OasisOptions(),
) {
    @Serializable
    data class MemberRole(val id: Int, val username: String, val displayName: String, val role: String, val roleLabel: String)
}

/** The input of the oasis form. */
@Serializable
data class OasisInput(
    val name: String = "",
    val description: String = "",
    val houseRules: String = "",
    val visibility: String = "public",
    val color: String = "spring",
) {
    /** The multipart fields when founding (with the optional banner). */
    val fields: List<Pair<String, String>>
        get() = listOf("name" to name, "description" to description, "house_rules" to houseRules, "visibility" to visibility, "color" to color)
}

/** The confirmation page before closing an oasis. */
@Serializable
data class OasisClosing(val name: String, val membersCount: Int = 0, val postsCount: Int = 0)

/** Host statistics of an oasis. */
@Serializable
data class OasisStatistics(val name: String, val totals: Totals, val weekly: List<Week> = emptyList()) {
    @Serializable
    data class Totals(
        val members: Int = 0,
        val hosts: Int = 0,
        val posts: Int = 0,
        @SerialName("posts_30_days") val posts30Days: Int = 0,
        @SerialName("active_authors_30_days") val activeAuthors30Days: Int = 0,
        val removed: Int = 0,
    )

    @Serializable
    data class Week(val week: OasisInstant, val joined: Int = 0, val posts: Int = 0)
}

/** The slug of a founded or saved oasis. */
@Serializable
data class OasisSlug(val slug: String)

/** After changing a role: whether the acting member is still a host (the last change may have been their own). */
@Serializable
data class RoleChange(val stillHost: Boolean = true)
