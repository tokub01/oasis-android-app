package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.membership.billing.BillingGateway
import de.velwebsolutions.oasis.membership.billing.PlayDelivery
import de.velwebsolutions.oasis.membership.billing.PlayPurchase
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Play purchases that arrive outside the checkout (renewals, other devices, pending payments that went
 * through, purchases whose delivery was interrupted): hands them to the server (iOS `AppleTransactions.observe`).
 * Once the server unlocked the membership the signed-in member is reloaded, so the app opens with paid access.
 */
@Singleton
class PlayTransactions @Inject constructor(
    private val gateway: BillingGateway,
    private val delivery: PlayDelivery,
    private val events: MembershipEvents,
    private val session: Session
) {
    /** Listens while the member is signed in; called by the tab shell. */
    suspend fun observe() {
        // The server acknowledges what it took over, so open (unacknowledged) purchases are the ones still to send.
        gateway.ownedPurchases().filterNot(PlayPurchase::isAcknowledged).forEach { deliver(it) }
        gateway.purchaseUpdates.collect { deliver(it) }
    }

    private suspend fun deliver(purchase: PlayPurchase) {
        val result = try {
            delivery.deliver(purchase)
        } catch (_: ApiError) {
            // Refused (422) or not checkable right now (503): it stays open at Play and goes again on the next start.
            return
        }
        if (result is PlayDelivery.Result.Unlocked) {
            events.changed()
            session.refreshMe()
        }
    }
}
