package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.data.MembershipFiles
import de.velwebsolutions.oasis.membership.ui.CancelViewModel
import de.velwebsolutions.oasis.membership.ui.OverviewViewModel
import de.velwebsolutions.oasis.membership.ui.PortalViewModel
import de.velwebsolutions.oasis.membership.ui.WithdrawViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport.Stub
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MembershipViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val files = MembershipFiles(Files.createTempDirectory("membership").toFile())

    @Test
    fun `overview shows another screen's outcome and reloads`() = runTest {
        val (repository, transport) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.subscriptionOverview))
        val events = MembershipEvents()
        val viewModel = OverviewViewModel(repository, events)
        viewModel.load()
        assertEquals(MembershipOverview.State.Subscription, viewModel.state.value.overview.valueOrNull?.state)
        val loads = transport.requests.size

        events.changed(message = Fixtures.CANCELLED)
        viewModel.load()

        assertEquals(Fixtures.CANCELLED, viewModel.state.value.message)
        assertTrue(transport.requests.size > loads)
        assertNull(events.take().message)
    }

    @Test
    fun `returning from Apple's subscription page reloads the overview`() = runTest {
        val (repository, transport) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.subscriptionOverview))
        val events = MembershipEvents()
        val viewModel = OverviewViewModel(repository, events)
        viewModel.load()
        val loads = transport.requests.size
        val version = events.version.value

        viewModel.subscriptionManaged()

        assertEquals(version + 1, events.version.value)
        // The reload goes out on OkHttp's thread; wait for it in real time.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (transport.requests.size <= loads) delay(10) } }
    }

    @Test
    fun `a failed refresh keeps the overview and says why`() = runTest {
        val (repository, transport) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.noMembershipOverview))
        val viewModel = OverviewViewModel(repository, MembershipEvents())
        viewModel.load()
        transport.add("GET", "membership", status = 500, json = """{"message":"Server Error"}""")
        viewModel.load()
        assertEquals(MembershipOverview.State.Expired, viewModel.state.value.overview.valueOrNull?.state)
        assertEquals("Server Error", viewModel.state.value.error)
    }

    @Test
    fun `objecting to the AGB change and taking it back show the server's answer and its state`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "membership", json = Fixtures.termsChangeOverview),
            Stub("POST", "membership/terms-objection", json = Fixtures.confirmed(Fixtures.OBJECTED, Fixtures.termsObjectedOverview)),
            Stub(
                "DELETE",
                "membership/terms-objection",
                json = Fixtures.confirmed(Fixtures.OBJECTION_WITHDRAWN, Fixtures.termsChangeOverview)
            )
        )
        val viewModel = OverviewViewModel(repository, MembershipEvents())
        viewModel.load()
        assertEquals(false, viewModel.state.value.overview.valueOrNull?.termsChange?.objected)

        viewModel.objectToTerms()
        assertEquals(true, viewModel.state.value.overview.valueOrNull?.termsChange?.objected)
        assertEquals(Fixtures.OBJECTED, viewModel.state.value.message)
        assertEquals(false, viewModel.state.value.termsBusy)

        viewModel.withdrawTermsObjection()
        assertEquals(false, viewModel.state.value.overview.valueOrNull?.termsChange?.objected)
        assertEquals(Fixtures.OBJECTION_WITHDRAWN, viewModel.state.value.message)
        assertEquals(
            listOf("POST", "DELETE"),
            transport.requests.filter { it.path == "membership/terms-objection" }.map { it.request.method }
        )
    }

    @Test
    fun `a refused objection is a toast and reloads what the server knows`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "membership", json = Fixtures.termsChangeOverview),
            Stub(
                "POST",
                "membership/terms-objection",
                status = 422,
                json = """{"message":"${Fixtures.ALREADY_OBJECTED}","reason":"already_objected"}"""
            ),
            Stub("DELETE", "membership/terms-objection", status = 500, json = """{"message":"Server Error"}""")
        )
        val viewModel = OverviewViewModel(repository, MembershipEvents())
        viewModel.load()
        transport.add("GET", "membership", json = Fixtures.termsObjectedOverview)

        viewModel.objectToTerms()
        assertEquals(Fixtures.ALREADY_OBJECTED, viewModel.state.value.error)
        assertEquals("reloaded", true, viewModel.state.value.overview.valueOrNull?.termsChange?.objected)
        assertNull(viewModel.state.value.message)

        viewModel.withdrawTermsObjection()
        assertEquals("other failures as usual", "Server Error", viewModel.state.value.error)
        assertEquals(false, viewModel.state.value.termsBusy)
    }

    @Test
    fun `the tier change starts at the current tier, sends the choice and shows the server's answer`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "membership", json = Fixtures.subscriptionOverview),
            Stub("POST", "membership/tier", json = Fixtures.confirmed(Fixtures.TIER_CHANGED, Fixtures.maezenOverview))
        )
        val viewModel = OverviewViewModel(repository, MembershipEvents())
        assertEquals("", viewModel.state.value.tier)
        viewModel.load()
        assertEquals("the current tier once loaded", "oase", viewModel.state.value.tier)
        assertFalse(viewModel.state.value.asksForAmount)

        viewModel.setTier("maezen")
        assertTrue(viewModel.state.value.asksForAmount)
        viewModel.setAmount(" 15 ")
        viewModel.changeTier()
        assertEquals(JsonPrimitive("maezen"), transport.lastBody("membership/tier")!!.jsonObject["tier"])
        assertEquals(JsonPrimitive(15), transport.lastBody("membership/tier")!!.jsonObject["amount"])
        assertEquals("maezen", viewModel.state.value.overview.valueOrNull?.subscription?.tier)
        assertEquals(Fixtures.TIER_CHANGED, viewModel.state.value.message)
        assertEquals("", viewModel.state.value.amount)
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.tierBusy)
        viewModel.load()
        assertEquals("the choice stays across reloads", "maezen", viewModel.state.value.tier)

        // The fixed tiers send no amount, whatever was typed; what is not a number goes as missing.
        viewModel.setTier("solidar")
        viewModel.setAmount("abc")
        viewModel.changeTier()
        assertNull(transport.lastBody("membership/tier")!!.jsonObject["amount"])
        viewModel.setTier("maezen")
        viewModel.changeTier()
        assertNull(transport.lastBody("membership/tier")!!.jsonObject["amount"])
    }

    @Test
    fun `without a tier from the server the choice stays open`() = runTest {
        val noTier = Fixtures.subscriptionOverview.replace(""""tier":"oase",""", "")
        val (repository, _) = Fixtures.repository(Stub("GET", "membership", json = noTier))
        val viewModel = OverviewViewModel(repository, MembershipEvents())
        viewModel.load()
        assertEquals("", viewModel.state.value.tier)
        val (noMembership, _) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.noMembershipOverview))
        val expired = OverviewViewModel(noMembership, MembershipEvents())
        expired.load()
        assertEquals("", expired.state.value.tier)
    }

    @Test
    fun `a refused tier change stays at its fields, reloads for a refusal of the rules, and shows the store and Mollie as they are`() =
        runTest {
            val (repository, transport) = Fixtures.repository(
                Stub("GET", "membership", json = Fixtures.subscriptionOverview),
                Stub(
                    "POST",
                    "membership/tier",
                    status = 422,
                    json = """{"message":"Der Beitrag liegt unter dem Mindestbeitrag.",""" +
                        """"errors":{"amount":["Der Beitrag liegt unter dem Mindestbeitrag."]}}"""
                )
            )
            val viewModel = OverviewViewModel(repository, MembershipEvents())
            viewModel.load()
            viewModel.setTier("maezen")
            viewModel.setAmount("3")
            viewModel.changeTier()
            assertEquals(mapOf("amount" to "Der Beitrag liegt unter dem Mindestbeitrag."), viewModel.state.value.tierErrors)
            assertNull("field errors are no toast", viewModel.state.value.error)
            assertEquals("what was typed stays", "3", viewModel.state.value.amount)
            viewModel.setAmount("12")
            assertEquals("typing clears the field's message", emptyMap<String, String>(), viewModel.state.value.tierErrors)
            viewModel.setAmount("3")
            viewModel.changeTier()
            viewModel.setTier("oase")
            assertEquals(
                "another field's message stays",
                mapOf("amount" to "Der Beitrag liegt unter dem Mindestbeitrag."),
                viewModel.state.value.tierErrors
            )

            val loads = transport.requests.count { it.path == "membership" }
            transport.add("GET", "membership", json = Fixtures.noMembershipOverview)
            transport.add(
                "POST",
                "membership/tier",
                status = 422,
                json = """{"message":"${Fixtures.NOT_RUNNING}","reason":"not_running"}"""
            )
            viewModel.changeTier()
            assertEquals(Fixtures.NOT_RUNNING, viewModel.state.value.error)
            assertEquals("reloaded", loads + 1, transport.requests.count { it.path == "membership" })
            assertEquals(MembershipOverview.State.Expired, viewModel.state.value.overview.valueOrNull?.state)
            assertEquals("field errors were cleared", emptyMap<String, String>(), viewModel.state.value.tierErrors)

            transport.add("POST", "membership/tier", status = 409, json = """{"message":"Im Store.","reason":"store"}""")
            viewModel.changeTier()
            assertEquals("Im Store.", viewModel.state.value.error)

            transport.add("POST", "membership/tier", status = 503, json = """{"message":"Mollie nicht erreichbar."}""")
            viewModel.changeTier()
            assertEquals("Mollie nicht erreichbar.", viewModel.state.value.error)
            assertFalse(viewModel.state.value.tierBusy)
        }

    @Test
    fun `cancellation hands the outcome to the overview`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "membership/cancellation", json = Fixtures.cancellation),
            Stub("POST", "membership/cancellation", json = Fixtures.confirmed(Fixtures.CANCELLED))
        )
        val events = MembershipEvents()
        val viewModel = CancelViewModel(repository, files, events, Fixtures.session())
        viewModel.load()
        assertEquals("Mia Mitglied", viewModel.state.value.details.valueOrNull?.name)

        val version = events.version.value
        viewModel.confirm()
        assertEquals(version + 1, events.version.value)
        assertEquals(Fixtures.CANCELLED, events.take().message)

        val failure = "Deine Kündigung konnte leider nicht verarbeitet werden. Bitte versuche es später noch einmal."
        transport.add("POST", "membership/cancellation", status = 503, json = """{"message":"$failure"}""")
        viewModel.confirm()
        assertEquals(failure, events.take().error)
    }

    @Test
    fun `an unreachable contract page shows the server's reason`() = runTest {
        val message = "Du hast derzeit kein aktives Abonnement, das gekündigt werden kann."
        val (repository, _) = Fixtures.repository(Stub("GET", "membership/cancellation", status = 409, json = """{"message":"$message"}"""))
        val viewModel = CancelViewModel(repository, files, MembershipEvents(), Fixtures.session())
        viewModel.load()
        assertEquals(message, (viewModel.state.value.details as Loadable.Failed).error.userMessage)
    }

    @Test
    fun `withdrawal page exports the data directly`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "membership/withdrawal", json = Fixtures.WITHDRAWAL),
            Stub("GET", "account/export", json = "PK")
        )
        val viewModel = WithdrawViewModel(repository, files, MembershipEvents(), Fixtures.session())
        // The page's own load runs on OkHttp's thread; once it is in, the export is the last request.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (viewModel.state.value.details !is Loadable.Loaded) delay(10) } }
        viewModel.prepareExport()
        val file = viewModel.state.value.exportFile
        assertEquals("oasis-daten.zip", file?.name)
        assertEquals("PK", file?.readText())
        assertEquals("/api/v1/account/export", transport.requests.last().request.url.encodedPath)
    }

    @Test
    fun `portal downloads the invoice as a file`() = runTest {
        val html = "<!doctype html><title>Rechnung OAS-2</title>"
        val (repository, _) = Fixtures.repository(
            Stub("GET", "membership/payments", json = Fixtures.payments),
            Stub("GET", "membership/invoices/2", json = html)
        )
        val viewModel = PortalViewModel(repository, files)
        viewModel.load()
        val payment = viewModel.state.value.payments.valueOrNull!!.first()
        viewModel.openInvoice(payment)
        // The download runs in the view model's scope on OkHttp's thread; wait for it.
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.invoiceFile == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val file = viewModel.state.value.invoiceFile
        assertNotNull(file)
        assertEquals("Rechnung-OAS-2.html", file!!.name)
        assertEquals(html, file.readText())
        assertNull(viewModel.state.value.loadingInvoice)
    }
}
