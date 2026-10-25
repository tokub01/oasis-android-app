package de.velwebsolutions.oasis.staff

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.staff.api.AdminDashboard
import de.velwebsolutions.oasis.staff.api.AdminVote
import de.velwebsolutions.oasis.staff.api.BreachRegister
import de.velwebsolutions.oasis.staff.api.CaseSummary
import de.velwebsolutions.oasis.staff.api.ComplaintDetail
import de.velwebsolutions.oasis.staff.api.DataBreach
import de.velwebsolutions.oasis.staff.api.ModerationCaseDetail
import de.velwebsolutions.oasis.staff.api.ModerationOverview
import de.velwebsolutions.oasis.staff.api.MollieLookup
import de.velwebsolutions.oasis.staff.api.NewBreach
import de.velwebsolutions.oasis.staff.api.NewVote
import de.velwebsolutions.oasis.staff.api.PaymentPage
import de.velwebsolutions.oasis.staff.api.PaymentRecord
import de.velwebsolutions.oasis.staff.api.QueueFilter
import de.velwebsolutions.oasis.staff.api.RolePage
import de.velwebsolutions.oasis.staff.api.RoleUser
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.api.SuspensionList
import de.velwebsolutions.oasis.staff.api.SystemStatus
import de.velwebsolutions.oasis.staff.data.StaffRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** The fixtures decoded as the screens get them. */
object Decoded {
    fun <T> plain(json: String, serializer: KSerializer<T>): T = OasisJson.snake.decodeFromString(serializer, json)

    fun <T> data(json: String, serializer: KSerializer<T>): T = plain(json, DataEnvelope.serializer(serializer)).data

    val overview get() = data(Fixtures.overview, ModerationOverview.serializer())
    val cases get() = plain(Fixtures.cases, Page.serializer(CaseSummary.serializer()))
    val caseDetail get() = data(Fixtures.caseDetail, ModerationCaseDetail.serializer())
    val caseEscalated get() = data(Fixtures.caseEscalated, ModerationCaseDetail.serializer())
    val caseCheck get() = data(Fixtures.caseCheck, ModerationCaseDetail.serializer())
    val complaint get() = data(Fixtures.complaint, ComplaintDetail.serializer())
    val roles get() = plain(Fixtures.roles, RolePage.serializer())
    val suspensions get() = data(Fixtures.suspensions, SuspensionList.serializer())
    val dashboard get() = data(Fixtures.dashboard, AdminDashboard.serializer())
    val votes get() = data(Fixtures.votes, ListSerializer(AdminVote.serializer()))
    val payments get() = plain(Fixtures.payments, PaymentPage.serializer())
    val breaches get() = plain(Fixtures.breaches, BreachRegister.serializer())
    val system get() = data(Fixtures.system, SystemStatus.serializer())
}

/**
 * The staff API without a network, for view-model tests: every call answers synchronously with what the test set (the
 * recorded fixtures by default), or throws the [ApiError] it set; [calls] lists what was asked.
 */
class FakeStaffRepository : StaffRepository {
    val calls = mutableListOf<String>()
    var overview: Result<ModerationOverview> = Result.success(Decoded.overview)
    var cases: Result<Page<CaseSummary>> = Result.success(Decoded.cases)
    var case: Result<ModerationCaseDetail> = Result.success(Decoded.caseDetail)
    var caseAction: Result<Confirmed<ModerationCaseDetail>> = Result.success(Confirmed(Decoded.caseDetail, "Gespeichert."))
    var archive: Result<ByteArray> = Result.success("PK".toByteArray())
    var complaint: Result<ComplaintDetail> = Result.success(Decoded.complaint)
    var review: Result<Confirmed<ComplaintDetail>> = Result.success(Confirmed(Decoded.complaint, "Geprüft."))
    var roles: Result<RolePage> = Result.success(Decoded.roles)
    var changeRole: Result<Confirmed<RoleUser>>? = null
    var suspensions: Result<SuspensionList> = Result.success(Decoded.suspensions)
    var release: Result<String> = Result.success("Freigegeben.")
    var dashboard: Result<AdminDashboard> = Result.success(Decoded.dashboard)
    var votes: Result<List<AdminVote>> = Result.success(Decoded.votes)
    var createVote: Result<Confirmed<AdminVote>> = Result.success(Confirmed(Decoded.votes.first(), "Angelegt."))
    var payments: Result<PaymentPage> = Result.success(Decoded.payments)
    var mollie: Result<MollieLookup> = Result.success(MollieLookup(error = "Nichts gefunden."))
    var paymentAction: Result<Confirmed<PaymentRecord>>? = null
    var breaches: Result<BreachRegister> = Result.success(Decoded.breaches)
    var breachAction: Result<Confirmed<DataBreach>> = Result.success(Confirmed(Decoded.breaches.data.first(), "Vermerkt."))
    var system: Result<SystemStatus> = Result.success(Decoded.system)

    private fun <T> answer(call: String, result: Result<T>): T {
        calls += call
        return result.getOrThrow()
    }

    override suspend fun overview() = answer("overview", overview)

    /** Holds the next queue page until the test completes it. */
    var casesGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun cases(filter: QueueFilter, page: Int): Page<CaseSummary> {
        casesGate?.await()
        return answer("cases $page ${filter.status}", cases)
    }

    override suspend fun moderationCase(id: Int) = answer("case $id", case)

    override suspend fun assign(caseId: Int, staffId: Int) = answer("assign $staffId", caseAction)

    override suspend fun decide(caseId: Int, statement: StatementOfReasons) = answer("decide ${statement.outcome}", caseAction)

    override suspend fun escalate(caseId: Int, type: String) = answer("escalate $type", caseAction)

    override suspend fun recordAuthorityReport(caseId: Int, reference: String) = answer("report $reference", caseAction)

    override suspend fun extendEvidence(caseId: Int) = answer("extend", caseAction)

    override suspend fun deleteEvidence(caseId: Int) = answer("delete", caseAction)

    override suspend fun liftQuarantine(caseId: Int) = answer("lift", caseAction)

    override suspend fun evidenceArchive(caseId: Int) = answer("archive", archive)

    override suspend fun complaint(id: Int) = answer("complaint $id", complaint)

    override suspend fun review(complaintId: Int, result: String, explanation: String, replacement: StatementOfReasons) =
        answer("review $result", review)

    override suspend fun roles(page: Int) = answer("roles $page", roles)

    override suspend fun changeRole(userId: Int, role: String): Confirmed<RoleUser> {
        val user = Decoded.roles.data.first { it.id == userId }
        return answer(
            "role $userId $role",
            changeRole ?: Result.success(Confirmed(user.copy(role = user.role.copy(value = role)), "Geändert."))
        )
    }

    /** The answer of the operator's access actions; by default the person comes back confirmed. */
    var access: Result<Confirmed<RoleUser>>? = null

    private fun accessAnswer(userId: Int, message: String) = access
        ?: Result.success(
            Confirmed(
                Decoded.roles.data.first {
                    it.id == userId
                }.copy(emailVerified = true, accessManageable = true),
                message
            )
        )

    override suspend fun confirmEmail(userId: Int) = answer("confirm $userId", accessAnswer(userId, "Bestätigt."))

    override suspend fun sendPasswordLink(userId: Int) = answer("link $userId", accessAnswer(userId, "Link gesendet."))

    override suspend fun setPassword(userId: Int, password: String) = answer("password $userId $password", accessAnswer(userId, "Gesetzt."))

    override suspend fun suspensions() = answer("suspensions", suspensions)

    override suspend fun release(suspensionId: Int) = answer("release $suspensionId", release)

    override suspend fun dashboard(days: Int, month: String) = answer("dashboard $days $month", dashboard)

    override suspend fun votes() = answer("votes", votes)

    override suspend fun createVote(vote: NewVote) = answer("vote ${vote.title}", createVote)

    override suspend fun payments(search: String, status: String, page: Int) = answer("payments '$search' '$status' $page", payments)

    override suspend fun checkPayment(recordId: Int) = answer("check payment $recordId", mollie)

    override suspend fun checkCustomer(recordId: Int) = answer("check customer $recordId", mollie)

    override suspend fun searchMollie(term: String) = answer("mollie $term", mollie)

    override suspend fun syncPayment(recordId: Int) = answer("sync $recordId", paymentAction ?: Result.success(recordAnswer(recordId)))

    override suspend fun refund(recordId: Int, amount: String, reason: String) =
        answer("refund $recordId $amount", paymentAction ?: Result.success(recordAnswer(recordId)))

    private fun recordAnswer(id: Int) = Confirmed(
        Decoded.payments.data.first {
            it.id == id
        }.copy(statusLabel = "Abgeglichen"),
        "Abgeglichen."
    )

    override suspend fun breaches() = answer("breaches", breaches)

    override suspend fun recordBreach(breach: NewBreach) = answer("breach ${breach.discoveredAt}", breachAction)

    override suspend fun recordBreachReport(id: Int, reference: String) = answer("breach report $reference", breachAction)

    override suspend fun recordPeopleNotified(id: Int) = answer("notified $id", breachAction)

    override suspend fun addBreachNote(id: Int, note: String) = answer("note $note", breachAction)

    override suspend fun closeBreach(id: Int) = answer("close $id", breachAction)

    override suspend fun system() = answer("system", system)

    companion object {
        /** A server error as the API client reports it. */
        fun failure(message: String = "Server Error") = Result.failure<Nothing>(ApiError.Server(500, message))

        /** A refused form with its field messages. */
        fun invalid(vararg fields: Pair<String, String>) =
            Result.failure<Nothing>(ApiError.Validation(fields.first().second, fields.associate { (field, text) -> field to listOf(text) }))
    }
}
