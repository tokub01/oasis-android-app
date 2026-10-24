package de.velwebsolutions.oasis.membership.data

import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.membership.api.CancellationDetails
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.MembershipPayment
import de.velwebsolutions.oasis.membership.api.MembershipService
import de.velwebsolutions.oasis.membership.api.WithdrawalDetails
import javax.inject.Inject

/** The membership of the signed-in member: status, invoices and the pages that end the contract. */
interface MembershipRepository {
    suspend fun overview(): MembershipOverview

    suspend fun payments(): List<MembershipPayment>

    /** The invoice of a payment as a self-contained HTML document (also for /app/mitgliedschaft/rechnungen/{id}). */
    suspend fun invoice(id: Int): ByteArray

    suspend fun cancellation(): CancellationDetails

    suspend fun cancel(): Confirmed<MembershipOverview>

    suspend fun withdrawal(): WithdrawalDetails

    suspend fun withdraw(): Confirmed<MembershipOverview>

    /**
     * The data export ZIP (`oasis-daten.zip`) – the same endpoint as „Daten herunterladen“ in Einstellungen, offered on
     * the cancel and withdraw pages like the web.
     */
    suspend fun export(): ByteArray
}

class DefaultMembershipRepository @Inject constructor(private val client: ApiClient) : MembershipRepository {
    private val service = client.create<MembershipService>()

    override suspend fun overview() = service.overview().data

    override suspend fun payments() = service.payments().data

    override suspend fun invoice(id: Int) = client.download(client.url("membership/invoices/$id").toString())

    override suspend fun cancellation() = service.cancellation().data

    override suspend fun cancel() = service.cancel()

    override suspend fun withdrawal() = service.withdrawal().data

    override suspend fun withdraw() = service.withdraw()

    override suspend fun export() = client.download(client.url("account/export").toString())
}
