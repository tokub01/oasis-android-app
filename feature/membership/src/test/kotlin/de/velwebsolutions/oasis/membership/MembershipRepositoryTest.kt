package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.MembershipTier
import de.velwebsolutions.oasis.membership.api.TierChangeInput
import de.velwebsolutions.oasis.membership.billing.PlayPurchaseReceipt
import de.velwebsolutions.oasis.membership.data.TermsObjectionRefused
import de.velwebsolutions.oasis.membership.ui.tierChangeLead
import de.velwebsolutions.oasis.testing.StubTransport.Stub
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MembershipRepositoryTest {
    @Test
    fun `decodes the overview of a member without membership`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.noMembershipOverview))
        val overview = repository.overview()
        assertEquals(MembershipOverview.State.Expired, overview.state)
        assertNull(overview.subscription)
        assertEquals(false, overview.apple?.managedByApple)
        val google = overview.google!!
        assertEquals(true, google.available)
        assertEquals(Fixtures.ACCOUNT_ID, google.accountId)
        assertEquals(Catalogue.ids, google.products.map { it.id })
        assertEquals("oase", google.products[2].tier)
        assertEquals(false, google.managedByGoogle)
        assertEquals("https://play.google.com/store/account/subscriptions", google.manageUrl)
    }

    @Test
    fun `tolerates servers without the apple and google blocks`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.subscriptionOverview))
        assertNull(repository.overview().apple)
        assertNull(repository.overview().google)
    }

    @Test
    fun `a Google Play purchase is posted with its token and product`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "membership/google-purchases", json = Fixtures.confirmed(Fixtures.WELCOME))
        )
        val answer = repository.googlePurchase(PlayPurchaseReceipt("token", Catalogue.OASE_MONTHLY))
        assertEquals(Fixtures.WELCOME, answer.message)
        assertEquals(MembershipOverview.State.Subscription, answer.data.state)
        assertEquals(
            """{"purchase_token":"token","product_id":"${Catalogue.OASE_MONTHLY}"}""",
            transport.lastBody("membership/google-purchases").toString()
        )
    }

    @Test
    fun `decodes a running subscription`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.subscriptionOverview))
        val overview = repository.overview()
        assertEquals(MembershipOverview.State.Subscription, overview.state)
        assertEquals("Oase", overview.subscription?.tierName)
        assertEquals(false, overview.subscription?.onGracePeriod)
        assertTrue(overview.canCancel && overview.canWithdraw && overview.isFoundingMember)
    }

    @Test
    fun `decodes payments and cancellation`() = runTest {
        val (repository, _) = Fixtures.repository(
            Stub("GET", "membership/payments", json = Fixtures.payments),
            Stub("GET", "membership/cancellation", json = Fixtures.cancellation)
        )
        val payment = repository.payments().first()
        assertEquals("OAS-2", payment.invoiceNumber)
        assertEquals("5 €", payment.amountLabel)
        assertEquals("19.10.2026", MembershipFormat.day(payment.paidAt))
        val details = repository.cancellation()
        assertEquals("Mia Mitglied", details.name)
        assertEquals("18.11.2026", MembershipFormat.day(details.effectiveDate))
    }

    @Test
    fun `maps the unavailable cancellation to a conflict`() = runTest {
        val message = "Du hast derzeit kein aktives Abonnement, das gekündigt werden kann."
        val (repository, _) = Fixtures.repository(Stub("GET", "membership/cancellation", status = 409, json = """{"message":"$message"}"""))
        try {
            repository.cancellation()
            fail("expected a conflict")
        } catch (error: ApiError) {
            assertEquals(ApiError.Conflict(message), error)
        }
    }

    @Test
    fun `cancelling reads the confirmation and the new membership`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "membership/cancellation", json = Fixtures.confirmed(Fixtures.CANCELLED))
        )
        val result = repository.cancel()
        assertEquals(Fixtures.CANCELLED, result.message)
        assertEquals(MembershipOverview.State.Subscription, result.data.state)
        assertEquals("POST", transport.requests.last().request.method)
    }

    @Test
    fun `withdrawing reads the withdrawal page and posts the withdrawal`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "membership/withdrawal", json = Fixtures.WITHDRAWAL),
            Stub("POST", "membership/withdrawal", json = Fixtures.confirmed("Widerrufen."))
        )
        assertEquals("Oase", repository.withdrawal().tierName)
        assertEquals("Widerrufen.", repository.withdraw().message)
        assertEquals("membership/withdrawal", transport.requests.last().path)
    }

    @Test
    fun `downloads the invoice and the export as files`() = runTest {
        val html = "<!doctype html><title>Rechnung OAS-2</title>"
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "membership/invoices/2", json = html),
            Stub("GET", "account/export", json = "PK")
        )
        assertEquals(html, repository.invoice(2).decodeToString())
        assertEquals("PK", repository.export().decodeToString())
        assertEquals("/api/v1/account/export", transport.requests.last().request.url.encodedPath)
        assertFalse(transport.requests.any { it.request.header("Authorization") == null })
    }
}

class MembershipFormatTest {
    @Test
    fun `days as the web writes them`() {
        assertEquals("11.11.2026", MembershipFormat.day(Instant.parse("2026-11-11T13:32:15Z")))
        assertEquals("", MembershipFormat.day(null))
        assertEquals("21.12.2026", MembershipFormat.calendarDay(LocalDate.of(2026, 12, 21)))
        assertEquals("", MembershipFormat.calendarDay(null))
    }

    @Test
    fun `moments as the confirmations name them`() {
        assertEquals("06.11.2026 um 14:32 Uhr", MembershipFormat.moment(Instant.parse("2026-11-06T13:32:15Z")))
        assertEquals("summer time", "21.06.2026 um 15:32 Uhr", MembershipFormat.moment(Instant.parse("2026-06-21T13:32:15Z")))
        assertEquals("", MembershipFormat.moment(null))
    }
}

class TermsObjectionRepositoryTest {
    private val alreadyObjected = """{"message":"${Fixtures.ALREADY_OBJECTED}","reason":"already_objected"}"""

    @Test
    fun `decodes an announced change of the AGB and tolerates servers without it`() = runTest {
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.termsChangeOverview))
        val change = repository.overview().termsChange!!
        assertEquals(LocalDate.of(2026, 12, 21), change.effectiveOn)
        assertEquals("Wir fassen § 14 neu.", change.summary)
        assertEquals("Das Gesetz verlangt es.", change.reason)
        assertEquals(Instant.parse("2026-11-01T09:00:00Z"), change.announcedAt)
        assertNull(change.objectedAt)
        assertFalse(change.objected)
        assertEquals("http://localhost/agb", change.url)

        val objected = Fixtures.overview(Fixtures.termsObjectedOverview).termsChange!!
        assertTrue(objected.objected)
        assertEquals("06.11.2026 um 14:32 Uhr", MembershipFormat.moment(objected.objectedAt))

        assertNull("servers before announcements", Fixtures.overview(Fixtures.subscriptionOverview).termsChange)
        val bare = Fixtures.subscriptionOverview.replaceFirst("""{"data":{""", """{"data":{"terms_change":{},""")
        assertEquals("an announcement without its fields", MembershipOverview.TermsChange(), Fixtures.overview(bare).termsChange)
        assertFalse("servers before the day flag", Fixtures.overview(bare).termsChange!!.inEffect)
        assertNull(Fixtures.overview(bare).termsChange!!.appliesToYou)
    }

    @Test
    fun `after the day the objector keeps the change, with the version that governs and a termination once made`() = runTest {
        val before = Fixtures.overview(Fixtures.termsChangeOverview).termsChange!!
        assertEquals(true, before.appliesToYou)
        assertEquals("Unsere AGB ändern sich am 21.12.2026", before.title)
        assertNull("no objection, no standing line", before.standingText)
        assertFalse(before.canWithdrawObjection)
        assertFalse(before.isTerminated)

        val objected = Fixtures.overview(Fixtures.termsObjectedOverview).termsChange!!
        assertEquals(false, objected.appliesToYou)
        assertEquals(
            "Du hast am 06.11.2026 um 14:32 Uhr widersprochen. Für dich gilt die bisherige Fassung weiter; wir melden uns, falls wir " +
                "deshalb kündigen müssen (AGB § 7).",
            objected.standingText
        )
        assertTrue(objected.canWithdrawObjection)

        val inEffect = Fixtures.overview(Fixtures.termsInEffectObjectedOverview).termsChange!!
        assertTrue(inEffect.inEffect)
        assertEquals("Unsere AGB haben sich am 21.12.2026 geändert", inEffect.title)
        assertEquals(
            "Du hast am 06.11.2026 um 14:32 Uhr widersprochen. Für dich gilt die bisherige Fassung weiter: die Fassung, der du " +
                "zugestimmt hast (unten unter „Dein Vertragstext“). Wir melden uns, falls wir deshalb kündigen müssen (AGB § 7).",
            inEffect.standingText
        )
        assertFalse("no way back after the day", inEffect.canWithdrawObjection)

        val terminated = Fixtures.overview(Fixtures.termsTerminatedOverview).termsChange!!
        assertEquals(Instant.parse("2027-01-31T23:00:00Z"), terminated.terminationEndsAt)
        assertTrue(terminated.isTerminated)
        assertEquals(
            "Du hast am 06.11.2026 um 14:32 Uhr widersprochen. Für dich gilt die bisherige Fassung weiter – bis zum Ende deines " +
                "Vertrags am 01.02.2027, den wir deshalb nach AGB § 7 gekündigt haben (Begründung per E-Mail).",
            terminated.standingText
        )
        assertFalse("nothing to take back once terminated", terminated.canWithdrawObjection)
        assertEquals(
            "the termination's wording wins after the day too",
            terminated.standingText,
            terminated.copy(inEffect = true).standingText
        )

        assertNull("servers before the contract confirmation", Fixtures.overview(Fixtures.subscriptionOverview).termsAcceptedAt)
        assertNull(Fixtures.overview(Fixtures.subscriptionOverview).termsAcceptedMoment)
        val accepted = Fixtures.overview(Fixtures.termsAcceptedOverview)
        assertEquals(Instant.parse("2026-10-18T15:39:37Z"), accepted.termsAcceptedAt)
        assertEquals("18.10.2026 um 17:39 Uhr", accepted.termsAcceptedMoment)
    }

    @Test
    fun `objecting posts and taking the objection back deletes, each with the server's word`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "membership/terms-objection", json = Fixtures.confirmed(Fixtures.OBJECTED, Fixtures.termsObjectedOverview)),
            Stub(
                "DELETE",
                "membership/terms-objection",
                json = Fixtures.confirmed(Fixtures.OBJECTION_WITHDRAWN, Fixtures.termsChangeOverview)
            )
        )
        val objected = repository.objectToTerms()
        assertEquals(Fixtures.OBJECTED, objected.message)
        assertTrue(objected.data.termsChange!!.objected)
        assertEquals("POST", transport.requests.last().request.method)
        assertEquals("membership/terms-objection", transport.requests.last().path)

        val withdrawn = repository.withdrawTermsObjection()
        assertEquals(Fixtures.OBJECTION_WITHDRAWN, withdrawn.message)
        assertFalse(withdrawn.data.termsChange!!.objected)
        assertEquals("DELETE", transport.requests.last().request.method)
        assertEquals("membership/terms-objection", transport.requests.last().path)
    }

    @Test
    fun `a refused objection names the server's reason`() = runTest {
        val notObjected = "Du hast dieser Änderung nicht widersprochen, es gibt also nichts zurückzunehmen."
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "membership/terms-objection", status = 422, json = alreadyObjected),
            Stub("DELETE", "membership/terms-objection", status = 422, json = """{"message":"$notObjected","reason":"not_objected"}""")
        )
        val objected = assertRefused { repository.objectToTerms() }
        assertEquals(TermsObjectionRefused.ALREADY_OBJECTED, objected.reason)
        assertEquals(Fixtures.ALREADY_OBJECTED, objected.message)
        val withdrawn = assertRefused { repository.withdrawTermsObjection() }
        assertEquals(TermsObjectionRefused.NOT_OBJECTED, withdrawn.reason)
        assertEquals(notObjected, withdrawn.message)

        val gone = "Derzeit ist keine Änderung unserer AGB angekündigt, der du widersprechen könntest."
        transport.add("POST", "membership/terms-objection", status = 404, json = """{"message":"$gone","reason":"no_announcement"}""")
        val noAnnouncement = assertRefused { repository.objectToTerms() }
        assertEquals(TermsObjectionRefused.NO_ANNOUNCEMENT, noAnnouncement.reason)
        assertEquals(gone, noAnnouncement.message)

        transport.add("POST", "membership/terms-objection", status = 422, json = """{"message":"Ungültig."}""")
        assertNull("a 422 without a code", assertRefused { repository.objectToTerms() }.reason)

        transport.add("POST", "membership/terms-objection", status = 503, json = """{"message":"Später."}""")
        val failure = runCatching { repository.objectToTerms() }.exceptionOrNull()
        assertEquals("other failures stay what they are", ApiError.Server(503, "Später."), failure)
    }

    private suspend fun assertRefused(block: suspend () -> Unit): TermsObjectionRefused {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is TermsObjectionRefused)
        return failure as TermsObjectionRefused
    }
}

class TierChangeRepositoryTest {
    private val subscription = Fixtures.overview(Fixtures.subscriptionOverview)

    @Test
    fun `only a running, uncancelled web membership changes its tier here`() {
        assertTrue(subscription.canChangeTier)
        assertEquals("oase", subscription.subscription?.tier)
        assertEquals(Instant.parse("2026-11-18T15:39:37Z"), subscription.subscription?.paidUntil)
        assertFalse("no membership", Fixtures.overview(Fixtures.noMembershipOverview).canChangeTier)
        assertFalse("a state without its subscription", subscription.copy(subscription = null).canChangeTier)
        assertFalse("cancelled", subscription.copy(subscription = subscription.subscription?.copy(onGracePeriod = true)).canChangeTier)
        assertFalse("the server says no", subscription.copy(canCancel = false).canChangeTier)
        assertFalse("managed by Apple", subscription.copy(apple = MembershipOverview.Apple(managedByApple = true)).canChangeTier)
        assertFalse("managed by Google", subscription.copy(google = MembershipOverview.Google(managedByGoogle = true)).canChangeTier)
        assertTrue(
            "store blocks that do not manage it",
            subscription.copy(apple = MembershipOverview.Apple(), google = MembershipOverview.Google()).canChangeTier
        )
        val older = Fixtures.subscriptionOverview.replace(
            """"tier":"oase",""",
            ""
        ).replace(""""paid_until":"2026-11-18T15:39:37.000000Z"""", """"paid_until":null""")
        assertNull("servers before the tier change", Fixtures.overview(older).subscription?.tier)
        assertNull(Fixtures.overview(older).subscription?.paidUntil)
    }

    @Test
    fun `the tiers carry the web's labels and the lead names the current tier and the next charge`() {
        assertEquals(listOf("solidar", "oase", "maezen"), MembershipTier.all.map { it.key })
        assertEquals(
            listOf("Solidar – 3\u00A0€ monatlich", "Oase – 5\u00A0€ monatlich", "Mäzen – ab 12\u00A0€ monatlich"),
            MembershipTier.all.map { it.label }
        )
        assertEquals(12, MembershipTier.minimumMaezen)
        assertEquals(
            "Derzeit: Stufe „Oase“. Die neue Stufe gilt ab sofort, der neue Beitrag ab deiner nächsten Abbuchung am 18.11.2026. " +
                "Wechselst du in eine kleinere Stufe, bleibt Bestehendes erhalten; Neues richtet sich nach der neuen Stufe (AGB § 5).",
            tierChangeLead(subscription)
        )
        assertEquals(
            "Derzeit: Stufe „Oase“. Die neue Stufe gilt ab sofort, der neue Beitrag ab deiner nächsten Abbuchung. " +
                "Wechselst du in eine kleinere Stufe, bleibt Bestehendes erhalten; Neues richtet sich nach der neuen Stufe (AGB § 5).",
            tierChangeLead(subscription.copy(subscription = subscription.subscription?.copy(paidUntil = null)))
        )
        assertEquals(
            "Die neue Stufe gilt ab sofort, der neue Beitrag ab deiner nächsten Abbuchung. " +
                "Wechselst du in eine kleinere Stufe, bleibt Bestehendes erhalten; Neues richtet sich nach der neuen Stufe (AGB § 5).",
            tierChangeLead(subscription.copy(subscription = null))
        )
    }

    @Test
    fun `the tier change posts the tier, the Maezen amount only for Maezen, and hands refusals on as they are`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "membership/tier", json = Fixtures.confirmed(Fixtures.TIER_CHANGED, Fixtures.maezenOverview))
        )
        val changed = repository.changeTier(TierChangeInput("maezen", 15))
        assertEquals(Fixtures.TIER_CHANGED, changed.message)
        assertEquals("maezen", changed.data.subscription?.tier)
        assertEquals(JsonPrimitive("maezen"), transport.lastBody("membership/tier")!!.jsonObject["tier"])
        assertEquals(JsonPrimitive(15), transport.lastBody("membership/tier")!!.jsonObject["amount"])

        repository.changeTier(TierChangeInput("solidar"))
        assertNull("no amount for the fixed tiers", transport.lastBody("membership/tier")!!.jsonObject["amount"])

        transport.add("POST", "membership/tier", status = 422, json = """{"message":"${Fixtures.SAME_TIER}","reason":"same_tier"}""")
        val same = runCatching { repository.changeTier(TierChangeInput("oase")) }.exceptionOrNull()
        assertEquals(ApiError.Validation(Fixtures.SAME_TIER, emptyMap(), "same_tier"), same)

        transport.add("POST", "membership/tier", status = 409, json = """{"message":"Im Store.","reason":"store"}""")
        assertEquals(ApiError.Conflict("Im Store."), runCatching { repository.changeTier(TierChangeInput("oase")) }.exceptionOrNull())
    }
}
