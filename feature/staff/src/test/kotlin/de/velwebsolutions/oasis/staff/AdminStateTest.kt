package de.velwebsolutions.oasis.staff

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.staff.api.MollieLookup
import de.velwebsolutions.oasis.staff.ui.admin.AdminDashboardViewModel
import de.velwebsolutions.oasis.staff.ui.admin.AdminPaymentsViewModel
import de.velwebsolutions.oasis.staff.ui.admin.DataBreachesViewModel
import de.velwebsolutions.oasis.staff.ui.admin.SystemViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The admin view models with a [FakeStaffRepository]: first loads, refreshes, forms, lookups and steps. */
class AdminStateTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakeStaffRepository()

    @Test
    fun `the statistics follow period and month, and failures show as page or toast`() = runTest {
        val viewModel = AdminDashboardViewModel(repository)
        viewModel.selectDays(90)
        viewModel.showMonth("2026-09")
        assertTrue(repository.calls.contains("dashboard 90 "))
        assertTrue("the month keeps the server's period", repository.calls.contains("dashboard ${Decoded.dashboard.days} 2026-09"))
        assertEquals("the server's period wins", Decoded.dashboard.days, viewModel.state.value.days)

        repository.dashboard = FakeStaffRepository.failure("Offline")
        viewModel.load()
        assertEquals("Offline", viewModel.state.value.error)
        viewModel.dismissError()
        assertNull(viewModel.state.value.error)
        assertTrue(AdminDashboardViewModel(repository).state.value.dashboard is Loadable.Failed)
    }

    @Test
    fun `payments search once typing pauses, filter by status and page on`() = runTest {
        repository.payments = Result.success(Decoded.payments.copy(meta = Page.Meta(nextCursor = "2", total = 40)))
        val viewModel = AdminPaymentsViewModel(repository)
        assertEquals(40, viewModel.state.value.total)
        viewModel.updateSearch("#1")
        viewModel.updateSearch(" #12 ")
        advanceTimeBy(401)
        assertTrue(repository.calls.contains("payments '#12' '' 1"))
        assertTrue(repository.calls.none { it.startsWith("payments '#1' ") })

        viewModel.selectStatus("paid")
        assertTrue(repository.calls.contains("payments '#12' 'paid' 1"))
        val shown = viewModel.state.value.records.size
        viewModel.loadMore()
        assertEquals(shown * 2, viewModel.state.value.records.size)

        repository.payments = FakeStaffRepository.failure("Seite fehlt")
        viewModel.loadMore()
        assertEquals("Seite fehlt", viewModel.state.value.error)
        viewModel.load()
        assertTrue(viewModel.state.value.page.valueOrNull != null)
        viewModel.dismissError()
        viewModel.dismissMessage()

        repository.payments = Result.success(Decoded.payments)
        viewModel.load()
        assertEquals(Decoded.payments.data.size, viewModel.state.value.total)
        val calls = repository.calls.size
        viewModel.loadMore()
        assertEquals("the last page loads nothing more", calls, repository.calls.size)
        repository.payments = FakeStaffRepository.failure()
        assertTrue(AdminPaymentsViewModel(repository).state.value.page is Loadable.Failed)
    }

    @Test
    fun `a further page asked for before a new search is dropped`() = runTest {
        repository.payments = Result.success(Decoded.payments.copy(meta = Page.Meta(nextCursor = "2", total = 40)))
        val viewModel = AdminPaymentsViewModel(repository)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repository.paymentsGate = gate
        val more = async { viewModel.loadMore() }
        testScheduler.runCurrent()

        val found = Decoded.payments.data.take(1)
        repository.paymentsGate = null
        repository.payments = Result.success(Decoded.payments.copy(data = found, meta = Page.Meta(total = 1)))
        viewModel.updateSearch("#1")
        advanceTimeBy(401)
        repository.payments = Result.success(Decoded.payments.copy(meta = Page.Meta(nextCursor = "3", total = 40)))
        gate.complete(Unit)
        more.await()
        assertTrue("page 2 of all payments was asked for", repository.calls.contains("payments '' '' 2"))
        assertEquals("but is not appended to the search", found.map { it.id }, viewModel.state.value.records.map { it.id })
        assertFalse(viewModel.state.value.hasMore)
        assertEquals(1, viewModel.state.value.total)
    }

    @Test
    fun `a further page continues the shown list, not a search still being typed`() = runTest {
        repository.payments = Result.success(Decoded.payments.copy(meta = Page.Meta(nextCursor = "2", total = 40)))
        val viewModel = AdminPaymentsViewModel(repository)
        viewModel.updateSearch("#9")
        viewModel.loadMore()
        assertTrue(repository.calls.contains("payments '' '' 2"))
        assertEquals(Decoded.payments.data.size * 2, viewModel.state.value.records.size)
        advanceTimeBy(401)
        assertTrue(repository.calls.contains("payments '#9' '' 1"))
        assertEquals(Decoded.payments.data.size, viewModel.state.value.records.size)
    }

    @Test
    fun `an older status that answers last leaves the newer list`() = runTest {
        val viewModel = AdminPaymentsViewModel(repository)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repository.paymentsGate = gate
        viewModel.selectStatus("paid")

        val open = Decoded.payments.data.take(1)
        repository.paymentsGate = null
        repository.payments = Result.success(Decoded.payments.copy(data = open))
        viewModel.selectStatus("open")
        repository.payments = FakeStaffRepository.failure("Zu spät")
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(repository.calls.contains("payments '' 'paid' 1"))
        assertEquals("open", viewModel.state.value.statusFilter)
        assertEquals(open.map { it.id }, viewModel.state.value.records.map { it.id })
        assertNull("the late answer of the older status is not shown either", viewModel.state.value.error)
    }

    @Test
    fun `before the first page there is nothing more to load`() {
        val empty = de.velwebsolutions.oasis.staff.ui.admin.AdminPaymentsUiState()
        assertEquals(false, empty.hasMore)
        assertEquals(0, empty.total)
        val withoutMeta = empty.copy(page = Loadable.Loaded(Decoded.payments.copy(meta = null)), records = Decoded.payments.data)
        assertEquals(false, withoutMeta.hasMore)
        assertEquals(Decoded.payments.data.size, withoutMeta.total)
        val withoutTotal = withoutMeta.copy(page = Loadable.Loaded(Decoded.payments.copy(meta = Page.Meta(nextCursor = "2"))))
        assertEquals(true, withoutTotal.hasMore)
        assertEquals(Decoded.payments.data.size, withoutTotal.total)
    }

    @Test
    fun `Mollie lookups show the answer or the reason, sync and refunds update the record`() = runTest {
        val viewModel = AdminPaymentsViewModel(repository)
        val record = viewModel.state.value.records.first()
        viewModel.checkPayment(record)
        assertEquals("Nichts gefunden.", viewModel.state.value.mollie?.error)
        viewModel.closeMollie()
        assertNull(viewModel.state.value.mollie)
        viewModel.checkCustomer(record)
        repository.mollie = FakeStaffRepository.failure("Mollie ist nicht erreichbar.")
        viewModel.searchMollie()
        assertEquals(MollieLookup(error = "Mollie ist nicht erreichbar."), viewModel.state.value.mollie)

        viewModel.sync(record)
        assertEquals("Abgeglichen", viewModel.state.value.records.first { it.id == record.id }.statusLabel)
        assertEquals("Abgeglichen.", viewModel.state.value.message)

        viewModel.startRefund(record)
        assertEquals(record.refundDefault, viewModel.state.value.refundAmount)
        viewModel.editRefund(reason = "Doppelt")
        viewModel.editRefund(amount = "1,00")
        assertEquals("1,00" to "Doppelt", viewModel.state.value.refundAmount to viewModel.state.value.refundReason)
        viewModel.refund(record)
        assertTrue(repository.calls.contains("refund ${record.id} 1,00"))
        assertNull(viewModel.state.value.refundFor)

        viewModel.startRefund(record)
        repository.paymentAction = FakeStaffRepository.invalid("amount" to "Höchstens 5,00 €.")
        viewModel.refund(record)
        assertEquals("Höchstens 5,00 €.", viewModel.state.value.refundErrors["amount"])
        assertEquals(record.id, viewModel.state.value.refundFor)
        viewModel.cancelRefund()
        assertNull(viewModel.state.value.refundFor)

        repository.paymentAction = FakeStaffRepository.failure("Mollie lehnt ab.")
        viewModel.refund(record)
        assertEquals("Mollie lehnt ab.", viewModel.state.value.error)
        viewModel.dismissError()
        viewModel.sync(record)
        assertEquals("Mollie lehnt ab.", viewModel.state.value.error)
    }

    @Test
    fun `breach steps confirm, fail at the breach, and the form is refused at its fields`() = runTest {
        val viewModel = DataBreachesViewModel(repository)
        val breach = viewModel.state.value.register.valueOrNull!!.data.first()
        viewModel.edit { it.copy(references = it.references + (breach.id to "BfDI 1"), notes = it.notes + (breach.id to "Nachtrag")) }
        viewModel.recordReport(breach)
        viewModel.addNote(breach)
        viewModel.recordNotified(breach)
        viewModel.close(breach)
        assertEquals("Vermerkt.", viewModel.state.value.message)
        assertTrue(viewModel.state.value.references.isEmpty())
        assertTrue(viewModel.state.value.notes.isEmpty())
        assertTrue(
            repository.calls.containsAll(listOf("breach report BfDI 1", "note Nachtrag", "notified ${breach.id}", "close ${breach.id}"))
        )

        viewModel.recordReport(breach)
        viewModel.addNote(breach)
        assertTrue("an empty reference and note are sent as they are", repository.calls.containsAll(listOf("breach report ", "note ")))

        repository.breachAction = FakeStaffRepository.failure("Schon abgeschlossen.")
        viewModel.edit { it.copy(references = it.references + (breach.id to "BfDI 2"), notes = it.notes + (breach.id to "Noch einer")) }
        viewModel.recordReport(breach)
        viewModel.addNote(breach)
        assertEquals("Schon abgeschlossen.", viewModel.state.value.stepErrors[breach.id])
        assertEquals("BfDI 2", viewModel.state.value.references[breach.id])
        assertEquals("Noch einer", viewModel.state.value.notes[breach.id])

        repository.breachAction = FakeStaffRepository.invalid("title" to "Bitte eine Bezeichnung angeben.")
        viewModel.record()
        assertEquals("Bitte eine Bezeichnung angeben.", viewModel.state.value.fieldErrors["title"])
        repository.breachAction = FakeStaffRepository.failure("Offline")
        viewModel.record()
        assertEquals("Offline", viewModel.state.value.error)
        viewModel.dismissError()
        viewModel.dismissMessage()
        assertNull(viewModel.state.value.message)

        repository.breaches = FakeStaffRepository.failure("Offline")
        viewModel.load()
        assertEquals("Offline", viewModel.state.value.error)
        assertTrue(DataBreachesViewModel(repository).state.value.register is Loadable.Failed)
    }

    @Test
    fun `the system check runs again and failures show as page or toast`() = runTest {
        val viewModel = SystemViewModel(repository)
        viewModel.load()
        assertEquals(2, repository.calls.count { it == "system" })
        repository.system = FakeStaffRepository.failure("Offline")
        viewModel.load()
        assertEquals("Offline", viewModel.state.value.error)
        viewModel.dismissError()
        assertNull(viewModel.state.value.error)
        assertTrue(SystemViewModel(repository).state.value.status is Loadable.Failed)
    }
}
