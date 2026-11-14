package de.velwebsolutions.oasis.membership

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.MembershipPerks
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.ui.CompanionLinkScreen
import de.velwebsolutions.oasis.membership.ui.CompanionLinkViewModel
import de.velwebsolutions.oasis.membership.ui.OverviewContent
import de.velwebsolutions.oasis.membership.ui.OverviewUiState
import de.velwebsolutions.oasis.membership.ui.PerksScreen
import de.velwebsolutions.oasis.membership.ui.PerksViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deine Vorteile, the companion link and the way there from the overview on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h3000dp")
class PerksScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val token = "a".repeat(40)
    private val repository = FakeMembershipRepository()
    private val events = MembershipEvents()
    private val navigator = Navigator().apply { selectedTab = MainTab.Profile }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator) { content() } } }
    }

    private fun waitFor(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `a Maezen shares, frees and creates places and switches the supporter mark`() {
        val perks = Fixtures.perks()
        // A third place without member and link (an old server) shows only "Platz freigeben".
        repository.perks = Result.success(perks.copy(remainingSeats = 1, seats = perks.seats + MembershipPerks.Seat(3)))
        val model = PerksViewModel(repository, events, Fixtures.session())
        show { PerksScreen(model) }
        waitFor("In Mäzen enthalten")
        compose.onNodeWithText("Fotos in voller Auflösung").assertExists()
        compose.onNodeWithText("Jonas Weber ist dabei, seit 20.10.2026").assertExists()
        compose.onNodeWithText("Stufen vergleichen").assertDoesNotExist()

        compose.onNodeWithContentDescription("Unterstützer-Zeichen am Profil").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Auf der Dankesliste stehen").performClick()
        compose.waitUntil(15_000) { repository.calls.size == 2 }

        compose.onNodeWithTag("perks.createSeat").performScrollTo().performClick()
        compose.waitUntil(15_000) { "createSeat" in repository.calls }

        compose.onAllNodes(hasText("Platz freigeben"))[0].performClick()
        waitFor("Jonas Weber verliert dann den Zugang")
        compose.onNodeWithText("Abbrechen").performClick()
        compose.onAllNodes(hasText("Platz freigeben"))[1].performClick()
        waitFor("Der Link gilt dann nicht mehr.")
        compose.onNodeWithTag("perks.confirm").performClick()
        compose.waitUntil(15_000) { "releaseSeat 2" in repository.calls }

        // Sharing opens the system sheet, the last step.
        compose.onNodeWithText("Link teilen").performClick()
    }

    @Test
    fun `a companion gives a resting place back and may compare the tiers`() {
        repository.perks = Result.success(Fixtures.perks(Fixtures.companionPerks))
        // Not paying: the held place shows (it is hidden for paying members, as on the web).
        repository.overview = Result.success(Fixtures.overview(Fixtures.noMembershipOverview))
        val model = PerksViewModel(repository, events, Fixtures.session())
        show { PerksScreen(model) }
        waitFor("Dein Platz bei Mia Mäzen")
        compose.onNodeWithText("deshalb ruht dein Platz", substring = true).assertExists()
        compose.onNodeWithText("Menschen mitnehmen").assertDoesNotExist()
        compose.onNodeWithText("Platz zurückgeben").performClick()
        waitFor("Den Platz wirklich zurückgeben?")
        compose.onNodeWithTag("perks.confirm").performClick()
        compose.waitUntil(15_000) { "leaveSeat" in repository.calls }
        compose.onNodeWithText("Stufen vergleichen").performClick()
    }

    @Test
    fun `an active place and a tier the server does not name`() {
        val active = Fixtures.perks(Fixtures.companionPerks).let { it.copy(heldSeat = it.heldSeat!!.copy(active = true), tierName = "") }
        repository.perks = Result.success(active)
        repository.overview = Result.success(
            Fixtures.overview(
                Fixtures.noMembershipOverview
            ).copy(companionSeat = MembershipOverview.CompanionSeat("Mia Mäzen", "oase", "Mildcrowd plus"))
        )
        val model = PerksViewModel(repository, events, Fixtures.session())
        show { PerksScreen(model) }
        waitFor("Mia Mäzen nimmt dich als Mäzen mit.")
        compose.onNodeWithText("aus der Stufe Mildcrowd plus", substring = true).assertExists()
        compose.onNodeWithText("MITGLIEDSCHAFT").assertExists()
    }

    @Test
    fun `accepting a companion link goes to the Start, an expired one leads to the membership`() {
        val model = CompanionLinkViewModel(token, repository, events, Fixtures.session())
        show { CompanionLinkScreen(token, model) }
        waitFor("Mia Mäzen nimmt dich mit nach Mildcrowd")
        compose.onNodeWithTag("companion.accept").performClick()
        compose.waitUntil(15_000) { navigator.selectedTab == MainTab.Source }

        repository.companionSponsor = Result.success(null)
        compose.runOnIdle { kotlinx.coroutines.runBlocking { model.load() } }
        waitFor("Dieser Link gilt nicht mehr")
        compose.onNodeWithText("Eigene Mitgliedschaft ansehen").performClick()
        assertEquals(MembershipRoute.Overview, navigator.stack(navigator.selectedTab).last())
    }

    @Test
    fun `the overview leads to the perks and names the places of a Maezen`() {
        val overview = Fixtures.overview(Fixtures.subscriptionOverview)
        val maezen = overview.copy(features = overview.features.copy(companionSeats = 2))
        show { OverviewContent(OverviewUiState(Loadable.Loaded(maezen)), onRefresh = {}) }
        compose.onNodeWithText("Was deine Stufe enthält, deine Plätze für andere und dein Unterstützer-Zeichen.").assertExists()
        compose.onNodeWithTag("membership.perks").performClick()
        assertEquals(MembershipRoute.Perks, navigator.stack(MainTab.Profile).last())
    }
}
