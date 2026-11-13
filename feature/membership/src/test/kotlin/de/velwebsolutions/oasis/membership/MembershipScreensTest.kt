package de.velwebsolutions.oasis.membership

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.membership.api.CancellationDetails
import de.velwebsolutions.oasis.membership.api.MembershipOverview
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
        compose.onNodeWithTag("membership.contractText").assertDoesNotExist()
        compose.onNodeWithTag("membership.withdraw").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("membership.cancel").performScrollTo().performClick()
        assertEquals(MembershipRoute.Cancel, navigator.stack(MainTab.Profile).last())
    }

    @Test
    fun `a member without membership is offered the checkout`() {
        show { OverviewContent(OverviewUiState(Loadable.Loaded(Fixtures.overview(Fixtures.noMembershipOverview))), onRefresh = {}) }
        compose.onNodeWithText("Noch keine Mitgliedschaft").assertIsDisplayed()
        compose.onNodeWithText(
            "Mildcrowd ist werbefrei und wird von den Mitgliedern getragen, deshalb gibt es keinen kostenlosen Zugang. Wähle deinen " +
                "Beitrag, dann geht es los. Bis dahin kannst du in den Einstellungen deine Daten herunterladen oder dein Konto löschen."
        ).assertIsDisplayed()
        compose.onNodeWithTag("membership.choose").performScrollTo().performClick()
        assertEquals(MembershipRoute.Checkout, navigator.stack(MainTab.Profile).last())
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /** Clicks through the semantics, so the snackbar of a toast over the node cannot catch the tap. */
    private fun SemanticsNodeInteraction.tap() = performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `an announced change of the AGB is objected to after a question`() {
        val web = mutableListOf<String>()
        navigator.openWeb = { web += it }
        var objected = 0
        var withdrawn = 0
        show {
            OverviewContent(
                OverviewUiState(Loadable.Loaded(Fixtures.overview(Fixtures.termsChangeOverview))),
                onRefresh = {},
                onObjectToTerms = { objected++ },
                onWithdrawTermsObjection = { withdrawn++ }
            )
        }
        compose.onNodeWithText("Unsere AGB ändern sich am 21.12.2026").assertIsDisplayed()
        compose.onNodeWithText("Wir fassen § 14 neu. Grund: Das Gesetz verlangt es.").assertIsDisplayed()
        compose.onNodeWithText(
            "Wenn du bis zum 21.12.2026 nicht widersprichst, gilt die neue Fassung auch für deinen Vertrag.",
            substring = true
        )
            .assertIsDisplayed()
        compose.onNodeWithTag("membership.withdrawTermsObjection").assertDoesNotExist()
        compose.onNodeWithTag("membership.termsUrl").performClick()
        assertEquals(listOf("http://localhost/agb"), web)

        compose.onNodeWithTag("membership.objectToTerms").tap()
        waitFor("Der Änderung zum 21.12.2026 widersprechen?")
        compose.onNodeWithText("Abbrechen").performClick()
        compose.waitForIdle()
        assertEquals("cancelled", 0, objected)
        compose.onNodeWithTag("membership.objectToTerms").tap()
        waitFor("Für dich gilt dann die bisherige Fassung weiter")
        compose.onNodeWithTag("membership.termsConfirm").performClick()
        compose.waitForIdle()
        assertEquals(1, objected)
        assertEquals(0, withdrawn)
    }

    @Test
    fun `a standing objection is taken back after a question, and a refusal is shown`() {
        var withdrawn = 0
        show {
            OverviewContent(
                OverviewUiState(Loadable.Loaded(Fixtures.overview(Fixtures.termsObjectedOverview)), error = Fixtures.ALREADY_OBJECTED),
                onRefresh = {},
                onWithdrawTermsObjection = { withdrawn++ }
            )
        }
        compose.onNodeWithText(
            "Du hast am 06.11.2026 um 14:32 Uhr widersprochen. Für dich gilt die bisherige Fassung weiter; wir melden uns, falls wir " +
                "deshalb kündigen müssen (AGB § 7)."
        ).assertIsDisplayed()
        compose.onNodeWithTag("membership.objectToTerms").assertDoesNotExist()
        waitFor(Fixtures.ALREADY_OBJECTED)

        compose.onNodeWithTag("membership.withdrawTermsObjection").tap()
        waitFor("Die neue Fassung gilt dann ab dem 21.12.2026 auch für deinen Vertrag.")
        compose.onNodeWithTag("membership.termsConfirm").performClick()
        compose.waitForIdle()
        assertEquals(1, withdrawn)
    }

    @Test
    fun `after the day the objector reads the version that governs, without a way back`() {
        show {
            OverviewContent(OverviewUiState(Loadable.Loaded(Fixtures.overview(Fixtures.termsInEffectObjectedOverview))), onRefresh = {})
        }
        compose.onNodeWithText("Unsere AGB haben sich am 21.12.2026 geändert").assertIsDisplayed()
        compose.onNodeWithText(
            "Du hast am 06.11.2026 um 14:32 Uhr widersprochen. Für dich gilt die bisherige Fassung weiter: die Fassung, der du " +
                "zugestimmt hast (unten unter „Dein Vertragstext“). Wir melden uns, falls wir deshalb kündigen müssen (AGB § 7)."
        ).assertIsDisplayed()
        compose.onNodeWithTag("membership.withdrawTermsObjection").assertDoesNotExist()
        compose.onNodeWithTag("membership.objectToTerms").assertDoesNotExist()
        compose.onNodeWithTag("membership.termsUrl").assertExists()
    }

    @Test
    fun `a terminated contract names its end, and without an objection the change in force is only stated`() {
        var overview by mutableStateOf(Fixtures.overview(Fixtures.termsTerminatedOverview))
        show { OverviewContent(OverviewUiState(Loadable.Loaded(overview)), onRefresh = {}) }
        compose.onNodeWithText(
            "Du hast am 06.11.2026 um 14:32 Uhr widersprochen. Für dich gilt die bisherige Fassung weiter – bis zum Ende deines " +
                "Vertrags am 01.02.2027, den wir deshalb nach AGB § 7 gekündigt haben (Begründung per E-Mail)."
        ).assertIsDisplayed()
        compose.onNodeWithTag("membership.withdrawTermsObjection").assertDoesNotExist()

        overview = Fixtures.overview(Fixtures.withTermsChange(Fixtures.termsChange(inEffect = true)))
        compose.onNodeWithText("Die neue Fassung gilt seit dem 21.12.2026 für deinen Vertrag.").assertIsDisplayed()
        compose.onNodeWithTag("membership.objectToTerms").assertDoesNotExist()
        compose.onNodeWithTag("membership.termsObjected").assertDoesNotExist()
    }

    @Test
    fun `the tier change offers the tiers, asks for the Maezen amount and changes after a question`() {
        var state by mutableStateOf(OverviewUiState(Loadable.Loaded(Fixtures.overview(Fixtures.subscriptionOverview)), tier = "oase"))
        var changed = 0
        show {
            OverviewContent(
                state,
                onRefresh = {},
                onSetTier = { state = state.copy(tier = it) },
                onSetAmount = { state = state.copy(amount = it) },
                onChangeTier = { changed++ }
            )
        }
        compose.onNodeWithTag("membership.tierChange").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "Derzeit: Stufe „Mittendrin“. Die neue Stufe gilt ab sofort, der neue Beitrag ab deiner nächsten Abbuchung am 18.11.2026. " +
                "Wechselst du in eine kleinere Stufe, bleibt Bestehendes erhalten; Neues richtet sich nach der neuen Stufe (AGB § 5)."
        ).assertIsDisplayed()
        compose.onNodeWithText("Mäzen – ab 12\u00A0€ monatlich").assertIsDisplayed()
        compose.onNodeWithTag("membership.tier.amount").assertDoesNotExist()
        compose.onNodeWithTag("membership.tier.maezen").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("maezen", state.tier)
        compose.onNodeWithContentDescription("Dein Monatsbeitrag in Euro (ab 12)").performScrollTo().performTextInput("15")
        compose.waitForIdle()
        assertEquals("15", state.amount)

        state =
            state.copy(
                tierErrors = mapOf("tier" to "Diese Stufe gibt es nicht.", "amount" to "Der Beitrag liegt unter dem Mindestbeitrag.")
            )
        compose.onNodeWithText("Diese Stufe gibt es nicht.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Der Beitrag liegt unter dem Mindestbeitrag.").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("membership.tier.change").tap()
        waitFor("Stufe jetzt ändern?")
        compose.onNodeWithText(
            "Die neue Stufe gilt ab sofort, der neue Beitrag ab deiner nächsten Abbuchung. Die Bestätigung mit dem Vertragstext bekommst du per E-Mail."
        ).assertIsDisplayed()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.waitForIdle()
        assertEquals("cancelled", 0, changed)
        compose.onNodeWithTag("membership.tier.change").tap()
        waitFor("Stufe jetzt ändern?")
        compose.onNodeWithTag("membership.tier.confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, changed)

        state = state.copy(tierBusy = true)
        compose.onNodeWithTag("membership.tier.change").performScrollTo().assertIsNotEnabled()
        state = state.copy(tierBusy = false, tier = "")
        compose.onNodeWithTag("membership.tier.change").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `store and cancelled memberships change their tier elsewhere or not at all`() {
        val subscription = Fixtures.overview(Fixtures.subscriptionOverview)
        var overview by mutableStateOf(
            subscription.copy(
                apple = MembershipOverview.Apple(managedByApple = true, manageUrl = "https://apps.apple.com/account/subscriptions")
            )
        )
        show { OverviewContent(OverviewUiState(Loadable.Loaded(overview), tier = "oase"), onRefresh = {}) }
        compose.onNodeWithTag("membership.tierChange").assertDoesNotExist()
        overview =
            subscription.copy(
                subscription = subscription.subscription?.copy(onGracePeriod = true, endsAt = Instant.parse("2026-11-18T15:39:37Z"))
            )
        compose.onNodeWithTag("membership.gracePeriod").assertExists()
        compose.onNodeWithTag("membership.tierChange").assertDoesNotExist()
    }

    @Test
    fun `while the objection is on its way the button waits`() {
        show {
            OverviewContent(
                OverviewUiState(
                    Loadable.Loaded(Fixtures.overview(Fixtures.termsChangeOverview)),
                    termsBusy = true
                ),
                onRefresh = {
                }
            )
        }
        compose.onNodeWithTag("membership.objectToTerms").assertIsNotEnabled()
    }

    @Test
    fun `the checkout buys the chosen tier`() {
        val offers = Catalogue.tiers.map { tier ->
            val products = Fixtures.playCatalogue.filter { Catalogue.entry(it.id)?.tier == tier }
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
        compose.onNodeWithText("Bleib in der Mildcrowd.").assertIsDisplayed()
        compose.onNodeWithTag("checkout.price.oase").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("checkout.subscribe.oase").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Catalogue.OASE_MONTHLY), bought.map { it.id })
        compose.onNodeWithTag("checkout.restore").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `without the server check the checkout says so and cannot buy`() {
        val offer = CheckoutOffer("oase", Fixtures.product(Catalogue.OASE_MONTHLY, "5,00 €"), null)
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
        val details = CancellationDetails("Mittendrin", "Mia Mitglied", "memsub@example.test", Instant.parse("2026-11-18T15:39:37Z"))
        show { CancelContent(ContractUiState(Loadable.Loaded(details)), onRetry = {}, onConfirm = { confirmed++ }, onExport = {}) }
        compose.onNodeWithText("Ordentlich zum Ende der Abrechnungsperiode").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mia Mitglied").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cancel.confirm").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, confirmed)
        assertEquals(listOf(MembershipRoute.Overview), navigator.stack(MainTab.Profile).toList())
    }
}
