package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.data.MembershipFiles
import de.velwebsolutions.oasis.membership.ui.CancelViewModel
import de.velwebsolutions.oasis.membership.ui.InvoiceViewModel
import de.velwebsolutions.oasis.membership.ui.OverviewViewModel
import de.velwebsolutions.oasis.membership.ui.PortalViewModel
import de.velwebsolutions.oasis.membership.ui.WithdrawViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The membership view models' states with a [FakeMembershipRepository]: loading, failures, dialogs and toasts. */
class MembershipStateTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakeMembershipRepository()
    private val files = MembershipFiles(Files.createTempDirectory("membership").toFile())

    @Test
    fun `the overview loads on start and a first failure shows the error page, not a toast`() = runTest {
        repository.overview = FakeMembershipRepository.failure()
        val viewModel = OverviewViewModel(repository, MembershipEvents())
        assertTrue(viewModel.state.value.overview is Loadable.Failed)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `another screen's error alone reaches the overview and toasts can be dismissed`() = runTest {
        val events = MembershipEvents()
        val viewModel = OverviewViewModel(repository, events)
        val loads = repository.overviewCalls

        events.changed(error = "Kündigung fehlgeschlagen.")
        assertEquals("Kündigung fehlgeschlagen.", viewModel.state.value.error)
        assertEquals(loads + 1, repository.overviewCalls)

        events.changed()
        assertEquals("a reload without news keeps the shown error", "Kündigung fehlgeschlagen.", viewModel.state.value.error)
        viewModel.dismissError()
        assertNull(viewModel.state.value.error)

        events.changed(message = "Erledigt.")
        viewModel.dismissMessage()
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `the portal reports a failed first load as page and a failed refresh as toast`() = runTest {
        repository.payments = FakeMembershipRepository.failure()
        val viewModel = PortalViewModel(repository, files)
        assertTrue(viewModel.state.value.payments is Loadable.Failed)
        assertNull(viewModel.state.value.error)

        repository.payments = Result.success(emptyList())
        viewModel.load()
        repository.payments = FakeMembershipRepository.failure("Offline")
        viewModel.load()
        assertEquals(emptyList<Any>(), viewModel.state.value.payments.valueOrNull)
        assertEquals("Offline", viewModel.state.value.error)
        viewModel.dismissError()
        viewModel.dismissMessage()
        assertNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `one invoice downloads at a time, and a failed one is a toast`() = runTest {
        val viewModel = PortalViewModel(repository, files)
        val payment = viewModel.state.value.payments.valueOrNull!!.single()
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate

        viewModel.openInvoice(payment)
        assertEquals(payment.id, viewModel.state.value.loadingInvoice)
        viewModel.openInvoice(payment)
        gate.complete(Unit)
        assertEquals("Rechnung-OAS-2.html", viewModel.state.value.invoiceFile?.name)
        assertNull(viewModel.state.value.loadingInvoice)
        viewModel.closeInvoice()
        assertNull(viewModel.state.value.invoiceFile)

        repository.gate = null
        repository.invoice = FakeMembershipRepository.failure("Rechnung nicht gefunden")
        viewModel.openInvoice(payment)
        assertEquals("Rechnung nicht gefunden", viewModel.state.value.error)
        assertNull(viewModel.state.value.loadingInvoice)
    }

    @Test
    fun `an invoice link downloads the invoice and shows it right away`() = runTest {
        val viewModel = InvoiceViewModel(7, repository, files)
        val file = viewModel.state.value.file.valueOrNull
        assertEquals("Rechnung-OAS-7.html", file?.name)
        assertEquals(file, viewModel.state.value.preview)
        viewModel.closePreview()
        assertNull(viewModel.state.value.preview)
        viewModel.showPreview()
        assertEquals(file, viewModel.state.value.preview)
        assertEquals(7, viewModel.paymentId)

        repository.invoice = FakeMembershipRepository.failure()
        viewModel.load()
        assertTrue(viewModel.state.value.file is Loadable.Failed)
        viewModel.showPreview()
        assertNull("nothing to show without a file", viewModel.state.value.preview)
    }

    @Test
    fun `a failed export is a toast on the contract pages`() = runTest {
        repository.export = FakeMembershipRepository.failure("Export nicht möglich")
        val viewModel = CancelViewModel(repository, files, MembershipEvents(), Fixtures.session())
        viewModel.prepareExport()
        assertEquals("Export nicht möglich", viewModel.state.value.error)
        assertNull(viewModel.state.value.exportFile)
        viewModel.dismissError()
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `withdrawing hands the server's confirmation to the overview`() = runTest {
        val events = MembershipEvents()
        val viewModel = WithdrawViewModel(repository, files, events, Fixtures.session())
        assertEquals("Mia Mitglied", viewModel.state.value.details.valueOrNull?.name)
        viewModel.confirm()
        assertEquals("Erledigt.", events.take().message)
    }
}

/** A cancelled request (the screen left) is passed on, never shown as an error. */
class MembershipCancellationTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakeMembershipRepository()
    private val files = MembershipFiles(Files.createTempDirectory("membership").toFile())
    private val cancelled = Result.failure<Nothing>(kotlinx.coroutines.CancellationException("left"))

    private suspend fun assertCancels(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a cancellation, got $failure", failure is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `every view model passes a cancellation on`() = runTest {
        val overview = OverviewViewModel(repository, MembershipEvents())
        val portal = PortalViewModel(repository, files)
        val invoice = InvoiceViewModel(2, repository, files)
        val cancel = CancelViewModel(repository, files, MembershipEvents(), Fixtures.session())
        repository.overview = cancelled
        repository.payments = cancelled
        repository.invoice = cancelled
        repository.cancellation = cancelled
        repository.end = cancelled
        repository.export = cancelled
        assertCancels { overview.load() }
        assertCancels { portal.load() }
        assertCancels { invoice.load() }
        assertCancels { cancel.load() }
        assertCancels { cancel.confirm() }
        assertCancels { cancel.prepareExport() }
        portal.openInvoice(portal.state.value.payments.valueOrNull!!.first())
        assertNull("a cancelled download is no error", portal.state.value.error)
        assertNull(overview.state.value.error)
    }
}
