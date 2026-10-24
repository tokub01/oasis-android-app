package de.velwebsolutions.oasis.staff.api

import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.MessageResponse
import de.velwebsolutions.oasis.core.network.Page
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** `{"assigned_to": …}`. */
@Serializable
data class AssignBody(val assignedTo: Int)

/** `{"type": …}`. */
@Serializable
data class TypeBody(val type: String)

/** `{"reference": …}`. */
@Serializable
data class ReferenceBody(val reference: String)

/** `{"role": …}`. */
@Serializable
data class RoleBody(val role: String)

/** `{"q": …}`. */
@Serializable
data class SearchBody(val q: String)

/** `{"amount": …, "reason": …}`. */
@Serializable
data class RefundBody(val amount: String, val reason: String)

/** `{"note": …}`. */
@Serializable
data class NoteBody(val note: String)

/**
 * The staff endpoints (`/api/v1/staff/…`, Laravel routes/api/v1/members/staff.php). Every call needs a staff role
 * with confirmed two-factor sign-in; the admin calls additionally the admin role.
 */
interface StaffService {
    @GET("staff/moderation/overview")
    suspend fun overview(): DataEnvelope<ModerationOverview>

    /** One page of the queue (25 cases), filtered like /moderation. */
    @GET("staff/moderation/cases")
    suspend fun cases(
        @Query("status") status: String,
        @Query("prioritaet") priority: String,
        @Query("meine") mine: String?,
        @Query("suche") search: String?,
        @Query("page") page: Int
    ): Page<CaseSummary>

    @GET("staff/moderation/cases/{id}")
    suspend fun moderationCase(@Path("id") id: Int): DataEnvelope<ModerationCaseDetail>

    @POST("staff/moderation/cases/{id}/assign")
    suspend fun assign(@Path("id") id: Int, @Body body: AssignBody): Confirmed<ModerationCaseDetail>

    @POST("staff/moderation/cases/{id}/decision")
    suspend fun decide(@Path("id") id: Int, @Body body: StatementOfReasons): Confirmed<ModerationCaseDetail>

    @POST("staff/moderation/cases/{id}/escalate")
    suspend fun escalate(@Path("id") id: Int, @Body body: TypeBody): Confirmed<ModerationCaseDetail>

    @POST("staff/moderation/cases/{id}/authority-report")
    suspend fun recordAuthorityReport(@Path("id") id: Int, @Body body: ReferenceBody): Confirmed<ModerationCaseDetail>

    @POST("staff/moderation/cases/{id}/evidence/extend")
    suspend fun extendEvidence(@Path("id") id: Int): Confirmed<ModerationCaseDetail>

    @DELETE("staff/moderation/cases/{id}/evidence")
    suspend fun deleteEvidence(@Path("id") id: Int): Confirmed<ModerationCaseDetail>

    @DELETE("staff/moderation/cases/{id}/quarantine")
    suspend fun liftQuarantine(@Path("id") id: Int): Confirmed<ModerationCaseDetail>

    @GET("staff/moderation/complaints/{id}")
    suspend fun complaint(@Path("id") id: Int): DataEnvelope<ComplaintDetail>

    @POST("staff/moderation/complaints/{id}/review")
    suspend fun review(@Path("id") id: Int, @Body body: ComplaintReview): Confirmed<ComplaintDetail>

    @GET("staff/moderation/roles")
    suspend fun roles(@Query("page") page: Int): RolePage

    @PUT("staff/moderation/roles/{id}")
    suspend fun changeRole(@Path("id") userId: Int, @Body body: RoleBody): Confirmed<RoleUser>

    @GET("staff/moderation/suspensions")
    suspend fun suspensions(): DataEnvelope<SuspensionList>

    @POST("staff/moderation/suspensions/{id}/release")
    suspend fun release(@Path("id") id: Int): MessageResponse

    /** Statistics for `tage` (7, 30, 90, 365) and the revenue month `monat` ("YYYY-MM", none for the current one). */
    @GET("staff/admin/dashboard")
    suspend fun dashboard(@Query("tage") days: Int, @Query("monat") month: String?): DataEnvelope<AdminDashboard>

    @GET("staff/admin/votes")
    suspend fun votes(): DataEnvelope<List<AdminVote>>

    @POST("staff/admin/votes")
    suspend fun createVote(@Body vote: NewVote): Confirmed<AdminVote>

    @GET("staff/admin/payments")
    suspend fun payments(@Query("suche") search: String?, @Query("status") status: String?, @Query("page") page: Int): PaymentPage

    @POST("staff/admin/payments/{id}/check-payment")
    suspend fun checkPayment(@Path("id") recordId: Int): DataEnvelope<MollieLookup>

    @POST("staff/admin/payments/{id}/check-customer")
    suspend fun checkCustomer(@Path("id") recordId: Int): DataEnvelope<MollieLookup>

    @POST("staff/admin/payments/mollie-search")
    suspend fun searchMollie(@Body body: SearchBody): DataEnvelope<MollieLookup>

    @POST("staff/admin/payments/{id}/sync")
    suspend fun syncPayment(@Path("id") recordId: Int): Confirmed<PaymentRecord>

    @POST("staff/admin/payments/{id}/refund")
    suspend fun refund(@Path("id") recordId: Int, @Body body: RefundBody): Confirmed<PaymentRecord>

    @GET("staff/admin/breaches")
    suspend fun breaches(): BreachRegister

    @POST("staff/admin/breaches")
    suspend fun recordBreach(@Body breach: NewBreach): Confirmed<DataBreach>

    @POST("staff/admin/breaches/{id}/authority-report")
    suspend fun recordBreachReport(@Path("id") id: Int, @Body body: ReferenceBody): Confirmed<DataBreach>

    @POST("staff/admin/breaches/{id}/people-notified")
    suspend fun recordPeopleNotified(@Path("id") id: Int): Confirmed<DataBreach>

    @POST("staff/admin/breaches/{id}/notes")
    suspend fun addBreachNote(@Path("id") id: Int, @Body body: NoteBody): Confirmed<DataBreach>

    @POST("staff/admin/breaches/{id}/close")
    suspend fun closeBreach(@Path("id") id: Int): Confirmed<DataBreach>

    @GET("staff/admin/system")
    suspend fun system(): DataEnvelope<SystemStatus>
}
