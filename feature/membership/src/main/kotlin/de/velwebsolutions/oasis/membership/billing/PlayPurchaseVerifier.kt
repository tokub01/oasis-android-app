package de.velwebsolutions.oasis.membership.billing

import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import javax.inject.Inject

/**
 * The server check of a Google Play purchase – the counterpart of iOS' `POST membership/apple-transactions`: the
 * server verifies the purchase token with the Google Play Developer API, ties it to the member and answers with the
 * unlocked membership (`{message, data: MembershipResource}`). Only after that the app acknowledges the purchase.
 */
interface PlayPurchaseVerifier {
    /** False while the server cannot check Google Play purchases; then the checkout sells nothing. */
    val isAvailable: Boolean

    /** Hands [receipt] to the server; throws its refusal as `ApiError`. */
    suspend fun verify(receipt: PlayPurchaseReceipt): Confirmed<MembershipOverview>
}

/** Thrown when a purchase reaches the app before the server can check Google Play purchases. */
class PlayVerificationUnavailable : IllegalStateException("Google Play purchases cannot be verified by the server yet.")

/**
 * The verifier until the backend has an endpoint for Google Play purchases (routes/api/v1/members/membership.php only
 * knows `apple-transactions`). It refuses everything, so no purchase is acknowledged and none is shown as a success;
 * Google Play refunds unacknowledged purchases after three days.
 *
 * To switch Play purchases on, add a Retrofit call for the new endpoint that posts a [PlayPurchaseReceipt]
 * (`purchase_token`, `product_id`) and returns `Confirmed<MembershipOverview>`, implement this interface with it
 * (`isAvailable = true`) and bind it in MembershipModule instead of this class.
 */
class ServerPendingPlayPurchaseVerifier @Inject constructor() : PlayPurchaseVerifier {
    override val isAvailable: Boolean = false

    override suspend fun verify(receipt: PlayPurchaseReceipt): Confirmed<MembershipOverview> = throw PlayVerificationUnavailable()
}
