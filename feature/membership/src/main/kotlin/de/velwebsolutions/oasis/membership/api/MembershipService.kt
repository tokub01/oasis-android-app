package de.velwebsolutions.oasis.membership.api

import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.DataEnvelope
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * The membership endpoints the app uses (`routes/api/v1/members/membership.php`, names `api.v1.membership.*`). Buying
 * happens through Google Play (PlayBillingGateway); Mollie checkout, gift purchases and voucher codes stay on the web.
 * Invoices and the data export are files and go through `ApiClient.download`.
 */
interface MembershipService {
    @GET("membership")
    suspend fun overview(): DataEnvelope<MembershipOverview>

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
}
