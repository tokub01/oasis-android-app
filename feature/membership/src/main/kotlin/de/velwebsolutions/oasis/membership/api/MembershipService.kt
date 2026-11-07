package de.velwebsolutions.oasis.membership.api

import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.MessageResponse
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseReceipt
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The membership endpoints the app uses (`routes/api/v1/members/membership.php`, names `api.v1.membership.*`). Buying
 * happens through Google Play (PlayBillingGateway); Mollie checkout, gift purchases and voucher codes stay on the web.
 * Invoices and the data export are files and go through `ApiClient.download`.
 */
interface MembershipService {
    @GET("membership")
    suspend fun overview(): DataEnvelope<MembershipOverview>

    /** „Wohin dein Beitrag geht“ (public): the figures, the latest published report and the earlier ones. */
    @GET("transparency")
    suspend fun transparency(): DataEnvelope<Transparency>

    @GET("transparency/reports/{id}")
    suspend fun transparencyReport(@Path("id") id: Int): DataEnvelope<FinanceReport>

    /**
     * Hands a Google Play purchase to the server, which checks it with Google, activates the membership and acknowledges
     * the purchase itself. Idempotent; 422 names why Oasis cannot take it over, 503 means "try again later".
     */
    @POST("membership/google-purchases")
    suspend fun googlePurchase(@Body receipt: PlayPurchaseReceipt): Confirmed<MembershipOverview>

    @GET("membership/payments")
    suspend fun payments(): DataEnvelope<List<MembershipPayment>>

    @GET("membership/cancellation")
    suspend fun cancellation(): DataEnvelope<CancellationDetails>

    @POST("membership/cancellation")
    suspend fun cancel(): Confirmed<MembershipOverview>

    @GET("membership/withdrawal")
    suspend fun withdrawal(): DataEnvelope<WithdrawalDetails>

    @POST("membership/withdrawal")
    suspend fun withdraw(): Confirmed<MembershipOverview>

    // An announced change of the AGB (AGB § 19): the objection and its withdrawal. Both answer with the overview
    // (`terms_change.objected_at` set or null) and a message; 404 `reason: no_announcement` without a running
    // announcement, 422 `reason: already_objected | not_objected`.

    @POST("membership/terms-objection")
    suspend fun objectToTerms(): Confirmed<MembershipOverview>

    @DELETE("membership/terms-objection")
    suspend fun withdrawTermsObjection(): Confirmed<MembershipOverview>

    /**
     * „Beitragsstufe ändern“ of a running Mollie membership (AGB § 5): answers with the overview (the tier changed at
     * once) and a `message`; 422 with `message`/`reason` (`not_running`, `same_tier`) or the field errors, 409 with the
     * store's hint for a store membership (`reason: store`), 503 when Mollie fails.
     */
    @POST("membership/tier")
    suspend fun changeTier(@Body input: TierChangeInput): Confirmed<MembershipOverview>

    // Deine Vorteile: companion places and the supporter mark

    @GET("membership/perks")
    suspend fun perks(): DataEnvelope<MembershipPerks>

    /** 201 with the new place's link; 422 `errors.seat` when none is free. */
    @POST("membership/companion-seats")
    suspend fun createSeat(): MessageResponse

    @DELETE("membership/companion-seats/{id}")
    suspend fun releaseSeat(@Path("id") id: Int): MessageResponse

    @GET("membership/companion-links/{token}")
    suspend fun companionLink(@Path("token") token: String): DataEnvelope<CompanionLinkInfo>

    /** Takes the place; 422 `errors.seat` names why not. */
    @POST("membership/companion-links/{token}")
    suspend fun acceptCompanionLink(@Path("token") token: String): MessageResponse

    /** A companion gives the held place back. */
    @DELETE("membership/companion-seat")
    suspend fun leaveSeat(): MessageResponse

    /** 403 below Mäzen. */
    @PATCH("membership/supporter")
    suspend fun updateSupporter(@Body input: SupporterInput): MessageResponse
}
