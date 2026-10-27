package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.membership.api.CancellationDetails
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.MembershipPayment
import de.velwebsolutions.oasis.membership.api.MembershipPerks
import de.velwebsolutions.oasis.membership.api.SupporterInput
import de.velwebsolutions.oasis.membership.api.WithdrawalDetails
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseReceipt
import de.velwebsolutions.oasis.membership.data.MembershipRepository
import kotlinx.coroutines.CompletableDeferred

/**
 * The membership API without a network, for view-model tests: every call answers synchronously with what the test
 * set, or throws the [ApiError] it set; [gate] holds the invoice download until the test completes it.
 */
class FakeMembershipRepository : MembershipRepository {
    var overview: Result<MembershipOverview> = Result.success(Fixtures.overview(Fixtures.subscriptionOverview))
    var payments: Result<List<MembershipPayment>> = Result.success(listOf(MembershipPayment(2, "OAS-2", null, "5 €")))
    var invoice: Result<ByteArray> = Result.success("<html>Rechnung</html>".toByteArray())
    var cancellation: Result<CancellationDetails> = Result.success(CancellationDetails("Oase", "Mia Mitglied", "mia@example.test"))
    var withdrawal: Result<WithdrawalDetails> = Result.success(WithdrawalDetails("Oase", "Mia Mitglied", "mia@example.test"))
    var end: Result<Confirmed<MembershipOverview>> = Result.success(
        Confirmed(Fixtures.overview(Fixtures.subscriptionOverview), "Erledigt.")
    )
    var export: Result<ByteArray> = Result.success("PK".toByteArray())
    var googlePurchase: Result<Confirmed<MembershipOverview>> = Result.success(
        Confirmed(Fixtures.overview(Fixtures.subscriptionOverview), Fixtures.WELCOME)
    )
    val receipts = mutableListOf<PlayPurchaseReceipt>()
    var gate: CompletableDeferred<Unit>? = null
    var overviewCalls = 0
        private set

    override suspend fun overview(): MembershipOverview {
        overviewCalls++
        return overview.getOrThrow()
    }

    override suspend fun payments() = payments.getOrThrow()

    override suspend fun googlePurchase(receipt: PlayPurchaseReceipt): Confirmed<MembershipOverview> {
        receipts += receipt
        return googlePurchase.getOrThrow()
    }

    override suspend fun invoice(id: Int): ByteArray {
        gate?.await()
        return invoice.getOrThrow()
    }

    override suspend fun cancellation() = cancellation.getOrThrow()

    override suspend fun cancel() = end.getOrThrow()

    override suspend fun withdrawal() = withdrawal.getOrThrow()

    override suspend fun withdraw() = end.getOrThrow()

    override suspend fun export() = export.getOrThrow()

    var perks: Result<MembershipPerks> = Result.success(Fixtures.perks())
    var companionSponsor: Result<String?> = Result.success("Mia Mäzen")
    var action: Result<String> = Result.success("Gespeichert.")
    val calls = mutableListOf<String>()

    override suspend fun perks() = perks.getOrThrow()

    override suspend fun createSeat() = action.also { calls += "createSeat" }.getOrThrow()

    override suspend fun releaseSeat(id: Int) = action.also { calls += "releaseSeat $id" }.getOrThrow()

    override suspend fun companionSponsor(token: String) = companionSponsor.getOrThrow()

    override suspend fun acceptCompanionLink(token: String) = action.also { calls += "accept $token" }.getOrThrow()

    override suspend fun leaveSeat() = action.also { calls += "leaveSeat" }.getOrThrow()

    override suspend fun updateSupporter(input: SupporterInput) = action.also { calls += "supporter $input" }.getOrThrow()

    companion object {
        /** A server error as the API client reports it. */
        fun failure(message: String = "Server Error") = Result.failure<Nothing>(ApiError.Server(500, message))
    }
}
