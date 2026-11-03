package de.velwebsolutions.oasis.network.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import kotlinx.serialization.Serializable

/** One Oasen-Wochenbrief in „Frühere Wochenbriefe“ (docs/oasis-notifications.md, „Oasen-Wochenbrief“). */
@Serializable
data class WeeklyLetterSummary(
    val id: Int,
    /** `YYYY-MM-DD`, the Monday of the week (German time). */
    val weekStart: String,
    val weekEnd: String,
    /** „2 Oasen · 4 Tropfen · 1 Treffen“. */
    val summary: String = ""
)

/**
 * The Oasen-Wochenbrief: per oasis the most-reacted Tropfen, the new members, the meetups of the next days and the new
 * collections. The Tropfen are loaded again when it is opened, so one deleted since then is no longer there.
 */
@Serializable
data class WeeklyLetter(
    val id: Int,
    val weekStart: String,
    val weekEnd: String,
    val summary: String = "",
    val oases: List<Entry> = emptyList()
) {
    /** One oasis of the letter. */
    @Serializable
    data class Entry(
        val oasis: NamedOasis,
        val posts: List<Post> = emptyList(),
        val newMembers: Int = 0,
        val meetups: List<LetterMeetup> = emptyList(),
        val collections: List<LetterCollection> = emptyList()
    )

    /** A meetup of the next seven days, with its current title. */
    @Serializable
    data class LetterMeetup(val id: Int, val title: String, val startsAt: OasisInstant, val place: String? = null)

    /** A collection created in the week. */
    @Serializable
    data class LetterCollection(val id: Int, val name: String)
}

/** `GET weekly-letters/latest`: `data` is null before the first letter (a generic envelope would not allow that). */
@Serializable
data class LatestWeeklyLetter(val data: WeeklyLetter? = null)

/** An idea in „Ideen für Oasis“ (docs/oasis-admin.md): votes are counted, never shown by name. */
@Serializable
data class Idea(
    val id: Int,
    val title: String,
    val description: String? = null,
    /** `open`, `planned`, `in_progress`, `done` or `declined`; [statusLabel] says it in German. */
    val status: String = "open",
    val statusLabel: String = "Offen",
    val statusReply: String? = null,
    val statusChangedAt: OasisInstant? = null,
    val votes: Int = 0,
    val voted: Boolean = false,
    val mine: Boolean = false,
    /** Hidden after a report: only its author (and staff) see it. */
    val hidden: Boolean = false,
    val canVote: Boolean = false,
    val canDelete: Boolean = false,
    val author: Member? = null,
    val createdAt: OasisInstant,
    /** The member vote an admin linked („Zur Abstimmung“). */
    val vote: VoteRef? = null,
    val reportUrl: String = ""
) {
    @Serializable
    data class VoteRef(val id: Int, val title: String)

    /** „Geplant“, „In Arbeit“ and „Umgesetzt“ stand out. */
    val isUnderway: Boolean get() = status in UNDERWAY

    companion object {
        /** The statuses with the web's labels (`Idea::STATUSES`), for the filter. */
        val statuses: List<Pair<String, String>> = listOf(
            "open" to "Offen",
            "planned" to "Geplant",
            "in_progress" to "In Arbeit",
            "done" to "Umgesetzt",
            "declined" to "Abgelehnt"
        )

        private val UNDERWAY = setOf("planned", "in_progress", "done")
    }
}

/** What „Idee einreichen“ sends. */
@Serializable
data class IdeaInput(val title: String = "", val description: String = "")
