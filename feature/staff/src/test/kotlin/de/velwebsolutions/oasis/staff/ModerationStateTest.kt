package de.velwebsolutions.oasis.staff

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.staff.api.ModerationCaseDetail
import de.velwebsolutions.oasis.staff.api.QueueFilter
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.data.StaffFiles
import de.velwebsolutions.oasis.staff.ui.moderation.CaseDetailViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.ComplaintReviewViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.ModerationOverviewViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.RolesViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.SuspensionsViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The moderation view models with a [FakeStaffRepository]: first loads, refreshes, failures, forms and pages. */
class ModerationStateTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakeStaffRepository()
    private val statement = StatementOfReasons("no_action", "community_rules", "Regel 1", "Tatsachen", "Begründung")
    private val morePages get() = Page(Decoded.cases.data, Page.Meta(nextCursor = "2"))

    @Test
    fun `the start page shows a failed first load as page, a failed refresh as toast`() = runTest {
        repository.overview = FakeStaffRepository.failure()
        val viewModel = ModerationOverviewViewModel(repository)
        assertTrue(viewModel.state.value.overview is Loadable.Failed)
        assertNull(viewModel.state.value.error)
        assertFalse("no queue without the start page", repository.calls.any { it.startsWith("cases") })

        repository.overview = Result.success(Decoded.overview)
        viewModel.load()
        repository.overview = FakeStaffRepository.failure("Offline")
        viewModel.load()
        assertEquals("Offline", viewModel.state.value.error)
        assertTrue(viewModel.state.value.overview.valueOrNull != null)
        viewModel.dismissError()
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `the queue pages on and a failed page is a toast`() = runTest {
        repository.cases = Result.success(morePages)
        val viewModel = ModerationOverviewViewModel(repository)
        val first = viewModel.state.value.cases.size
        assertTrue(viewModel.state.value.hasMore)

        viewModel.loadMore()
        assertEquals(first * 2, viewModel.state.value.cases.size)
        assertTrue("cases 2 open is asked", repository.calls.contains("cases 2 ${QueueFilter().status}"))
        assertTrue(viewModel.state.value.hasMore)

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repository.casesGate = gate
        val loading = async { viewModel.loadMore() }
        testScheduler.runCurrent()
        assertTrue(viewModel.state.value.isLoadingQueue)
        viewModel.loadMore()
        repository.cases = Result.success(Decoded.cases)
        gate.complete(Unit)
        loading.await()
        repository.casesGate = null
        assertEquals("one page while one is loading", first * 3, viewModel.state.value.cases.size)
        assertFalse(viewModel.state.value.hasMore)
        repository.cases = Result.success(morePages)
        viewModel.reloadQueue()

        repository.cases = FakeStaffRepository.failure("Seite fehlt")
        viewModel.loadMore()
        assertEquals("Seite fehlt", viewModel.state.value.error)
        assertFalse(viewModel.state.value.isLoadingQueue)

        viewModel.reloadQueue()
        assertEquals("a failed reload keeps the queue", first, viewModel.state.value.cases.size)

        repository.cases = Result.success(Decoded.cases)
        viewModel.reloadQueue()
        assertFalse(viewModel.state.value.hasMore)
        val calls = repository.calls.size
        viewModel.loadMore()
        assertEquals("the last page loads nothing more", calls, repository.calls.size)
    }

    @Test
    fun `a filter reloads the queue once typing pauses, an unchanged one does not`() = runTest {
        val viewModel = ModerationOverviewViewModel(repository)
        val calls = repository.calls.count { it.startsWith("cases") }
        viewModel.apply(QueueFilter(search = "d"))
        viewModel.apply(QueueFilter(search = "dr"))
        advanceTimeBy(301)
        assertEquals(calls + 1, repository.calls.count { it.startsWith("cases") })
        assertEquals(QueueFilter(search = "dr"), viewModel.state.value.loadedFilter)

        viewModel.apply(QueueFilter(search = "dr"))
        advanceTimeBy(301)
        assertEquals(calls + 1, repository.calls.count { it.startsWith("cases") })
    }

    @Test
    fun `case actions confirm, fail at their fields or as toast`() = runTest {
        val viewModel = CaseDetailViewModel(2, repository, StaffFiles(Files.createTempDirectory("staff").toFile()))
        assertEquals(2, viewModel.caseId)
        val detail = viewModel.state.value.detail.valueOrNull!!
        assertEquals(detail.assignee?.id ?: detail.assignDefault, viewModel.state.value.assignTo)

        viewModel.selectAssignee(9)
        viewModel.assign()
        assertTrue(repository.calls.contains("assign 9"))
        assertEquals("Gespeichert.", viewModel.state.value.message)
        viewModel.dismissMessage()

        viewModel.updateStatement(statement)
        viewModel.decide()
        assertEquals("", viewModel.state.value.statement.outcome)

        viewModel.updateReference("LKA 1/26")
        viewModel.recordReport()
        assertEquals("", viewModel.state.value.reference)
        viewModel.escalate("terror")
        viewModel.extendEvidence()
        viewModel.deleteEvidence()
        viewModel.liftQuarantine()
        assertTrue(repository.calls.containsAll(listOf("escalate terror", "report LKA 1/26", "extend", "delete", "lift")))

        repository.caseAction = FakeStaffRepository.invalid("facts" to "Bitte die Tatsachen angeben.")
        viewModel.updateStatement(statement)
        viewModel.decide()
        assertEquals("Bitte die Tatsachen angeben.", viewModel.state.value.fieldErrors["facts"])
        assertEquals("the form stays on a refusal", "no_action", viewModel.state.value.statement.outcome)
        viewModel.updateReference("LKA")
        viewModel.recordReport()
        assertEquals("LKA", viewModel.state.value.reference)

        repository.caseAction = FakeStaffRepository.failure("Konflikt")
        viewModel.escalate("terror")
        assertEquals("Konflikt", viewModel.state.value.error)
        viewModel.dismissError()
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `a case without an assignee keeps the chosen person, and loading may fail`() = runTest {
        repository.case = FakeStaffRepository.failure()
        val viewModel = CaseDetailViewModel(2, repository, StaffFiles(Files.createTempDirectory("staff").toFile()))
        assertTrue(viewModel.state.value.detail is Loadable.Failed)
        assertNull(viewModel.state.value.error)
        viewModel.assign()
        assertFalse("nobody chosen, nothing sent", repository.calls.any { it.startsWith("assign") })

        repository.case = Result.success(Decoded.caseDetail.copy(assignee = null))
        val unassigned = CaseDetailViewModel(2, repository, StaffFiles(Files.createTempDirectory("staff").toFile()))
        assertEquals("nobody chosen: the suggested person", Decoded.caseDetail.assignDefault, unassigned.state.value.assignTo)
        viewModel.selectAssignee(5)
        viewModel.load()
        assertEquals(5, viewModel.state.value.assignTo)
        repository.case = Result.success(Decoded.caseDetail.copy(assignee = ModerationCaseDetail.Assignee(12, "Mo Moderatorin")))
        viewModel.load()
        assertEquals("an assigned case shows its person", 12, viewModel.state.value.assignTo)
        repository.case = FakeStaffRepository.failure("Offline")
        viewModel.load()
        assertEquals("Offline", viewModel.state.value.error)

        repository.archive = FakeStaffRepository.failure("Kein Zugriff")
        viewModel.prepareArchive()
        assertEquals("Kein Zugriff", viewModel.state.value.error)
        assertNull(viewModel.state.value.archiveFile)
    }

    @Test
    fun `a complaint review clears its form after saving and keeps it on a refusal`() = runTest {
        val viewModel = ComplaintReviewViewModel(1, repository)
        viewModel.selectResult("overturned")
        viewModel.updateStatement(statement)
        viewModel.updateExplanation("Begründung")
        viewModel.review()
        assertEquals("Geprüft.", viewModel.state.value.message)
        assertEquals("", viewModel.state.value.result)
        viewModel.dismissMessage()
        assertNull(viewModel.state.value.message)

        repository.review = FakeStaffRepository.invalid("result" to "Bitte ein Ergebnis wählen.")
        viewModel.review()
        assertEquals("Bitte ein Ergebnis wählen.", viewModel.state.value.fieldErrors["result"])
        assertNull(viewModel.state.value.error)
        repository.review = FakeStaffRepository.failure("Gesperrt")
        viewModel.review()
        assertEquals("Gesperrt", viewModel.state.value.error)
        viewModel.dismissError()

        repository.complaint = FakeStaffRepository.failure("Offline")
        viewModel.load()
        assertEquals("Offline", viewModel.state.value.error)
        val failed = ComplaintReviewViewModel(1, repository)
        assertTrue(failed.state.value.complaint is Loadable.Failed)
        assertNull(failed.state.value.error)
    }

    @Test
    fun `roles page on, save the chosen role and show refusals at the row`() = runTest {
        repository.roles = Result.success(Decoded.roles.copy(meta = Page.Meta(nextCursor = "2")))
        val viewModel = RolesViewModel(repository)
        val users = viewModel.state.value.users.valueOrNull!!
        assertTrue(viewModel.state.value.hasMore)
        viewModel.loadMore()
        assertTrue("a further page with more to come", viewModel.state.value.hasMore)
        repository.roles = Result.success(Decoded.roles)
        viewModel.loadMore()
        assertEquals(users.size * 3, viewModel.state.value.users.valueOrNull!!.size)
        assertFalse(viewModel.state.value.hasMore)
        val calls = repository.calls.size
        viewModel.loadMore()
        assertEquals(calls, repository.calls.size)

        val user = users.first()
        viewModel.choose(user.id, "moderator")
        viewModel.save(user)
        assertEquals("Geändert.", viewModel.state.value.message)
        assertEquals("moderator", viewModel.state.value.users.valueOrNull!!.first { it.id == user.id }.role.value)
        viewModel.dismissMessage()

        repository.changeRole = FakeStaffRepository.invalid("role" to "Nur das Betreiberkonto ernennt Admins.")
        viewModel.save(users.last())
        assertEquals("Nur das Betreiberkonto ernennt Admins.", viewModel.state.value.errors[users.last().id])
        repository.changeRole = FakeStaffRepository.failure("Offline")
        viewModel.save(user)
        assertEquals("Offline", viewModel.state.value.errors[user.id])

        repository.roles = Result.success(Decoded.roles.copy(meta = Page.Meta(nextCursor = "2")))
        viewModel.load()
        repository.roles = FakeStaffRepository.failure("Seite fehlt")
        viewModel.loadMore()
        assertEquals("Seite fehlt", viewModel.state.value.error)
        viewModel.load()
        viewModel.dismissError()
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `roles that fail to load show the error page`() = runTest {
        repository.roles = FakeStaffRepository.failure()
        val viewModel = RolesViewModel(repository)
        assertTrue(viewModel.state.value.users is Loadable.Failed)
        assertNull(viewModel.state.value.error)
        viewModel.save(Decoded.roles.data.first())
        assertEquals("a user not on the page changes nothing", null, viewModel.state.value.users.valueOrNull)
    }

    @Test
    fun `locks are released with the server's confirmation and failures are toasts`() = runTest {
        repository.suspensions = FakeStaffRepository.failure()
        val viewModel = SuspensionsViewModel(repository)
        assertTrue(viewModel.state.value.list is Loadable.Failed)
        assertNull(viewModel.state.value.error)

        repository.suspensions = Result.success(Decoded.suspensions)
        viewModel.load()
        val lock = viewModel.state.value.list.valueOrNull!!.active.first()
        viewModel.release(lock)
        assertEquals("Freigegeben.", viewModel.state.value.message)
        viewModel.dismissMessage()

        repository.release = FakeStaffRepository.failure("Nur Admins")
        viewModel.release(lock)
        assertEquals("Nur Admins", viewModel.state.value.error)
        repository.suspensions = FakeStaffRepository.failure("Offline")
        viewModel.load()
        assertTrue(viewModel.state.value.list.valueOrNull != null)
        viewModel.dismissError()
        assertNull(viewModel.state.value.error)
    }
}
