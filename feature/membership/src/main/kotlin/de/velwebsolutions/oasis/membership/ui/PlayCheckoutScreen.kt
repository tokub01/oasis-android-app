package de.velwebsolutions.oasis.membership.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.membership.billing.PlayProduct
import de.velwebsolutions.oasis.membership.billing.PlayProducts

/**
 * "Mitgliedschaft wählen" in the app (iOS `AppleCheckoutView`): the tiers as Google Play subscriptions, monthly or
 * yearly, with Play's price and the terms next to the button (renewal, cancellation, AGB, Datenschutz, "Käufe
 * wiederherstellen"). Prices, names and descriptions come from the Play Console.
 */
@Composable
fun PlayCheckoutScreen(viewModel: PlayCheckoutViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val navigator = LocalNavigator.current
    PlayCheckoutContent(
        state = state,
        onRetry = viewModel::load,
        onSelectRhythm = viewModel::selectRhythm,
        onPurchase = { product -> if (viewModel.purchase(activity, product)) backToOverview(navigator) },
        onRestore = viewModel::restore,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayCheckoutContent(
    state: CheckoutUiState,
    onRetry: suspend () -> Unit,
    onSelectRhythm: (String) -> Unit,
    onPurchase: suspend (PlayProduct) -> Unit,
    onRestore: suspend () -> Unit,
    onDismissMessage: () -> Unit = {},
    onDismissError: () -> Unit = {}
) {
    val openWebsite = rememberOpenWebsite(LocalNavigator.current)
    OasisScreen(
        "Mitglied werden",
        message = state.message,
        error = state.error,
        onDismissMessage = onDismissMessage,
        onDismissError = onDismissError
    ) {
        MembershipPage {
            PageHeader(
                "Bleib in der Oase.",
                eyebrow = "Mitgliedschaft",
                lead = "Werbefrei und ohne Algorithmus, getragen von den Mitgliedern. Bei Abstimmungen hat jedes Mitglied eine Stimme, " +
                    "egal welche Stufe."
            )
            val rhythms = listOf(PlayProducts.MONTHLY to "Monatlich", PlayProducts.YEARLY to "Jährlich · 2 Monate geschenkt")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("checkout.rhythm")) {
                rhythms.forEachIndexed { index, (rhythm, label) ->
                    SegmentedButton(
                        selected = state.rhythm == rhythm,
                        onClick = { onSelectRhythm(rhythm) },
                        shape = SegmentedButtonDefaults.itemShape(index, rhythms.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = OasisColors.Night,
                            activeContentColor = OasisColors.OnNight,
                            inactiveContainerColor = OasisColors.Paper,
                            inactiveContentColor = OasisColors.Night
                        )
                    ) { Text(label, style = OasisType.SmallBold) }
                }
            }
            LoadableView(state.offers, onRetry = onRetry) { offers ->
                when {
                    state.alreadyMember != null -> Text(
                        if (state.alreadyMember == AlreadyMember.Apple) {
                            "Du bist schon Mitglied. Deine Mitgliedschaft läuft über deine Apple-ID; eine zweite brauchst du nicht."
                        } else {
                            "Du bist schon Mitglied. Deine Mitgliedschaft läuft über die Website; eine zweite brauchst du nicht."
                        },
                        style = OasisType.Body,
                        color = OasisColors.Ink2,
                        modifier = Modifier.testTag("checkout.alreadyMember")
                    )

                    offers.isEmpty() -> Text(
                        "Mitgliedschaften können in der App gerade nicht abgeschlossen werden. Bitte versuche es später noch einmal.",
                        style = OasisType.Body,
                        color = OasisColors.Ink2,
                        modifier = Modifier.testTag("checkout.unavailable")
                    )

                    else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!state.canPurchase) {
                            Banner(
                                PlayCheckoutViewModel.NOT_YET_AVAILABLE,
                                Modifier.testTag("checkout.notYetAvailable"),
                                kind = BannerKind.Info
                            )
                        }
                        offers.forEach { offer ->
                            offer.product(state.rhythm)?.let { product ->
                                OfferCard(offer.tier, product, enabled = state.canPurchase && state.purchasing == null, onPurchase)
                            }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Bezahlt wird über dein Google-Konto. Das Abo verlängert sich automatisch um die gewählte Laufzeit, wenn du es " +
                        "nicht vor Ablauf kündigst. Kündigen und die Stufe wechseln kannst du jederzeit in der Google Play Store App " +
                        "unter Profil → Zahlungen & Abos → Abos.",
                    style = OasisType.Caption,
                    color = OasisColors.Muted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InlineLink("AGB", { openWebsite("/agb") }, color = OasisColors.Night)
                    InlineLink("Datenschutz", { openWebsite("/datenschutz") }, color = OasisColors.Night)
                }
                AsyncOasisButton(
                    "Käufe wiederherstellen",
                    onRestore,
                    Modifier.testTag("checkout.restore"),
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false
                )
            }
        }
    }
}

@Composable
private fun OfferCard(tier: String, product: PlayProduct, enabled: Boolean, onPurchase: suspend (PlayProduct) -> Unit) {
    val highlighted = tier == "oase"
    val shape = RoundedCornerShape(OasisRadius.M.dp)
    val price = "${product.price} ${product.period.per}".trim()
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, shape)
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) OasisColors.Night else OasisColors.PaperLine, shape)
            .padding(18.dp)
            .testTag("checkout.tier.$tier"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(product.name, style = OasisType.heading(), color = OasisColors.Night)
        if (product.description.isNotEmpty()) Text(product.description, style = OasisType.Small, color = OasisColors.Ink2)
        Text(price, style = OasisType.BodyBold, color = OasisColors.Night, modifier = Modifier.testTag("checkout.price.$tier"))
        AsyncOasisButton(
            "Abonnieren",
            { onPurchase(product) },
            Modifier
                .testTag("checkout.subscribe.$tier")
                .semantics { contentDescription = "${product.name} für $price abonnieren" },
            kind = if (highlighted) OasisButtonKind.Primary else OasisButtonKind.Secondary,
            enabled = enabled
        )
    }
}

/** The activity Play's purchase sheet opens over. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
