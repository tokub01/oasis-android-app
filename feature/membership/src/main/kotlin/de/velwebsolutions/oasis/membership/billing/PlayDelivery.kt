package de.velwebsolutions.oasis.membership.billing

import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import javax.inject.Inject

/**
 * Hands Google Play purchases to the server and acknowledges them once the server unlocked the membership (like iOS'
 * `AppleTransactions.deliver`). A refusal is not acknowledged: Play refunds such a purchase after three days, and until
 * then it is offered again on every start.
 */
class PlayDelivery @Inject constructor(
    private val gateway: BillingGateway,
    private val verifier: PlayPurchaseVerifier
) {
    sealed interface Result {
        /** The server unlocked the membership; the purchase is acknowledged. */
        data class Unlocked(val update: Confirmed<MembershipOverview>) : Result

        /** Paid at Google Play, but the server cannot check Play purchases yet; nothing is acknowledged. */
        data object AwaitingServer : Result

        /** Not ours to deliver: still pending, or none of the membership products. */
        data object Skipped : Result
    }

    /** Throws the server's refusal (`ApiError`). */
    suspend fun deliver(purchase: PlayPurchase): Result {
        val receipt = purchase.receipt()
        if (!purchase.isPurchased || receipt == null) return Result.Skipped
        if (!verifier.isAvailable) return Result.AwaitingServer
        val update = verifier.verify(receipt)
        if (!purchase.isAcknowledged) gateway.acknowledge(purchase)
        return Result.Unlocked(update)
    }
}
