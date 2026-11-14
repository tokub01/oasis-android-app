package de.velwebsolutions.oasis.membership

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.theme.Appearance
import de.velwebsolutions.oasis.design.theme.AppearanceChoice
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.membership.api.FinanceReport
import de.velwebsolutions.oasis.membership.api.Transparency
import de.velwebsolutions.oasis.membership.ui.TransparencyContent
import de.velwebsolutions.oasis.membership.ui.TransparencyScreen
import de.velwebsolutions.oasis.membership.ui.TransparencyUiState
import de.velwebsolutions.oasis.membership.ui.TransparencyViewModel
import de.velwebsolutions.oasis.membership.ui.figureText
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport.Stub
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** „Wohin dein Beitrag geht“ (docs/mildcrowd-admin.md): the figures and the reports, at 360 dp in both modes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class TransparencyTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeMembershipRepository()

    @After
    fun light() = Appearance.show(AppearanceChoice.Automatic)

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/membership-$name.png")

    @Test
    fun `the page loads, an earlier report replaces the shown one and failures show as page or toast`() = runTest {
        val viewModel = TransparencyViewModel(repository)
        assertEquals("November 2026", viewModel.state.value.shown?.periodLabel)
        viewModel.show(3)
        assertEquals("Oktober 2026", viewModel.state.value.shown?.periodLabel)
        repository.report = Result.failure(ApiError.Server(500, "Kaputt."))
        viewModel.show(4)
        assertEquals("Kaputt.", viewModel.state.value.error)
        viewModel.dismissError()
        assertNull(viewModel.state.value.error)
        repository.transparency = Result.failure(ApiError.Server(500, "Offline"))
        viewModel.load()
        assertEquals("Offline", viewModel.state.value.error)
        assertTrue(TransparencyViewModel(repository).state.value.page is Loadable.Failed)
        assertNull("a failed first load is the page", TransparencyViewModel(repository).state.value.error)
    }

    @Test
    fun `the repository asks without sign-in for the page and a report`() = runTest {
        val (api, transport) = Fixtures.repository(
            Stub("GET", "transparency", json = TransparencyFixtures.pageJson()),
            Stub("GET", "transparency/reports/3", json = """{"data":${TransparencyFixtures.reportJson(3, "Oktober 2026")}}""")
        )
        assertEquals(1234, api.transparency().figures.activeMembers)
        assertEquals("Oktober 2026", api.transparencyReport(3).periodLabel)
        assertEquals(listOf("transparency", "transparency/reports/3"), transport.requests.map { it.path })
    }

    @Test
    fun `amounts, shares and counts read as on the web`() {
        val report = TransparencyFixtures.report(4, "November 2026")
        assertEquals("1.234,56 €", FinanceReport.euros(1234.56))
        assertEquals("(37 %)", report.share(report.costs.first()))
        assertNull(report.copy(costsTotal = 0.0).share(report.costs.first()))
        assertEquals("1.234", figureText(1234))
        val plain = FinanceReport(1, "Mai 2026", 0.0)
        assertTrue(plain.costs.isEmpty() && plain.note == null && plain.balance == 0.0)
        val figures = Transparency.Figures()
        assertTrue(figures.activeMembers == 0 && figures.oases == 0)
        assertTrue(Transparency("Tobias Kubina - VelWebSolutions", figures).earlier.isEmpty())
        assertEquals("Mai 2026", Transparency.EarlierReport(1, "Mai 2026").periodLabel)
        assertEquals(1.5, FinanceReport.Cost("server", "Server & Technik", 1.5).amount, 0.0)
    }

    @Test
    fun `the page shows figures and the report with its earlier ones`() {
        val viewModel = TransparencyViewModel(repository)
        compose.setContent { OasisTheme { TransparencyScreen(viewModel) } }
        compose.onNodeWithText("Mildcrowd wird betrieben von Tobias Kubina - VelWebSolutions.", substring = true).assertExists()
        shot("transparency")
        compose.onNodeWithText("Überschuss").performScrollTo()
        shot("transparency-report")
        compose.onNodeWithTag("transparency.report.3").performScrollTo().performClick()
        compose.waitUntil(15_000) { viewModel.state.value.shown?.id == 3 }
        compose.onNodeWithText("Fehlbetrag").performScrollTo()
        Appearance.show(AppearanceChoice.Dark)
        compose.onNodeWithText("Mildcrowd heute").performScrollTo()
        shot("transparency-dark")
    }

    @Test
    fun `without a report`() {
        val page = TransparencyFixtures.page(TransparencyFixtures.pageJson(report = null))
        compose.setContent { OasisTheme { TransparencyContent(TransparencyUiState(Loadable.Loaded(page)), {}, {}, {}) } }
        compose.onNodeWithText("Noch keine Zahlen veröffentlicht.").performScrollTo().assertExists()
        compose.onNodeWithText("Frühere Berichte").assertDoesNotExist()
    }

    @Test
    fun `a report without earlier ones and without a note`() {
        val page = TransparencyFixtures.page().let { it.copy(earlier = emptyList(), report = it.report?.copy(note = null)) }
        compose.setContent { OasisTheme { TransparencyContent(TransparencyUiState(Loadable.Loaded(page), page.report), {}, {}, {}) } }
        compose.onNodeWithText("Überschuss").performScrollTo().assertExists()
        compose.onNodeWithText("Frühere Berichte").assertDoesNotExist()
    }
}
