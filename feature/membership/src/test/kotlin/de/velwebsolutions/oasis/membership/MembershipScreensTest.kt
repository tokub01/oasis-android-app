package de.velwebsolutions.oasis.membership

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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
import de.velwebsolutions.oasis.membership.api.CancellationDetails
import de.velwebsolutions.oasis.membership.billing.PlayProduct
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.ui.CancelContent
import de.velwebsolutions.oasis.membership.ui.CheckoutOffer
import de.velwebsolutions.oasis.membership.ui.CheckoutUiState
import de.velwebsolutions.oasis.membership.ui.ContractUiState
import de.velwebsolutions.oasis.membership.ui.OverviewContent
import de.velwebsolutions.oasis.membership.ui.OverviewUiState
import de.velwebsolutions.oasis.membership.ui.PlayCheckoutContent
import de.velwebsolutions.oasis.membership.ui.PlayCheckoutViewModel
import de.velwebsolutions.oasis.membership.ui.TrialBannerView
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MembershipScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val navigator = Navigator().apply { selectedTab = MainTab.Profile }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator) { content() } } }
    }

    @Test
    fun `a subscriber sees the status and reaches cancel and withdraw`() {
        show { OverviewContent(OverviewUiState(Loadable.Loaded(Fixtures.overview(Fixtures.subscriptionOverview))), onRefresh = {}) }
        compose.onNodeWithText("Aktive Mitgliedschaft").assertIsDisplayed()
        compose.onNodeWithText("Gründungsmitglied mit Preisgarantie").assertIsDisplayed()
        compose.onNodeWithTag("membership.withdraw").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("membership.cancel").performScrollTo().performClick()
        assertEquals(MembershipRoute.Cancel, navigator.stack(MainTab.Profile).last())
    }

    @Test
    fun `a trial member is offered the checkout`() {
        show { OverviewContent(OverviewUiState(Loadable.Loaded(Fixtures.overview(Fixtures.trialOverview))), onRefresh = {}) }
        compose.onNodeWithText("Kostenloser Test").assertIsDisplayed()
        compose.onNodeWithTag("membership.choose").performScrollTo().performClick()
        assertEquals(MembershipRoute.Checkout, navigator.stack(MainTab.Profile).last())
    }

    @Test
    fun `the checkout buys the chosen tier`() {
        val offers = PlayProducts.tiers.map { tier ->
            val products = Fixtures.playCatalogue.filter { PlayProducts.entry(it.id)?.tier == tier }
            CheckoutOffer(tier, products.first { it.id.endsWith(".monat") }, products.first { it.id.endsWith(".jahr") })
        }
        val bought = mutableListOf<PlayProduct>()
        show {
            PlayCheckoutContent(
                CheckoutUiState(Loadable.Loaded(offers), canPurchase = true),
                onRetry = {},
                onSelectRhythm = {},
                onPurchase = { bought += it },
                onRestore = {}
            )
        }
        compose.onNodeWithText("Bleib in der Oase.").assertIsDisplayed()
        compose.onNodeWithTag("checkout.price.oase").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("checkout.subscribe.oase").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(PlayProducts.OASE_MONTHLY), bought.map { it.id })
        compose.onNodeWithTag("checkout.restore").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `without the server check the checkout says so and cannot buy`() {
        val offer = CheckoutOffer("oase", Fixtures.product(PlayProducts.OASE_MONTHLY, "5,00 €"), null)
        show {
            PlayCheckoutContent(
                CheckoutUiState(Loadable.Loaded(listOf(offer)), canPurchase = false),
                onRetry = {},
                onSelectRhythm = {},
                onPurchase = {},
                onRestore = {}
            )
        }
        compose.onNodeWithText(PlayCheckoutViewModel.NOT_YET_AVAILABLE).assertIsDisplayed()
        compose.onNodeWithTag("checkout.subscribe.oase").assertIsNotEnabled()
    }

    @Test
    fun `cancelling confirms and goes back to the overview`() {
        navigator.open(MembershipRoute.Overview)
        navigator.open(MembershipRoute.Cancel)
        var confirmed = 0
        val details = CancellationDetails("Oase", "Mia Mitglied", "memsub@example.test", Instant.parse("2026-11-18T15:39:37Z"))
        show { CancelContent(ContractUiState(Loadable.Loaded(details)), onRetry = {}, onConfirm = { confirmed++ }, onExport = {}) }
        compose.onNodeWithText("Ordentlich zum Ende der Abrechnungsperiode").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mia Mitglied").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cancel.confirm").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, confirmed)
        assertEquals(listOf(MembershipRoute.Overview), navigator.stack(MainTab.Profile).toList())
    }

    @Test
    fun `the trial banner counts the days`() {
        show { TrialBannerView(20) }
        compose.onNodeWithText("Noch 20 Tage in deinem Test. Der Test endet von selbst.").assertIsDisplayed()
    }
}
