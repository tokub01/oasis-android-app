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
import org.junit.Assert.assertEquals
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
        val (repository, transport) = Fixtures.repository(Stub("GET", "membership", json = Fixtures.trialOverview))
        val viewModel = OverviewViewModel(repository, MembershipEvents())
        viewModel.load()
        transport.add("GET", "membership", status = 500, json = """{"message":"Server Error"}""")
        viewModel.load()
        assertEquals(MembershipOverview.State.Trial, viewModel.state.value.overview.valueOrNull?.state)
        assertEquals("Server Error", viewModel.state.value.error)
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
