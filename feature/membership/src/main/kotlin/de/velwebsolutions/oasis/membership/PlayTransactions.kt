package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.membership.billing.BillingGateway
import de.velwebsolutions.oasis.membership.billing.PlayDelivery
import de.velwebsolutions.oasis.membership.billing.PlayPurchase
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Play purchases that arrive outside the checkout (renewals, other devices, pending payments): hands them to
 * the server and acknowledges them once it unlocked the membership (iOS `AppleTransactions.observe`). While the server
 * cannot check Play purchases nothing is acknowledged (PlayPurchaseVerifier).
 */
@Singleton
class PlayTransactions @Inject constructor(
    private val gateway: BillingGateway,
    private val delivery: PlayDelivery,
    private val events: MembershipEvents
) {
    /** Listens while the member is signed in; called by the tab shell. */
    suspend fun observe() {
        gateway.ownedPurchases().filterNot(PlayPurchase::isAcknowledged).forEach { deliver(it) }
        gateway.purchaseUpdates.collect { deliver(it) }
    }

    private suspend fun deliver(purchase: PlayPurchase) {
        val result = try {
            delivery.deliver(purchase)
        } catch (_: ApiError) {
            // Refused or offline: not acknowledged, so Play offers it again on the next start.
            return
        }
        if (result is PlayDelivery.Result.Unlocked) events.changed()
    }
}
