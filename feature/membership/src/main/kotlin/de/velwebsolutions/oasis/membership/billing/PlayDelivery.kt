package de.velwebsolutions.oasis.membership.billing

import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.data.MembershipRepository
import javax.inject.Inject

/**
 * Hands Google Play purchases to the server (like iOS' `AppleTransactions.deliver`): `POST membership/google-purchases`
 * checks the purchase with Google, activates the membership and acknowledges the purchase itself, so the app never
 * does. A refusal (422) or an unreachable check (503) leaves the purchase unacknowledged at Play; it is sent again on
 * the next start and with "Käufe wiederherstellen".
 */
class PlayDelivery @Inject constructor(private val repository: MembershipRepository) {
    /** What happened to a purchase handed to the server. */
    sealed interface Result {
        /** The server took the purchase over and unlocked the membership. */
        data class Unlocked(val update: Confirmed<MembershipOverview>) : Result

        /** Not for the server (yet): the payment is still pending, or the purchase carries no product. */
        data object Skipped : Result
    }

    /** Throws the server's refusal (`ApiError`: 422 with its reason, 503 for "try again later"). */
    suspend fun deliver(purchase: PlayPurchase): Result {
        val receipt = purchase.receipt()
        if (!purchase.isPurchased || receipt == null) return Result.Skipped
        return Result.Unlocked(repository.googlePurchase(receipt))
    }
}
