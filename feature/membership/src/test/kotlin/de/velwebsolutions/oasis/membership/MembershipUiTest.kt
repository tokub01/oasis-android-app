package de.velwebsolutions.oasis.membership

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.membership.api.CancellationDetails
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.MembershipPayment
import de.velwebsolutions.oasis.membership.api.WithdrawalDetails
import de.velwebsolutions.oasis.membership.billing.PlayProducts
import de.velwebsolutions.oasis.membership.data.MembershipFiles
import de.velwebsolutions.oasis.membership.ui.AlreadyMember
import de.velwebsolutions.oasis.membership.ui.CancelContent
import de.velwebsolutions.oasis.membership.ui.CheckoutOffer
import de.velwebsolutions.oasis.membership.ui.CheckoutUiState
import de.velwebsolutions.oasis.membership.ui.ContractUiState
import de.velwebsolutions.oasis.membership.ui.InvoiceContent
import de.velwebsolutions.oasis.membership.ui.InvoiceSheet
import de.velwebsolutions.oasis.membership.ui.InvoiceUiState
import de.velwebsolutions.oasis.membership.ui.OverviewContent
import de.velwebsolutions.oasis.membership.ui.OverviewUiState
import de.velwebsolutions.oasis.membership.ui.PlayCheckoutContent
import de.velwebsolutions.oasis.membership.ui.PortalContent
import de.velwebsolutions.oasis.membership.ui.PortalUiState
import de.velwebsolutions.oasis.membership.ui.WithdrawContent
import de.velwebsolutions.oasis.testing.StubTransport
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A lifecycle the test moves by hand (the Custom Tab of "Abo verwalten" pauses and resumes the app). */
private class TestLifecycle : LifecycleOwner {
    val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
}

/** Every membership screen in each of its states, with a Roborazzi screenshot of each. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MembershipUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val web = mutableListOf<String>()
    private val navigator = Navigator().apply {
        selectedTab = MainTab.Profile
        openWeb = { web += it }
    }
    private val lifecycle = TestLifecycle()
    private val files = MembershipFiles(context.cacheDir)
    private val subscription = Fixtures.overview(Fixtures.subscriptionOverview)
    private val trial = Fixtures.overview(Fixtures.trialOverview)

    private fun show(withClient: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(
                    LocalNavigator provides navigator,
                    LocalApiClient provides if (withClient) StubTransport().client else null,
                    LocalLifecycleOwner provides lifecycle
                ) { content() }
            }
        }
    }

    /** FileProvider caches its roots per authority, but every Robolectric test has its own cache directory. */
    @org.junit.Before
    fun forgetFileProviderRoots() {
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {
            isAccessible = true
        }.let { (it.get(null) as MutableMap<*, *>).clear() }
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/membership-$name.png")

    private fun shotDialog(name: String) = compose.onAllNodes(isRoot())[1].captureRoboImage("build/outputs/roborazzi/membership-$name.png")

    private val top get() = navigator.stack(MainTab.Profile).lastOrNull()

    private fun overview(overview: MembershipOverview, withClient: Boolean = true, managed: () -> Unit = {}) =
        show(withClient) { OverviewContent(OverviewUiState(Loadable.Loaded(overview)), onRefresh = {}, onSubscriptionManaged = managed) }

    @Test
    fun `the overview loads, fails and refreshes`() {
        var refreshed = 0
        var state by mutableStateOf(OverviewUiState(Loadable.Loading))
        show { OverviewContent(state, onRefresh = { refreshed++ }) }
        shot("overview-loading")
        state = OverviewUiState(Loadable.Failed(ApiError.Server(500, "Server Error")))
        compose.onNodeWithText("Noch einmal versuchen").assertIsDisplayed()
        shot("overview-error")
        state = OverviewUiState(Loadable.Loaded(trial), message = "Erledigt.", error = "Nicht gelungen.")
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.9f) }
        compose.waitForIdle()
        assertTrue(refreshed > 0)
        shot("overview-toasts")
    }

    @Test
    fun `a cancelled subscription shows its end and the way to the invoices`() {
        val cancelled = subscription.copy(
            subscription = subscription.subscription!!.copy(onGracePeriod = true, endsAt = Instant.parse("2026-11-18T15:39:37Z")),
            canWithdraw = false
        )
        overview(cancelled)
        compose.onNodeWithTag("membership.gracePeriod").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Gekündigt zum 18.11.2026").assertIsDisplayed()
        shot("overview-grace")
        compose.onNodeWithTag("membership.portal").performScrollTo().performClick()
        assertEquals(MembershipRoute.Portal, top)
        compose.onNodeWithTag("membership.withdraw").assertDoesNotExist()
    }

    @Test
    fun `an Apple subscription is managed at Apple and reloads on return`() {
        var managed = 0
        val apple = MembershipOverview.Apple(managedByApple = true, manageUrl = "https://apps.apple.com/account/subscriptions")
        overview(subscription.copy(apple = apple)) { managed++ }
        compose.onNodeWithTag("membership.cancel").assertDoesNotExist()
        compose.onNodeWithTag("membership.withdraw").assertDoesNotExist()
        shot("overview-apple")
        compose.onNodeWithTag("membership.manageApple").performScrollTo().performClick()
        assertEquals("https://apps.apple.com/account/subscriptions", web.last())
        lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        compose.waitForIdle()
        assertEquals(1, managed)
        lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        compose.waitForIdle()
        assertEquals("only the return from Apple's page reloads", 1, managed)
    }

    @Test
    fun `an Apple subscription without the manage link has no button`() {
        overview(subscription.copy(apple = MembershipOverview.Apple(managedByApple = true)))
        compose.onNodeWithText("Deine Mitgliedschaft läuft über deine Apple-ID. Stufe, Laufzeit und Kündigung verwaltest du im App Store.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("membership.manageApple").assertDoesNotExist()
    }

    @Test
    fun `a gifted membership, vouchers to pass on and the side cards`() {
        val gift = subscription.copy(
            state = MembershipOverview.State.Gift,
            gift = MembershipOverview.Gift("Oase", Instant.parse("2027-01-31T12:00:00Z")),
            purchasedGifts = listOf(
                MembershipOverview.PurchasedGift(1, "OASIS-GIFT-1", "Oase"),
                MembershipOverview.PurchasedGift(2, "OASIS-GIFT-2", "Solidar", redeemed = true)
            )
        )
        overview(gift)
        compose.onNodeWithTag("membership.giftActive").assertIsDisplayed()
        compose.onNodeWithText("Solidar · Bereits eingelöst").performScrollTo().assertIsDisplayed()
        shot("overview-gift")
        compose.onNodeWithText("Weitergeben").performScrollTo().performClick()
        assertNotNull(shadowOf(context).nextStartedActivity)
        compose.onNodeWithText("Zu den Abstimmungen").performScrollTo().performClick()
        assertEquals(AccountRoute.Votes, top)
        compose.onNodeWithText("Stufen & Preise").performScrollTo().performClick()
        assertEquals("http://localhost/preise", web.last())
        compose.onNodeWithText("AGB").performScrollTo().performClick()
        compose.onNodeWithText("Hilfe & Kontakt").performScrollTo().performClick()
        assertEquals("http://localhost/hilfe", web.last())
    }

    @Test
    fun `without a server the website links do nothing`() {
        overview(subscription, withClient = false)
        compose.onNodeWithText("AGB").performScrollTo().performClick()
        assertEquals(null, top)
    }

    @Test
    fun `a trial member without founding seats and switched-off purchases`() {
        overview(trial.copy(remainingFoundingSeats = 0, trialDaysLeft = null, isFoundingMember = true, apple = null))
        compose.onNodeWithText("Der Test endet von selbst. Es entstehen dir keine Kosten.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("membership.founding").assertIsDisplayed()
        compose.onNodeWithTag("membership.choose").assertDoesNotExist()
        shot("overview-trial")
    }

    @Test
    fun `read-only mode offers the membership when Play may sell`() {
        val expired = trial.copy(state = MembershipOverview.State.Expired)
        overview(expired)
        compose.onNodeWithTag("membership.expired").assertIsDisplayed()
        shot("overview-expired")
        compose.onNodeWithTag("membership.choose").performScrollTo().performClick()
        assertEquals(MembershipRoute.Checkout, top)
    }

    @Test
    fun `read-only mode without in-app purchases`() {
        overview(trial.copy(state = MembershipOverview.State.Expired, apple = null))
        compose.onNodeWithTag("membership.choose").assertDoesNotExist()
    }

    @Test
    fun `states without their details show only the card`() {
        overview(subscription.copy(subscription = null))
        compose.onNodeWithTag("membership.active").assertDoesNotExist()
    }

    @Test
    fun `a gift state without its gift shows only the card`() {
        overview(subscription.copy(state = MembershipOverview.State.Gift, gift = null))
        compose.onNodeWithTag("membership.giftActive").assertDoesNotExist()
    }

    @Test
    fun `the invoices list, an invoice loading and the way back`() {
        val payments = listOf(
            MembershipPayment(2, "OAS-2", Instant.parse("2026-10-19T15:39:37Z"), "5 €"),
            MembershipPayment(3, "OAS-3", Instant.parse("2026-11-19T15:39:37Z"), "5 €", refunded = true)
        )
        val opened = mutableListOf<Int>()
        navigator.open(MembershipRoute.Overview)
        navigator.open(MembershipRoute.Portal)
        show {
            PortalContent(PortalUiState(Loadable.Loaded(payments), loadingInvoice = 3), onRefresh = {}, onOpenInvoice = { opened += it.id })
        }
        compose.onNodeWithText("5 € · erstattet").assertIsDisplayed()
        shot("portal")
        compose.onNodeWithTag("portal.invoice.2").performClick()
        assertEquals(listOf(2), opened)
        compose.onNodeWithText("Hilfe & Kontakt").performScrollTo().performClick()
        compose.onNodeWithText("Zur Mitgliedschaft").performScrollTo().performClick()
        assertEquals(listOf(MembershipRoute.Overview), navigator.stack(MainTab.Profile).filterIsInstance<MembershipRoute>())
    }

    @Test
    fun `no invoices yet, and the way back from a deep link`() {
        navigator.present(MembershipRoute.Portal)
        show { PortalContent(PortalUiState(Loadable.Loaded(emptyList())), onRefresh = {}, onOpenInvoice = {}) }
        compose.onNodeWithTag("portal.empty").assertIsDisplayed()
        shot("portal-empty")
        compose.onNodeWithText("Zur Mitgliedschaft").performScrollTo().performClick()
        assertEquals(MembershipRoute.Overview, navigator.stack(MainTab.Profile).last())
    }

    @Test
    fun `an invoice link shows the invoice, shares and prints it`() {
        val file = files.invoice("<html><body>Rechnung OAS-2</body></html>".toByteArray(), "OAS-2")
        var shown = 0
        var closed = 0
        show { InvoiceContent(2, InvoiceUiState(Loadable.Loaded(file), preview = file), {}, { shown++ }, { closed++ }) }
        compose.onAllNodesWithTag("invoice.document")[0].assertExists()
        shotDialog("invoice-sheet")
        compose.onNodeWithContentDescription("Drucken oder als PDF sichern").performClick()
        compose.onNodeWithContentDescription("Teilen").performClick()
        assertNotNull(shadowOf(context).nextStartedActivity)
        compose.onNodeWithContentDescription("Schließen").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `the invoice page before the sheet opens`() {
        val file = files.invoice("<html></html>".toByteArray(), "OAS-4")
        var shown = 0
        show { InvoiceContent(4, InvoiceUiState(Loadable.Loaded(file)), {}, { shown++ }, {}) }
        compose.onNodeWithText("Rechnung OAS-4").assertIsDisplayed()
        shot("invoice")
        compose.onNodeWithTag("invoice.view").performClick()
        compose.onNodeWithTag("invoice.pageShare").performClick()
        assertEquals(1, shown)
    }

    @Test
    fun `a file the app cannot show is offered for sharing`() {
        val pdf = File(context.cacheDir, "Rechnungen/Rechnung-OAS-5.pdf").apply {
            parentFile?.mkdirs()
            writeText("%PDF")
        }
        show { InvoiceSheet(pdf) {} }
        compose.onNodeWithText("Über „Teilen“ kannst du die Rechnung speichern, drucken oder als PDF sichern.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drucken oder als PDF sichern").assertDoesNotExist()
        shotDialog("invoice-pdf")
    }

    @Test
    fun `a missing invoice shows the error`() {
        show { InvoiceContent(9, InvoiceUiState(Loadable.Failed(ApiError.NotFound)), {}, {}, {}) }
        shot("invoice-error")
    }

    private val cancellation = CancellationDetails("Oase", "Mia Mitglied", "mia@example.test", Instant.parse("2026-11-18T15:39:37Z"))

    @Test
    fun `the cancel page shows loading, the confirmation, the export and the public form`() {
        var state by mutableStateOf(ContractUiState<CancellationDetails>(Loadable.Loading))
        val gate = CompletableDeferred<Unit>()
        var exports = 0
        show {
            CancelContent(state, onRetry = {}, onConfirm = {}, onExport = {
                exports++
                gate.await()
            })
        }
        shot("cancel-loading")
        state = ContractUiState(Loadable.Loaded(cancellation.copy(effectiveDate = null)))
        compose.onNodeWithText("Deine Mitgliedschaft bleibt bis zum Periodenende vollständig aktiv.").performScrollTo().assertIsDisplayed()
        shot("cancel")
        compose.onNodeWithTag("contract.export").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("contract.export").performClick()
        compose.waitForIdle()
        assertEquals("one export at a time", 1, exports)
        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("Verträge hier kündigen").performScrollTo().performClick()
        assertEquals("http://localhost/vertraege-kuendigen", web.last())
        compose.onNodeWithText("Abbrechen und zurück zur Mitgliedschaft").performScrollTo().performClick()
        assertEquals(MembershipRoute.Overview, top)
    }

    @Test
    fun `a contract page that is not reachable says why, other errors offer a retry`() {
        var state by mutableStateOf(
            ContractUiState<CancellationDetails>(Loadable.Failed(ApiError.Conflict("Du hast derzeit kein aktives Abonnement.")))
        )
        show { CancelContent(state, onRetry = {}, onConfirm = {}, onExport = {}) }
        compose.onNodeWithTag("membership.notAvailable").assertIsDisplayed()
        shot("cancel-unavailable")
        compose.onNodeWithText("Zur Mitgliedschaft").performClick()
        assertEquals(MembershipRoute.Overview, top)
        state = ContractUiState(Loadable.Failed(ApiError.Server(500, "Server Error")))
        compose.onNodeWithText("Noch einmal versuchen").assertIsDisplayed()
    }

    @Test
    fun `the withdraw page with the export ready to share`() {
        val export = files.export("PK".toByteArray())
        var confirmed = 0
        show {
            WithdrawContent(
                ContractUiState(Loadable.Loaded(WithdrawalDetails("Oase", "Mia Mitglied", "mia@example.test")), exportFile = export),
                onRetry = {},
                onConfirm = { confirmed++ },
                onExport = {}
            )
        }
        compose.onNodeWithText("14-tägiges gesetzliches Widerrufsrecht").performScrollTo().assertIsDisplayed()
        shot("withdraw")
        compose.onNodeWithTag("contract.exportShare").performScrollTo().performClick()
        assertNotNull(shadowOf(context).nextStartedActivity)
        compose.onNodeWithText("Widerrufsbelehrung lesen").performScrollTo().performClick()
        assertEquals("http://localhost/widerrufsbelehrung", web.last())
        compose.onNodeWithTag("withdraw.confirm").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, confirmed)
    }

    private val offers = PlayProducts.tiers.map { tier ->
        val products = Fixtures.playCatalogue.filter { PlayProducts.entry(it.id)?.tier == tier }
        CheckoutOffer(tier, products.first { it.id.endsWith(".monat") }, products.first { it.id.endsWith(".jahr") })
    }

    @Test
    fun `the checkout in the yearly rhythm, its terms and restoring`() {
        val rhythms = mutableListOf<String>()
        var restored = 0
        show {
            PlayCheckoutContent(
                CheckoutUiState(
                    Loadable.Loaded(offers + CheckoutOffer("leer", null, null)),
                    rhythm = PlayProducts.YEARLY,
                    canPurchase = true
                ),
                onRetry = {},
                onSelectRhythm = { rhythms += it },
                onPurchase = {},
                onRestore = { restored++ }
            )
        }
        compose.onNodeWithText("50,00 € / Jahr").performScrollTo().assertIsDisplayed()
        shot("checkout-yearly")
        compose.onNodeWithText("Monatlich").performClick()
        assertEquals(listOf(PlayProducts.MONTHLY), rhythms)
        compose.onNodeWithText("Datenschutz").performScrollTo().performClick()
        assertEquals("http://localhost/datenschutz", web.last())
        compose.onNodeWithText("AGB").performScrollTo().performClick()
        compose.onNodeWithTag("checkout.restore").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, restored)
    }

    @Test
    fun `the checkout with a described product`() {
        val described = CheckoutOffer("oase", Fixtures.product(PlayProducts.OASE_MONTHLY, "5,00 €").copy(description = "Werbefrei"), null)
        show {
            PlayCheckoutContent(CheckoutUiState(Loadable.Loaded(listOf(described)), canPurchase = true), {}, {}, {}, {})
        }
        compose.onNodeWithText("Werbefrei").assertIsDisplayed()
    }

    @Test
    fun `members are not sold a second membership, and nothing to buy says so`() {
        var state by mutableStateOf(CheckoutUiState(Loadable.Loaded(emptyList()), alreadyMember = AlreadyMember.Web))
        show { PlayCheckoutContent(state, {}, {}, {}, {}) }
        compose.onNodeWithText("Du bist schon Mitglied. Deine Mitgliedschaft läuft über die Website; eine zweite brauchst du nicht.")
            .assertIsDisplayed()
        shot("checkout-member")
        state = CheckoutUiState(Loadable.Loaded(emptyList()), alreadyMember = AlreadyMember.Apple)
        compose.onNodeWithText("Du bist schon Mitglied. Deine Mitgliedschaft läuft über deine Apple-ID; eine zweite brauchst du nicht.")
            .assertIsDisplayed()
        state = CheckoutUiState(Loadable.Loaded(emptyList()))
        compose.onNodeWithTag("checkout.unavailable").assertIsDisplayed()
        shot("checkout-unavailable")
        state = CheckoutUiState(Loadable.Loading)
        shot("checkout-loading")
    }

    @Test
    fun `the trial banner for the signed-in member`() {
        val me = Me(1, "mia", "Mia", "M", "mia@example.test", trialEndsAt = Instant.now().plus(5, ChronoUnit.DAYS).minusSeconds(60))
        var member by mutableStateOf(me)
        show { TrialBanner(member) }
        compose.onNodeWithTag("membership.trialBanner").assertIsDisplayed()
        shot("trial-banner")
        member = me.copy(membershipTier = "oase")
        compose.onNodeWithTag("membership.trialBanner").assertDoesNotExist()
    }
}
