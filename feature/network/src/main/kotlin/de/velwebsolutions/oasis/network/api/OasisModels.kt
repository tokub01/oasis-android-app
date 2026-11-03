package de.velwebsolutions.oasis.network.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The form choices for founding and editing an oasis. */
@Serializable
data class OasisOptions(
    val visibilities: List<Choice> = emptyList(),
    val colors: List<Choice> = emptyList(),
    /** The Bundesländer for „Region“ (none from a server without the Karte: the field is not shown). */
    val regions: List<Choice> = emptyList()
)

/** "Oasen": own oases and all others. */
@Serializable
data class OasisDirectory(
    val mine: List<OasisSummary> = emptyList(),
    val others: List<OasisSummary> = emptyList(),
    val canCreate: Boolean = false,
    val options: OasisOptions = OasisOptions()
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
    /** „Mitglieder dürfen Treffen anlegen“. */
    val membersCreateMeetups: Boolean = false,
    /** Willkommenspaten (null for non-members). */
    val welcome: OasisWelcome? = null
)

/** The member list of an oasis, for its members. */
@Serializable
data class OasisMembers(
    val members: List<Entry> = emptyList(),
    val hiddenMembers: Int = 0,
    /** The viewer's own list audience when it limits them: "contacts" or "nobody". */
    val ownListAudience: String? = null
) {
    @Serializable
    data class Entry(
        val id: Int,
        val username: String,
        val displayName: String,
        val initials: String,
        val avatarColor: String = "sun",
        val photoUrl: String? = null,
        val canWrite: Boolean = false
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
    /** How many other members chose not to appear to this host (`hidden_members`, OasisMemberList::hiddenFromHost). */
    val hiddenMembers: Int = 0
) {
    /** The note under the role list while members are hidden (oasis-form.blade.php); null when none is. */
    val hiddenMembersNote: String?
        get() = when (hiddenMembers) {
            0 -> null

            1 -> "Ein weiteres Mitglied zeigt sich hier nur Freund:innen und Menschen, denen es folgt, oder niemandem. $ROLE_ONLY_VISIBLE"

            else ->
                "$hiddenMembers weitere Mitglieder zeigen sich hier nur Freund:innen und Menschen, denen sie folgen, oder niemandem. " +
                    ROLE_ONLY_VISIBLE
        }

    @Serializable
    data class MemberRole(val id: Int, val username: String, val displayName: String, val role: String, val roleLabel: String)
}

/** The end of [OasisEditForm.hiddenMembersNote]. */
private const val ROLE_ONLY_VISIBLE = "Eine Rolle kannst du nur Mitgliedern geben, die du hier siehst."

/** The input of the oasis form. */
@Serializable
data class OasisInput(
    val name: String = "",
    val description: String = "",
    val houseRules: String = "",
    val visibility: String = "public",
    val color: String = "spring",
    /** The Bundesland code (`BY`), null for none. */
    val region: String? = null,
    /** A city or area (at most 60), only with a region. */
    val city: String = ""
) {
    /** The multipart fields when founding (with the optional banner); region and city only with a region. */
    val fields: List<Pair<String, String>>
        get() = listOf(
            "name" to name,
            "description" to description,
            "house_rules" to houseRules,
            "visibility" to visibility,
            "color" to color
        ) + region?.let { listOf("region" to it, "city" to city) }.orEmpty()

    /** What saving an edited oasis sends: `region` also as null, since leaving it out would keep the old one. */
    internal val update: OasisUpdate
        get() = OasisUpdate(
            name,
            description,
            houseRules,
            visibility,
            color,
            region?.let(::JsonPrimitive) ?: JsonNull,
            region?.let {
                city
            }.orEmpty()
        )
}

/** The body of `PUT oases/{slug}`. */
@Serializable
internal data class OasisUpdate(
    val name: String,
    val description: String,
    val houseRules: String,
    val visibility: String,
    val color: String,
    val region: JsonElement,
    val city: String
)

/** One tile of the Karte der Oasen (`GET network/regions`): the Bundesland, its visible oases and its grid cell. */
@Serializable
data class MapRegion(val code: String, val name: String, val oasesCount: Int = 0, val tile: Tile) {
    @Serializable
    data class Tile(val x: Int, val y: Int)

    /** „Bayern: 3 Oasen“. */
    val accessibleName: String get() = "$name: ${if (oasesCount == 1) "1 Oase" else "$oasesCount Oasen"}"
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
        val removed: Int = 0
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
