package de.velwebsolutions.oasis.staff.data

import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.staff.api.AdminDashboard
import de.velwebsolutions.oasis.staff.api.AdminVote
import de.velwebsolutions.oasis.staff.api.AssignBody
import de.velwebsolutions.oasis.staff.api.BreachRegister
import de.velwebsolutions.oasis.staff.api.CaseSummary
import de.velwebsolutions.oasis.staff.api.ComplaintDetail
import de.velwebsolutions.oasis.staff.api.ComplaintReview
import de.velwebsolutions.oasis.staff.api.DataBreach
import de.velwebsolutions.oasis.staff.api.ModerationCaseDetail
import de.velwebsolutions.oasis.staff.api.ModerationOverview
import de.velwebsolutions.oasis.staff.api.MollieLookup
import de.velwebsolutions.oasis.staff.api.NewBreach
import de.velwebsolutions.oasis.staff.api.NewVote
import de.velwebsolutions.oasis.staff.api.NoteBody
import de.velwebsolutions.oasis.staff.api.PaymentPage
import de.velwebsolutions.oasis.staff.api.PaymentRecord
import de.velwebsolutions.oasis.staff.api.QueueFilter
import de.velwebsolutions.oasis.staff.api.ReferenceBody
import de.velwebsolutions.oasis.staff.api.RefundBody
import de.velwebsolutions.oasis.staff.api.RoleBody
import de.velwebsolutions.oasis.staff.api.RolePage
import de.velwebsolutions.oasis.staff.api.RoleUser
import de.velwebsolutions.oasis.staff.api.SearchBody
import de.velwebsolutions.oasis.staff.api.StaffService
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.api.SuspensionList
import de.velwebsolutions.oasis.staff.api.SystemStatus
import de.velwebsolutions.oasis.staff.api.TypeBody
import javax.inject.Inject

/** Moderation (cases, complaints, roles, locks) and administration (statistics, votes, payments, breaches, system). */
interface StaffRepository {
    suspend fun overview(): ModerationOverview

    suspend fun cases(filter: QueueFilter, page: Int = 1): Page<CaseSummary>

    suspend fun moderationCase(id: Int): ModerationCaseDetail

    suspend fun assign(caseId: Int, staffId: Int): Confirmed<ModerationCaseDetail>

    suspend fun decide(caseId: Int, statement: StatementOfReasons): Confirmed<ModerationCaseDetail>

    suspend fun escalate(caseId: Int, type: String): Confirmed<ModerationCaseDetail>

    suspend fun recordAuthorityReport(caseId: Int, reference: String): Confirmed<ModerationCaseDetail>

    suspend fun extendEvidence(caseId: Int): Confirmed<ModerationCaseDetail>

    suspend fun deleteEvidence(caseId: Int): Confirmed<ModerationCaseDetail>

    suspend fun liftQuarantine(caseId: Int): Confirmed<ModerationCaseDetail>

    /** The evidence ZIP for the police (admins only); the bytes as the web download serves them. */
    suspend fun evidenceArchive(caseId: Int): ByteArray

    suspend fun complaint(id: Int): ComplaintDetail

    suspend fun review(complaintId: Int, result: String, explanation: String, replacement: StatementOfReasons): Confirmed<ComplaintDetail>

    suspend fun roles(page: Int): RolePage

    suspend fun changeRole(userId: Int, role: String): Confirmed<RoleUser>

    suspend fun suspensions(): SuspensionList

    /** Returns the server's confirmation. */
    suspend fun release(suspensionId: Int): String

    /** Statistics for [days] (7, 30, 90, 365) and the revenue [month] ("YYYY-MM", "" for the current one). */
    suspend fun dashboard(days: Int, month: String): AdminDashboard

    suspend fun votes(): List<AdminVote>

    suspend fun createVote(vote: NewVote): Confirmed<AdminVote>

    suspend fun payments(search: String, status: String, page: Int): PaymentPage

    suspend fun checkPayment(recordId: Int): MollieLookup

    suspend fun checkCustomer(recordId: Int): MollieLookup

    suspend fun searchMollie(term: String): MollieLookup

    suspend fun syncPayment(recordId: Int): Confirmed<PaymentRecord>

    suspend fun refund(recordId: Int, amount: String, reason: String): Confirmed<PaymentRecord>

    suspend fun breaches(): BreachRegister

    suspend fun recordBreach(breach: NewBreach): Confirmed<DataBreach>

    suspend fun recordBreachReport(id: Int, reference: String): Confirmed<DataBreach>

    suspend fun recordPeopleNotified(id: Int): Confirmed<DataBreach>

    suspend fun addBreachNote(id: Int, note: String): Confirmed<DataBreach>

    suspend fun closeBreach(id: Int): Confirmed<DataBreach>

    suspend fun system(): SystemStatus
}

class DefaultStaffRepository @Inject constructor(private val client: ApiClient) : StaffRepository {
    private val service = client.create<StaffService>()

    override suspend fun overview() = service.overview().data

    override suspend fun cases(filter: QueueFilter, page: Int) = service.cases(
        status = filter.status,
        priority = filter.priority,
        mine = if (filter.mine) "1" else null,
        search = filter.search.takeIf { it.isNotBlank() },
        page = page
    )

    override suspend fun moderationCase(id: Int) = service.moderationCase(id).data

    override suspend fun assign(caseId: Int, staffId: Int) = service.assign(caseId, AssignBody(staffId))

    override suspend fun decide(caseId: Int, statement: StatementOfReasons) = service.decide(caseId, statement)

    override suspend fun escalate(caseId: Int, type: String) = service.escalate(caseId, TypeBody(type))

    override suspend fun recordAuthorityReport(caseId: Int, reference: String) =
        service.recordAuthorityReport(caseId, ReferenceBody(reference))

    override suspend fun extendEvidence(caseId: Int) = service.extendEvidence(caseId)

    override suspend fun deleteEvidence(caseId: Int) = service.deleteEvidence(caseId)

    override suspend fun liftQuarantine(caseId: Int) = service.liftQuarantine(caseId)

    override suspend fun evidenceArchive(caseId: Int) = client.download(client.url("staff/moderation/cases/$caseId/evidence").toString())

    override suspend fun complaint(id: Int) = service.complaint(id).data

    override suspend fun review(complaintId: Int, result: String, explanation: String, replacement: StatementOfReasons) =
        service.review(complaintId, ComplaintReview.of(result, explanation, replacement))

    override suspend fun roles(page: Int) = service.roles(page)

    override suspend fun changeRole(userId: Int, role: String) = service.changeRole(userId, RoleBody(role))

    override suspend fun suspensions() = service.suspensions().data

    override suspend fun release(suspensionId: Int) = service.release(suspensionId).message

    override suspend fun dashboard(days: Int, month: String) = service.dashboard(days, month.ifEmpty { null }).data

    override suspend fun votes() = service.votes().data

    override suspend fun createVote(vote: NewVote) = service.createVote(vote)

    override suspend fun payments(search: String, status: String, page: Int) =
        service.payments(search.ifEmpty { null }, status.ifEmpty { null }, page)

    override suspend fun checkPayment(recordId: Int) = service.checkPayment(recordId).data

    override suspend fun checkCustomer(recordId: Int) = service.checkCustomer(recordId).data

    override suspend fun searchMollie(term: String) = service.searchMollie(SearchBody(term)).data

    override suspend fun syncPayment(recordId: Int) = service.syncPayment(recordId)

    override suspend fun refund(recordId: Int, amount: String, reason: String) = service.refund(recordId, RefundBody(amount, reason))

    override suspend fun breaches() = service.breaches()

    override suspend fun recordBreach(breach: NewBreach) = service.recordBreach(breach)

    override suspend fun recordBreachReport(id: Int, reference: String) = service.recordBreachReport(id, ReferenceBody(reference))

    override suspend fun recordPeopleNotified(id: Int) = service.recordPeopleNotified(id)

    override suspend fun addBreachNote(id: Int, note: String) = service.addBreachNote(id, NoteBody(note))

    override suspend fun closeBreach(id: Int) = service.closeBreach(id)

    override suspend fun system() = service.system().data
}
