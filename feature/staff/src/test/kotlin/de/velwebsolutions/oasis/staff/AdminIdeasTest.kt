package de.velwebsolutions.oasis.staff

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.staff.api.AdminIdea
import de.velwebsolutions.oasis.staff.api.IdeaStatusInput
import de.velwebsolutions.oasis.staff.ui.admin.AdminIdeasScreen
import de.velwebsolutions.oasis.staff.ui.admin.AdminIdeasUiState
import de.velwebsolutions.oasis.staff.ui.admin.AdminIdeasViewModel
import de.velwebsolutions.oasis.staff.ui.admin.ideaVotes
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** „Ideen“ for admins (docs/oasis-admin.md „Ideen für Oasis“): list, sort, pages and „Stand setzen“, at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class AdminIdeasTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val compose = createComposeRule()

    private val ui = StaffUiHarness(compose)
    private val repository = FakeStaffRepository()

    @Test
    fun `ideas load with the votes to link, page on and sort`() = runTest {
        val viewModel = AdminIdeasViewModel(repository)
        viewModel.load()
        val state = viewModel.state.value
        assertEquals(listOf(7, 8), state.ideas.valueOrNull?.map { it.id })
        assertEquals(2, state.nextPage)
        assertEquals(Decoded.votes, state.votes)
        repository.ideas = Result.success(Page(listOf(Decoded.ideas.data.first(), Decoded.ideas.data.first().copy(id = 9))))
        viewModel.loadMore()
        assertEquals("known ones once", listOf(7, 8, 9), viewModel.state.value.ideas.valueOrNull?.map { it.id })
        assertNull(viewModel.state.value.nextPage)
        viewModel.loadMore()
        viewModel.choose("new")
        assertTrue(repository.calls.containsAll(listOf("ideas popular 1", "ideas popular 2", "ideas new 1")))

        repository.votes = FakeStaffRepository.failure()
        viewModel.load()
        assertEquals("the votes stay", Decoded.votes, viewModel.state.value.votes)
        repository.ideas = FakeStaffRepository.failure("Offline")
        viewModel.load()
        assertEquals("Offline", viewModel.state.value.error)
        viewModel.dismissError()
        assertTrue(AdminIdeasViewModel(repository).state.value.ideas is Loadable.Failed)
        val failing = AdminIdeasViewModel(repository)
        failing.load()
        assertNull("a failed first load is the page, no toast", failing.state.value.error)
        failing.loadMore()
        failing.save(Decoded.ideas.data.first())
        assertTrue("nothing to page or replace in", failing.state.value.ideas is Loadable.Failed)
        repository.ideas = Result.success(Decoded.ideas)
        val paging = AdminIdeasViewModel(repository)
        repository.ideas = FakeStaffRepository.failure("Offline")
        paging.loadMore()
        assertEquals("Offline", paging.state.value.error)
        assertEquals(2, paging.state.value.nextPage)
        val written = de.velwebsolutions.oasis.core.network.OasisJson.snake.encodeToString(AdminIdea.serializer(), Decoded.ideas.data[1])
        assertTrue(written.contains("Wohin mit einem Überschuss?"))
        assertEquals("Aylin", AdminIdea.Author("Aylin").displayName)
        assertEquals(1, AdminIdea.VoteRef(1, "Frage?").id)
        val signed = Decoded.ideas.data.first()
        assertTrue(
            de.velwebsolutions.oasis.core.network.OasisJson.snake.encodeToString(AdminIdea.serializer(), signed).contains("Aylin Demir")
        )
    }

    @Test
    fun `a status is set, refused at its fields or failed as toast`() = runTest {
        val viewModel = AdminIdeasViewModel(repository)
        viewModel.load()
        val idea = viewModel.state.value.ideas.valueOrNull!!.first()
        assertEquals(IdeaStatusInput("open", "", null), viewModel.state.value.form(idea))
        val linked = viewModel.state.value.ideas.valueOrNull!![1]
        assertEquals(IdeaStatusInput("planned", "Kommt im Winter.", 1), viewModel.state.value.form(linked))
        viewModel.edit(idea) { it.copy(status = "planned", reply = "Bald.", voteId = 1) }
        repository.ideaStatus = FakeStaffRepository.invalid("reply" to "Zu lang.")
        viewModel.save(idea)
        assertEquals("Zu lang.", viewModel.state.value.fieldErrors[7]?.get("reply"))
        repository.ideaStatus = FakeStaffRepository.failure("Offline")
        viewModel.save(idea)
        assertEquals("Offline", viewModel.state.value.error)
        repository.ideaStatus = Result.success(
            de.velwebsolutions.oasis.core.network.Confirmed(idea.copy(status = "planned", statusLabel = "Geplant"), "Gespeichert.")
        )
        viewModel.save(idea)
        assertTrue(repository.calls.contains("idea 7 planned Bald. 1"))
        assertEquals("Geplant", viewModel.state.value.ideas.valueOrNull!!.first().statusLabel)
        assertEquals("Gespeichert.", viewModel.state.value.message)
        assertTrue(viewModel.state.value.forms.isEmpty() && viewModel.state.value.fieldErrors.isEmpty())
        viewModel.dismissMessage()
        assertNull(viewModel.state.value.message)
        assertEquals("1 Stimme", ideaVotes(1))
        assertEquals("3 Stimmen", ideaVotes(3))
        assertEquals("popular", AdminIdeasUiState().sort)
        assertEquals("Offen", AdminIdea(1, "x").statusLabel)
        assertEquals(5, AdminIdea.statuses.size)
    }

    @Test
    fun `the page sets a status with a reply and a linked vote`() {
        val viewModel = AdminIdeasViewModel(repository)
        ui.show { AdminIdeasScreen(viewModel) }
        compose.onNodeWithText("Dunkler Modus für die App").assertExists()
        ui.shot("ideas")
        compose.onNodeWithTag("idea.7.status").performClick()
        compose.onNodeWithText("In Arbeit").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("idea.7.reply"))).performScrollTo().performTextInput("Wir bauen.")
        compose.onNodeWithTag("idea.7.vote").performScrollTo().performClick()
        compose.onNodeWithText("Soll die Quelle eine Suchfunktion bekommen?").performClick()
        compose.onNodeWithTag("idea.7.save").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repository.calls.toString(), repository.calls.any { it == "idea 7 in_progress Wir bauen. 2" })
        // The confirmation's snackbar would cover the buttons below.
        viewModel.dismissMessage()
        compose.onNodeWithTag("ideas.more").performScrollTo().performClick()
        compose.onNodeWithTag("ideas.sort").performScrollTo().performClick()
        compose.onNodeWithText("Neu").performClick()
        compose.waitForIdle()
        assertTrue(repository.calls.toString(), repository.calls.containsAll(listOf("ideas popular 2", "ideas new 1")))
    }

    @Test
    fun `no ideas yet`() {
        repository.ideas = Result.success(Page(emptyList()))
        val viewModel = AdminIdeasViewModel(repository)
        ui.show { AdminIdeasScreen(viewModel) }
        compose.onNodeWithText("Noch keine Ideen.").assertExists()
    }
}
