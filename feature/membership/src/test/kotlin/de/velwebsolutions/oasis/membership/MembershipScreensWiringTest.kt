package de.velwebsolutions.oasis.membership

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.membership.billing.PlayDelivery
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.billing.PurchaseOutcome
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.data.MembershipFiles
import de.velwebsolutions.oasis.membership.ui.CancelScreen
import de.velwebsolutions.oasis.membership.ui.CancelViewModel
import de.velwebsolutions.oasis.membership.ui.InvoiceScreen
import de.velwebsolutions.oasis.membership.ui.InvoiceViewModel
import de.velwebsolutions.oasis.membership.ui.OverviewScreen
import de.velwebsolutions.oasis.membership.ui.OverviewViewModel
import de.velwebsolutions.oasis.membership.ui.PlayCheckoutScreen
import de.velwebsolutions.oasis.membership.ui.PlayCheckoutViewModel
import de.velwebsolutions.oasis.membership.ui.PortalScreen
import de.velwebsolutions.oasis.membership.ui.PortalViewModel
import de.velwebsolutions.oasis.membership.ui.WithdrawScreen
import de.velwebsolutions.oasis.membership.ui.WithdrawViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The screens wired to their real view models (on a fake repository), as the app shows them. */
@RunWith(RobolectricTestRunner::class)
class MembershipScreensWiringTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val repository = FakeMembershipRepository()
    private val files = MembershipFiles(context.cacheDir)
    private val events = MembershipEvents()
    private val navigator = Navigator().apply { selectedTab = MainTab.Profile }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator) { content() } } }
    }

    @Test
    fun `the overview shows the loaded membership`() {
        val viewModel = OverviewViewModel(repository, events)
        show { OverviewScreen(viewModel) }
        compose.onNodeWithTag("membership.active").assertIsDisplayed()
    }

    @Test
    fun `the portal opens an invoice in its sheet and closes it`() {
        val viewModel = PortalViewModel(repository, files)
        show { PortalScreen(viewModel) }
        compose.onNodeWithTag("portal.invoice.2").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("invoice.share").assertExists()
        compose.onNodeWithContentDescription("Schließen").performClick()
        compose.onNodeWithTag("invoice.share").assertDoesNotExist()
    }

    @Test
    fun `an invoice link shows the invoice right away`() {
        val viewModel = InvoiceViewModel(7, repository, files)
        show { InvoiceScreen(7, viewModel) }
        compose.waitForIdle()
        compose.onNodeWithTag("invoice.share").assertExists()
    }

    @Test
    fun `the cancel page loads its contract`() {
        val viewModel = CancelViewModel(repository, files, events, Fixtures.session())
        show { CancelScreen(viewModel) }
        compose.onNodeWithText("Mia Mitglied").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the withdraw page loads its contract`() {
        val viewModel = WithdrawViewModel(repository, files, events, Fixtures.session())
        show { WithdrawScreen(viewModel) }
        compose.onNodeWithText("14-tägiges gesetzliches Widerrufsrecht").performScrollTo().assertIsDisplayed()
    }

    private fun checkout(gateway: FakeBillingGateway): PlayCheckoutViewModel {
        val verifier = FakeVerifier()
        repository.overview = Result.success(Fixtures.overview(Fixtures.noMembershipOverview))
        return PlayCheckoutViewModel(repository, gateway, PlayDelivery(gateway, verifier), verifier, events, Fixtures.session())
    }

    @Test
    fun `a bought membership goes back to the overview`() {
        navigator.open(MembershipRoute.Overview)
        navigator.open(MembershipRoute.Checkout)
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Purchased(listOf(Fixtures.purchase())))
        val viewModel = checkout(gateway)
        show { CompositionLocalProvider(LocalContext provides ContextWrapper(LocalContext.current)) { PlayCheckoutScreen(viewModel) } }
        compose.onNodeWithTag("checkout.subscribe.oase").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(MembershipRoute.Overview), navigator.stack(MainTab.Profile).toList())
        assertEquals(PlayProducts.OASE_MONTHLY, gateway.purchased.single().first)
    }

    @Test
    fun `a purchase that is not completed stays in the checkout, also without an activity`() {
        navigator.open(MembershipRoute.Checkout)
        val gateway = FakeBillingGateway(outcome = PurchaseOutcome.Cancelled)
        val viewModel = checkout(gateway)
        val application: Context = context
        show { CompositionLocalProvider(LocalContext provides application) { PlayCheckoutScreen(viewModel) } }
        compose.onNodeWithTag("checkout.subscribe.oase").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(MembershipRoute.Checkout, navigator.stack(MainTab.Profile).last())
        assertTrue(gateway.purchased.isNotEmpty())
    }
}
