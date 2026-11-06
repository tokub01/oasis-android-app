package de.velwebsolutions.oasis.staff.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import de.velwebsolutions.oasis.core.network.Page
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** An enum value of the backend with the German label the web shows (`{ "value": "open", "label": "Offen" }`). */
@Serializable
data class Labelled(val value: String, val label: String)

/**
 * A measure for the decision picker: the value, the German label and – for "Warnung" and "Konto gesperrt" – the one
 * sentence the web shows under the option (`StaffJson::outcomes`).
 */
@Serializable
data class OutcomeOption(val value: String, val label: String, val hint: String? = null) {
    /** Whether this is "Konto gesperrt", the only measure with a duration and a condition. */
    val isSuspension: Boolean get() = value == ACCOUNT_SUSPENDED

    companion object {
        const val ACCOUNT_SUSPENDED = "account_suspended"
        const val WARNING = "warning"
    }
}

/**
 * Whether "Konto gesperrt" may be chosen on a case (`SuspensionRule`: a warning or lock within `strike_months` first,
 * except in severe cases), why not, and the durations in days to choose from (`oasis.moderation.suspension_days`).
 */
@Serializable
data class SuspensionOption(val allowed: Boolean = true, val blockedReason: String? = null, val days: List<Int> = emptyList())

/** "1 Tag" or "n Tage", as the web writes the duration of a lock (`SuspensionRule::durationLabel`). */
fun durationLabel(days: Int): String = if (days == 1) "1 Tag" else "$days Tage"

/** `GET staff/moderation/overview` – the moderation start page. */
@Serializable
data class ModerationOverview(val figures: Figures, val activity: List<Activity> = emptyList(), val navigation: Navigation) {
    /** The figure cards. */
    @Serializable
    data class Figures(
        val active: Int,
        val unassigned: Int,
        val urgent: Int,
        val mine: Int,
        val complaints: Int,
        val decidedWeek: Int,
        val oldestWaitingLabel: String,
        val medianLabel: String
    )

    /** One recent event of a case. */
    @Serializable
    data class Activity(val caseId: Int, val type: Labelled, val actorName: String? = null, val createdAt: OasisInstant)

    /** Who is signed in and which staff pages they may open. */
    @Serializable
    data class Navigation(val displayName: String, val role: Labelled, val canManageRoles: Boolean, val canAdministrate: Boolean)
}

/** One row of the queue (`GET staff/moderation/cases`). */
@Serializable
data class CaseSummary(
    val id: Int,
    val status: Labelled,
    val priority: Labelled,
    val escalation: Labelled? = null,
    val subjectUrl: String,
    val reasonExcerpt: String? = null,
    val assigneeName: String? = null,
    val createdAt: OasisInstant
)

/** The queue filters, named like the web's query parameters. */
data class QueueFilter(
    /** A case status, "active" (offen und in Arbeit) or "all". */
    val status: String = "active",
    /** "urgent", "normal" or "all". */
    val priority: String = "all",
    val mine: Boolean = false,
    val search: String = ""
) {
    companion object {
        /** Status options of the filter in the web's order. */
        val statuses = listOf(
            Labelled("active", "Offen und in Arbeit"),
            Labelled("all", "Alle"),
            Labelled("open", "Offen"),
            Labelled("in_review", "In Prüfung"),
            Labelled("decided", "Entschieden"),
            Labelled("complaint_open", "Beschwerde offen"),
            Labelled("closed", "Abgeschlossen")
        )

        val priorities = listOf(Labelled("all", "Alle"), Labelled("normal", "Normal"), Labelled("urgent", "Dringend"))
    }
}

/** A statement of reasons (DSA Art. 17). */
@Serializable
data class Decision(
    val outcome: Labelled,
    /** Days of the lock for "Konto gesperrt" (Art. 17 Abs. 3 lit. a DSA), null for every other measure. */
    val suspensionDays: Int? = null,
    val ground: Labelled,
    val isComplaintReview: Boolean = false,
    val deciderName: String? = null,
    val decidedAt: OasisInstant,
    val notifiedAt: OasisInstant? = null,
    val ruleReference: String,
    val facts: String,
    val explanation: String
)

/** One moderation case (`GET staff/moderation/cases/{id}`). The reporter is never part of it. */
@Serializable
data class ModerationCaseDetail(
    val id: Int,
    val status: Labelled,
    val priority: Labelled,
    val subjectUrl: String,
    val createdAt: OasisInstant,
    val report: Report,
    val subjectUser: Person? = null,
    val assignee: Assignee? = null,
    val assignDefault: Int,
    val staff: List<StaffMember> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val complaints: List<ComplaintSummary> = emptyList(),
    val events: List<Event> = emptyList(),
    val escalation: Escalation? = null,
    val escalationOptions: List<EscalationOption> = emptyList(),
    val evidenceDays: Int,
    val outcomes: List<OutcomeOption> = emptyList(),
    /** Whether "Konto gesperrt" is available on this case and the durations to choose from. */
    val suspension: SuspensionOption = SuspensionOption(),
    val grounds: List<Labelled> = emptyList(),
    val can: Permissions,
    /** The viewer is a party of the case (affected account or reporter), so another person decides it. */
    val involved: Boolean = false
) {
    /**
     * Why the decision form is missing (case-detail.blade.php): the viewer is involved and the case still waits for its
     * first decision (`CaseStatus::awaitsFirstDecision`). Null otherwise.
     */
    val involvedNote: String?
        get() = if (involved && status.value in setOf("open", "in_review")) {
            "Du bist an diesem Fall beteiligt. Übernehmen und entscheiden kann ihn deshalb nur eine andere Person aus dem Team."
        } else {
            null
        }

    /**
     * The question before lifting the quarantine, after what the case is about (escalation.blade.php, iOS
     * `liftQuarantineQuestion`): a chat report's attachments, a Tropfen's images, an oasis' banner, or the photo and
     * banner of a profile – the last two are served again at once.
     */
    val liftQuarantineQuestion: String
        get() = when {
            report.kind == "chat_report" -> "Quarantäne aufheben? Die Anhänge der Nachricht sind dann für das Team wieder zu sehen."

            "/tropfen/" in subjectUrl ->
                "Quarantäne aufheben? Die Bilder werden dann wieder ausgeliefert, sobald der Tropfen freigegeben ist."

            "/oasen/" in subjectUrl -> "Quarantäne aufheben? Das Banner der Oase wird dann wieder ausgeliefert."

            else -> "Quarantäne aufheben? Profilbild und Banner werden dann wieder ausgeliefert."
        }

    /** What was reported, and how. */
    @Serializable
    data class Report(
        /** content_report | chat_report | content_check */
        val kind: String,
        val reason: String? = null,
        /** The written text of a reported chat message ("(nur Anhang)" for one with files only). */
        val plaintext: String? = null,
        /** The photos, voice messages, videos and files of a reported chat message, opened with the reporter's key. */
        val attachments: List<ReportedAttachment> = emptyList(),
        val check: Check? = null,
        /** How many files of a chat message in quarantine are held back; none of them is listed in [attachments]. */
        val lockedAttachments: Int = 0
    )

    /** One file of a reported chat message (`cases/{id}/chat-attachments/{index}`, loaded with the member's token). */
    @Serializable
    data class ReportedAttachment(
        /** image | audio | video | file */
        val kind: String,
        val mime: String,
        val url: String,
        /** The file name of a `file` (null without one, and for photos, voice messages and videos). */
        val name: String? = null
    )

    /** The automated check of a drop. */
    @Serializable
    data class Check(
        val sourceLabel: String,
        val checkedAt: OasisInstant? = null,
        val checkedText: String? = null,
        val scores: List<Score> = emptyList(),
        val reason: String? = null,
        val heldAt: OasisInstant? = null
    )

    /** One score of the automated check. */
    @Serializable
    data class Score(val key: String, val label: String, val percent: Int, val flagged: Boolean)

    /** The account a case is about. */
    @Serializable
    data class Person(val displayName: String, val username: String)

    /** The person a case is assigned to. */
    @Serializable
    data class Assignee(val id: Int)

    /** A person the case can be assigned to. */
    @Serializable
    data class StaffMember(val id: Int, val displayName: String, val isMe: Boolean = false)

    /** A complaint against a decision of the case. */
    @Serializable
    data class ComplaintSummary(val id: Int, val status: Labelled, val submittedAt: OasisInstant, val reviewerName: String? = null)

    /** One entry of the case history. */
    @Serializable
    data class Event(val type: Labelled, val actorName: String? = null, val createdAt: OasisInstant)

    /** The escalation of a case (child abuse or terror). */
    @Serializable
    data class Escalation(
        val type: Labelled,
        val escalatedAt: OasisInstant? = null,
        val escalatedByName: String? = null,
        val checklist: List<String> = emptyList(),
        val authorityContact: String? = null,
        val authorityReportedAt: OasisInstant? = null,
        val authorityReference: String? = null,
        val evidence: List<Evidence> = emptyList(),
        val quarantined: Boolean = false
    )

    /** A secured piece of evidence. */
    @Serializable
    data class Evidence(
        /** text | image */
        val kind: String,
        val createdAt: OasisInstant,
        val purgeAfter: OasisInstant,
        val sha256: String? = null,
        val bytes: Int? = null,
        val content: String? = null
    )

    /** A way to escalate a case. */
    @Serializable
    data class EscalationOption(val value: String, val label: String, val description: String)

    /** What the signed-in person may do with the case. */
    @Serializable
    data class Permissions(val assign: Boolean, val decide: Boolean, val administrate: Boolean)
}

/**
 * The five parts of a statement of reasons, as the decision form sends them (empty parts too, so the server names
 * them), plus the duration in days for "Konto gesperrt" (`suspension_days`, required with that measure).
 */
@Serializable
data class StatementOfReasons(
    @EncodeDefault val outcome: String = "",
    @EncodeDefault val ground: String = "",
    @EncodeDefault val ruleReference: String = "",
    @EncodeDefault val facts: String = "",
    @EncodeDefault val explanation: String = "",
    val suspensionDays: Int? = null
) {
    /** "Konto gesperrt" is chosen. */
    val isSuspension: Boolean get() = outcome == OutcomeOption.ACCOUNT_SUSPENDED

    /** "Konto gesperrt" without its duration: the server refuses it, naming `suspension_days`. */
    val lacksDuration: Boolean get() = isSuspension && suspensionDays == null

    /** The chosen measure with its duration, as the web's case page writes it: "Konto gesperrt (7 Tage)". */
    fun outcomeLabel(outcomes: List<OutcomeOption>): String {
        val label = outcomes.firstOrNull { it.value == outcome }?.label.orEmpty()
        val days = suspensionDays?.takeIf { isSuspension } ?: return label
        return "$label (${durationLabel(days)})"
    }
}

/** A complaint for review (`GET staff/moderation/complaints/{id}`). */
@Serializable
data class ComplaintDetail(
    val caseId: Int,
    val status: Labelled,
    val submittedAt: OasisInstant,
    val decision: OriginalDecision,
    val reason: String,
    val canReview: Boolean,
    val result: Result? = null,
    val outcomes: List<OutcomeOption> = emptyList(),
    /** Whether "Konto gesperrt" is available as the replacement decision and the durations to choose from. */
    val suspension: SuspensionOption = SuspensionOption(),
    val grounds: List<Labelled> = emptyList()
) {
    /** The decision the complaint is against. */
    @Serializable
    data class OriginalDecision(
        val outcome: Labelled,
        /** Days of the lock for "Konto gesperrt", null for every other measure (and in older answers). */
        val suspensionDays: Int? = null,
        val ground: Labelled,
        val deciderName: String? = null,
        val decidedAt: OasisInstant,
        val ruleReference: String,
        val explanation: String
    )

    /** The result of the review. */
    @Serializable
    data class Result(val reviewerName: String? = null, val reviewedAt: OasisInstant? = null, val explanation: String? = null)
}

/**
 * The review as the form sends it: `result` "upheld" sends the explanation, "overturned" the new statement of reasons
 * (whose explanation is also the reason of the result), nothing chosen an empty result (422 like the web).
 */
@Serializable
data class ComplaintReview(
    val result: String,
    val explanation: String? = null,
    val outcome: String? = null,
    val suspensionDays: Int? = null,
    val ground: String? = null,
    val ruleReference: String? = null,
    val facts: String? = null
) {
    companion object {
        fun of(result: String, explanation: String, replacement: StatementOfReasons) = when (result) {
            "upheld" -> ComplaintReview(result, explanation = explanation)

            "overturned" -> ComplaintReview(
                result,
                explanation = replacement.explanation,
                outcome = replacement.outcome,
                suspensionDays = replacement.suspensionDays,
                ground = replacement.ground,
                ruleReference = replacement.ruleReference,
                facts = replacement.facts
            )

            else -> ComplaintReview(result)
        }
    }
}

/** An account on the roles screen. */
@Serializable
data class RoleUser(
    val id: Int,
    val displayName: String,
    val email: String,
    val role: Labelled,
    val editable: Boolean,
    val note: String? = null,
    val choosableRoles: List<String> = emptyList(),
    /** Whether the address is confirmed. */
    val emailVerified: Boolean = false,
    /** Confirm e-mail, password link and new password: only the operator account, never for itself. */
    val accessManageable: Boolean = false
)

/** `GET staff/moderation/roles` – a page of accounts plus the role names. */
@Serializable
data class RolePage(val data: List<RoleUser>, val meta: Page.Meta? = null, val roles: List<Labelled> = emptyList()) {
    val page: Page<RoleUser> get() = Page(data, meta)
}

/** `GET staff/moderation/suspensions`: the locks in force and the lately ended ones, with the strike rule. */
@Serializable
data class SuspensionList(
    val active: List<Suspension> = emptyList(),
    val ended: List<Suspension> = emptyList(),
    val strikesForBan: Int,
    /** Months a confirmed violation counts towards the ban and as the warning before a lock. */
    val strikeMonths: Int = 12
)

/** A lock (active or ended): `suspension` from a decision with its planned end, or `ban`. */
@Serializable
data class Suspension(
    val id: Int,
    val type: String = "suspension",
    val label: String,
    val user: Account,
    val startedAt: OasisInstant,
    /** The planned end of a lock with a duration; null for a ban. */
    val endsAt: OasisInstant? = null,
    /** The planned end as the web shows it ("12.11.2026, 14:05 Uhr"); null for a ban. */
    val endsAtLabel: String? = null,
    val endedAt: OasisInstant? = null,
    /** Why an ended lock ended ("Dauer abgelaufen", "durch eine neue Sperre ersetzt", …); null while in force. */
    val resultLabel: String? = null,
    val caseId: Int? = null,
    val accusation: String? = null,
    val excerpt: String? = null,
    val statement: String? = null,
    val statementAt: OasisInstant? = null,
    val canRelease: Boolean? = null
) {
    /** The locked account; [activeStrikes] counts the confirmed violations of the last `strike_months`. */
    @Serializable
    data class Account(val displayName: String, val username: String, val activeStrikes: Int? = null)
}
