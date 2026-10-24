package de.velwebsolutions.oasis.staff.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import de.velwebsolutions.oasis.core.network.Page
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** One figure of the admin statistics, already formatted like the web tile ("5 €", "–", "12"). */
@Serializable
data class StatTile(val value: String, val label: String)

/** `GET staff/admin/dashboard` – admin statistics. Sums only, never people or content. */
@Serializable
data class AdminDashboard(
    val days: Int,
    val periods: List<Int> = emptyList(),
    val revenue: Revenue,
    val ai: Ai,
    val members: Members,
    val network: Network,
    val referrals: Section,
    val chat: Section,
    val moderation: Section
) {
    /** The revenue card of a month. */
    @Serializable
    data class Revenue(
        val previousMonth: String,
        /** Null on the current month; "" means "back to the current month". */
        val nextMonth: String? = null,
        val title: String,
        val period: String,
        val amount: String,
        val payments: String,
        val fees: String,
        val comparison: String
    )

    /** A section of statistic tiles. */
    @Serializable
    data class Section(val tiles: List<StatTile> = emptyList())

    /** The AI check's figures and note. */
    @Serializable
    data class Ai(val tiles: List<StatTile> = emptyList(), val note: String? = null)

    /** Members per tier. */
    @Serializable
    data class TierCount(val name: String, val count: Int)

    /** Member figures and members per tier. */
    @Serializable
    data class Members(val tiles: List<StatTile> = emptyList(), val byTier: List<TierCount> = emptyList())

    /** New accounts and Tropfen of one week. */
    @Serializable
    data class Week(val week: OasisInstant, val registered: Int, val posts: Int)

    /** Network figures and the last weeks. */
    @Serializable
    data class Network(val tiles: List<StatTile> = emptyList(), val weeks: List<Week> = emptyList())
}

/** A member vote as admins see it. */
@Serializable
data class AdminVote(
    val id: Int,
    val title: String,
    /** Wall-clock value as stored ("2026-11-01T10:00"), shown without a time zone shift like the web. */
    val startsAt: String,
    val endsAt: String,
    /** läuft | beendet | geplant */
    val state: String,
    val participations: Int,
    val results: List<Result>? = null
) {
    /** The votes of one answer. */
    @Serializable
    data class Result(val label: String, val votes: Int)
}

/** A new vote as the form sends it (times as the web's `datetime-local` values). */
@Serializable
data class NewVote(val title: String, val description: String, val startsAt: String, val endsAt: String, val options: List<String>)

/** A local payment record (`GET staff/admin/payments`). */
@Serializable
data class PaymentRecord(
    val id: Int,
    val kind: String,
    val amount: String,
    val customerName: String? = null,
    val customerEmail: String? = null,
    val accountDeleted: Boolean = false,
    val statusLabel: String,
    val refunded: Boolean = false,
    val tier: String,
    val rhythm: String,
    val createdAt: OasisInstant,
    val paidAt: OasisInstant? = null,
    val molliePaymentId: String? = null,
    val mollieCustomerId: String? = null,
    val canSync: Boolean = false,
    val canRefund: Boolean = false,
    val refundable: String,
    val refundDefault: String,
    val refunds: List<Refund> = emptyList()
) {
    /** A refund of the record. */
    @Serializable
    data class Refund(
        val amount: String,
        val createdAt: OasisInstant,
        val adminName: String? = null,
        val reason: String,
        val mollieRefundId: String? = null
    )
}

/** A page of payment records plus the filter data. */
@Serializable
data class PaymentPage(val data: List<PaymentRecord>, val meta: Page.Meta? = null, val filters: Filters) {
    val page: Page<PaymentRecord> get() = Page(data, meta)

    /** The filters the search offers. */
    @Serializable
    data class Filters(val statuses: List<Labelled> = emptyList(), val mollieId: Boolean = false, val retentionYears: Int? = null)
}

/** A payment as Mollie knows it (read only). */
@Serializable
data class MolliePayment(
    val id: String,
    val status: String,
    val amount: String? = null,
    val refunded: String? = null,
    val method: String? = null,
    val description: String? = null,
    val created: String? = null,
    val paid: String? = null,
    val customer: String? = null,
    val subscription: String? = null,
    val dashboard: String? = null
)

/** A customer as Mollie knows it. */
@Serializable
data class MollieCustomer(
    val id: String,
    val name: String? = null,
    val email: String? = null,
    val created: String? = null,
    val dashboard: String? = null
)

/** Result of a Mollie lookup: the data, or the German reason why there is none. */
@Serializable
data class MollieLookup(val mollie: Data? = null, val error: String? = null) {
    /** What Mollie answered. */
    @Serializable
    data class Data(
        /** payment | customer */
        val kind: String,
        val payment: MolliePayment? = null,
        val customer: MollieCustomer? = null,
        val payments: List<MolliePayment>? = null
    )
}

/** A data breach in the register. */
@Serializable
data class DataBreach(
    val id: Int,
    val title: String,
    val discoveredAt: OasisInstant,
    val riskLabel: String,
    val closedAt: OasisInstant? = null,
    val reportDueAt: OasisInstant,
    val reportOutstanding: Boolean = false,
    val hoursLeft: Int = 0,
    val mustReport: Boolean = false,
    val mustNotifyPeople: Boolean = false,
    val reportText: String? = null,
    val authorityReportedAt: OasisInstant? = null,
    val authorityReference: String? = null,
    val peopleNotifiedAt: OasisInstant? = null,
    val canRecordReport: Boolean = false,
    val canRecordNotified: Boolean = false,
    val log: List<LogEntry> = emptyList()
) {
    /** One entry of the breach's log. */
    @Serializable
    data class LogEntry(val at: OasisInstant, val by: String, val text: String)
}

/** `GET staff/admin/breaches`. */
@Serializable
data class BreachRegister(val data: List<DataBreach> = emptyList(), val risks: List<Labelled> = emptyList(), val authority: String? = null)

/** A new data breach as the form sends it (discovery as the web's `datetime-local` value; every
 * field is sent, also when empty or untouched). */
@Serializable
data class NewBreach(
    @EncodeDefault val title: String = "",
    @EncodeDefault val discoveredAt: String = "",
    @EncodeDefault val description: String = "",
    @EncodeDefault val dataCategories: String = "",
    @EncodeDefault val affectedPeople: String = "",
    @EncodeDefault val risk: String = "risk",
    @EncodeDefault val consequences: String = "",
    @EncodeDefault val measures: String = ""
)

/** `GET staff/admin/system`. */
@Serializable
data class SystemStatus(
    val healthy: Boolean,
    val checks: List<Check> = emptyList(),
    val backups: List<String> = emptyList(),
    val botRefusals: List<BotRefusal> = emptyList(),
    val heartbeats: List<Heartbeat> = emptyList()
) {
    /** One health check. */
    @Serializable
    data class Check(val key: String, val label: String, val ok: Boolean, val detail: String)

    /** Refused bot attempts of one kind today. */
    @Serializable
    data class BotRefusal(val label: String, val count: Int)

    /** The last runs of a scheduled job. */
    @Serializable
    data class Heartbeat(val key: String, val lastSuccessAt: OasisInstant? = null, val lastFailureAt: OasisInstant? = null)
}
