package de.velwebsolutions.oasis.membership.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * „Wohin dein Beitrag geht“ (GET /api/v1/transparency, docs/oasis-admin.md): only figures the server knows and finance
 * reports an admin published – never estimated.
 */
@Serializable
data class Transparency(
    val operator: String,
    val figures: Figures,
    val report: FinanceReport? = null,
    val earlier: List<EarlierReport> = emptyList()
) {
    @Serializable
    data class Figures(val activeMembers: Int = 0, val oases: Int = 0, val ideasDone: Int = 0, val votes: List<Vote> = emptyList())

    /** A member vote that ended, with its results (secret ballot: only after the end). */
    @Serializable
    data class Vote(
        val id: Int,
        val title: String,
        val endedAt: OasisInstant,
        val participations: Int = 0,
        val results: List<Result> = emptyList()
    ) {
        @Serializable
        data class Result(val label: String, val votes: Int)
    }

    /** One of „Frühere Berichte“. */
    @Serializable
    data class EarlierReport(val id: Int, val periodLabel: String)
}

/** A published finance report of a month or quarter: income, costs per category, their total and the difference. */
@Serializable
data class FinanceReport(
    val id: Int,
    /** „November 2026“, „4. Quartal 2026“. */
    val periodLabel: String,
    val income: Double,
    val costs: List<Cost> = emptyList(),
    val costsTotal: Double = 0.0,
    val balance: Double = 0.0,
    val note: String? = null
) {
    @Serializable
    data class Cost(val key: String, val label: String, val amount: Double)

    /** The share of a category in the costs, „(42 %)“; null without costs. */
    fun share(cost: Cost): String? = if (costsTotal > 0) "(${(cost.amount / costsTotal * 100).roundToInt()} %)" else null

    companion object {
        /** „1.234,56 €“ (`OasisFormat::money`). */
        fun euros(amount: Double): String {
            val format = NumberFormat.getNumberInstance(Locale.GERMANY)
            format.minimumFractionDigits = 2
            format.maximumFractionDigits = 2
            return format.format(amount) + " €"
        }
    }
}
