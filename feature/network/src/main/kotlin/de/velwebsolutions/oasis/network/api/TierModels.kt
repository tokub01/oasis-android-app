package de.velwebsolutions.oasis.network.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import kotlinx.serialization.Serializable

/**
 * Zeitkapseln (GET /api/v1/network/time-capsules, from Mittendrin): the waiting capsules (soonest first), the last opened or
 * failed ones and the limits. Below Mittendrin [available] is false and [lockedMessage] names the tier that offers them.
 */
@Serializable
data class TimeCapsules(
    val available: Boolean = false,
    val lockedMessage: String? = null,
    val maxLength: Int = 500,
    val maxWaiting: Int = 50,
    val maxYears: Int = 10,
    /** The crowds the member belongs to, where a capsule may appear instead of the own Start. */
    val oases: List<Oasis> = emptyList(),
    val waiting: List<TimeCapsule> = emptyList(),
    val past: List<TimeCapsule> = emptyList(),
    /** Invitations to others' gemeinsame Zeitkapseln (also below Mittendrin). */
    val invitations: List<CapsuleInvitation> = emptyList()
) {
    @Serializable
    data class Oasis(val id: Int, val slug: String, val name: String)
}

/** One Zeitkapsel: sealed until [opensAt], then a Post ([postId]) or not opened for [failedReason]. */
@Serializable
data class TimeCapsule(
    val id: Int,
    val body: String,
    val opensAt: OasisInstant,
    val oasis: NamedOasis? = null,
    val openedAt: OasisInstant? = null,
    val postId: Int? = null,
    val failedReason: String? = null,
    /** A gemeinsame Zeitkapsel: the deadline and where each invitee stands (the texts stay sealed). */
    val shared: Shared? = null
) {
    @Serializable
    data class Shared(val contributeUntil: OasisInstant? = null, val invitees: List<Invitee> = emptyList())

    /** One invitee: `invited`, `contributed`, `declined`, `published` or `failed`. */
    @Serializable
    data class Invitee(val member: Member, val status: String)
}

/** Where a Zeitkapsel or a Post of the Jahrbuch appeared: a crowd by slug and name. */
@Serializable
data class NamedOasis(val slug: String, val name: String)

/** What sealing a Zeitkapsel sends: `opens_at` as ISO 8601 with the device's offset (2026-12-24T18:00:00+01:00). */
@Serializable
data class TimeCapsuleInput(
    val body: String,
    val opensAt: String,
    val oasisId: Int? = null,
    /** „Gemeinsam versiegeln“: up to 10 usernames. */
    val invitees: List<String> = emptyList(),
    /** „Beitragen bis“ (ISO with offset); none: one hour before opening. */
    val contributeUntil: String? = null
)

/** Dein Jahrbuch (GET /api/v1/network/yearbook/{year?}, from Mittendrin; 403 below): the own Posts of one year by month. */
@Serializable
data class Yearbook(
    val year: Int,
    /** The years with Posts, newest first. */
    val years: List<Int> = emptyList(),
    val totals: Totals = Totals(),
    /** The printable book on the website ("Drucken oder als PDF"). */
    val printUrl: String,
    val months: List<Month> = emptyList()
) {
    @Serializable
    data class Totals(val posts: Int = 0, val images: Int = 0, val oases: Int = 0)

    /** One month ("Januar") with its Posts. */
    @Serializable
    data class Month(val name: String, val posts: List<Entry> = emptyList())

    /** A Post of the book. */
    @Serializable
    data class Entry(
        val id: Int,
        val body: String,
        val createdAt: OasisInstant,
        val oasis: NamedOasis? = null,
        /** Through the visibility-checked post image route, loaded like the feed's images. */
        val images: List<Post.Image> = emptyList(),
        val reactions: Int = 0,
        val replies: Int = 0
    )
}
