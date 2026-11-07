package de.velwebsolutions.oasis.membership.data

import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.membership.api.CancellationDetails
import de.velwebsolutions.oasis.membership.api.FinanceReport
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.MembershipPayment
import de.velwebsolutions.oasis.membership.api.MembershipPerks
import de.velwebsolutions.oasis.membership.api.MembershipService
import de.velwebsolutions.oasis.membership.api.SupporterInput
import de.velwebsolutions.oasis.membership.api.TierChangeInput
import de.velwebsolutions.oasis.membership.api.Transparency
import de.velwebsolutions.oasis.membership.api.WithdrawalDetails
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseReceipt
import javax.inject.Inject

/** The membership of the signed-in member: status, invoices and the pages that end the contract. */
interface MembershipRepository {
    suspend fun overview(): MembershipOverview

    /** „Wohin dein Beitrag geht“: the figures, the latest published report and the earlier ones. */
    suspend fun transparency(): Transparency

    /** An earlier published report. */
    suspend fun transparencyReport(id: Int): FinanceReport

    suspend fun payments(): List<MembershipPayment>

    /** Hands a Google Play purchase to the server (`POST membership/google-purchases`); throws its refusal as `ApiError`. */
    suspend fun googlePurchase(receipt: PlayPurchaseReceipt): Confirmed<MembershipOverview>

    /** The invoice of a payment as a self-contained HTML document (also for /app/mitgliedschaft/rechnungen/{id}). */
    suspend fun invoice(id: Int): ByteArray

    suspend fun cancellation(): CancellationDetails

    suspend fun cancel(): Confirmed<MembershipOverview>

    suspend fun withdrawal(): WithdrawalDetails

    suspend fun withdraw(): Confirmed<MembershipOverview>

    /**
     * „Ich widerspreche dieser Änderung“ (`POST membership/terms-objection`, AGB § 19): the overview with the objection
     * and the server's confirmation; throws [TermsObjectionRefused] when the server refuses, other failures as `ApiError`.
     */
    suspend fun objectToTerms(): Confirmed<MembershipOverview>

    /** „Widerspruch zurücknehmen“ (`DELETE membership/terms-objection`); fails like [objectToTerms]. */
    suspend fun withdrawTermsObjection(): Confirmed<MembershipOverview>

    /**
     * „Stufe ändern“ (`POST membership/tier`, AGB § 5): the overview with the new tier and the server's confirmation;
     * the refusals come as `ApiError` – a `Validation` with field errors or with a `reason` (`not_running`,
     * `same_tier`), a `Conflict` with the store's hint, a `Server` 503 when Mollie fails.
     */
    suspend fun changeTier(input: TierChangeInput): Confirmed<MembershipOverview>

    /**
     * The data export ZIP (`oasis-daten.zip`) – the same endpoint as „Daten herunterladen“ in Einstellungen, offered on
     * the cancel and withdraw pages like the web.
     */
    suspend fun export(): ByteArray

    /** Deine Vorteile: the tier's lines, the companion places and the supporter choices. */
    suspend fun perks(): MembershipPerks

    /** A link for a free companion place; returns the confirmation (422 `seat` when none is free). */
    suspend fun createSeat(): String

    /** Ends one of the own companion places. */
    suspend fun releaseSeat(id: Int): String

    /** Who offers the place behind a companion link, null when it no longer works. */
    suspend fun companionSponsor(token: String): String?

    /** Takes the place behind a companion link; returns the welcome. */
    suspend fun acceptCompanionLink(token: String): String

    /** Gives the held companion place back. */
    suspend fun leaveSeat(): String

    /** Switches the supporter mark or the thank-you list. */
    suspend fun updateSupporter(input: SupporterInput): String
}

class DefaultMembershipRepository @Inject constructor(private val client: ApiClient) : MembershipRepository {
    private val service = client.create<MembershipService>()

    override suspend fun overview() = service.overview().data

    override suspend fun transparency() = service.transparency().data

    override suspend fun transparencyReport(id: Int) = service.transparencyReport(id).data

    override suspend fun payments() = service.payments().data

    override suspend fun googlePurchase(receipt: PlayPurchaseReceipt) = service.googlePurchase(receipt)

    override suspend fun invoice(id: Int) = client.download(client.url("membership/invoices/$id").toString())

    override suspend fun cancellation() = service.cancellation().data

    override suspend fun cancel() = service.cancel()

    override suspend fun withdrawal() = service.withdrawal().data

    override suspend fun withdraw() = service.withdraw()

    override suspend fun objectToTerms() = termsObjection(service::objectToTerms)

    override suspend fun withdrawTermsObjection() = termsObjection(service::withdrawTermsObjection)

    override suspend fun changeTier(input: TierChangeInput) = service.changeTier(input)

    /**
     * Runs an objection call and turns the server's refusal into [TermsObjectionRefused]: the 404 of a change that is no
     * longer announced (the server's `no_announcement`, whose message a 404 does not carry) and the 422 with its `reason`.
     */
    private suspend fun termsObjection(call: suspend () -> Confirmed<MembershipOverview>): Confirmed<MembershipOverview> = try {
        call()
    } catch (error: ApiError.NotFound) {
        throw TermsObjectionRefused(
            TermsObjectionRefused.NO_ANNOUNCEMENT,
            "Derzeit ist keine Änderung unserer AGB angekündigt, der du widersprechen könntest."
        )
    } catch (error: ApiError.Validation) {
        throw TermsObjectionRefused(error.reason, error.summary)
    }

    override suspend fun export() = client.download(client.url("account/export").toString())

    override suspend fun perks() = service.perks().data

    override suspend fun createSeat() = service.createSeat().message

    override suspend fun releaseSeat(id: Int) = service.releaseSeat(id).message

    override suspend fun companionSponsor(token: String) = service.companionLink(token).data.sponsor

    override suspend fun acceptCompanionLink(token: String) = service.acceptCompanionLink(token).message

    override suspend fun leaveSeat() = service.leaveSeat().message

    override suspend fun updateSupporter(input: SupporterInput) = service.updateSupporter(input).message
}

/**
 * The server refused the objection or its withdrawal (AGB § 19): [message] says why in German, [reason] is its fixed code
 * – [NO_ANNOUNCEMENT] (404), [ALREADY_OBJECTED] or [NOT_OBJECTED] (422); null for a 422 without one.
 */
class TermsObjectionRefused(val reason: String?, override val message: String) : Exception(message) {
    companion object {
        const val NO_ANNOUNCEMENT = "no_announcement"
        const val ALREADY_OBJECTED = "already_objected"
        const val NOT_OBJECTED = "not_objected"
    }
}
