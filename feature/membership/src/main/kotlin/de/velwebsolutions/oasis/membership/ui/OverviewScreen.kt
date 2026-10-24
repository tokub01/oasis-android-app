package de.velwebsolutions.oasis.membership.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.api.MembershipOverview

/**
 * /app/mitgliedschaft (App\Livewire\Membership\Overview) in the app: status, vouchers bought on the web and the aside
 * cards. Buying happens through Google Play (PlayCheckoutScreen); redeeming and buying vouchers stays on the web.
 */
@Composable
fun OverviewScreen(viewModel: OverviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OverviewContent(
        state,
        onRefresh = viewModel::load,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    )
}

@Composable
fun OverviewContent(
    state: OverviewUiState,
    onRefresh: suspend () -> Unit,
    onDismissMessage: () -> Unit = {},
    onDismissError: () -> Unit = {}
) {
    OasisScreen(
        title = "Mitgliedschaft",
        message = state.message,
        error = state.error,
        onDismissMessage = onDismissMessage,
        onDismissError = onDismissError
    ) {
        MembershipPage(onRefresh) {
            PageHeader("Mitgliedschaft", eyebrow = "Konto", lead = "Verwalte deinen Beitrag, deine Rechnungen und deinen Status bei Oasis.")
            LoadableView(state.overview, onRetry = onRefresh) { overview -> OverviewSections(overview) }
        }
    }
}

@Composable
private fun OverviewSections(overview: MembershipOverview) {
    val navigator = LocalNavigator.current
    val openWebsite = rememberOpenWebsite(navigator)
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        StatusCard(overview)
        if (overview.purchasedGifts.isNotEmpty()) {
            SectionCard("Deine gekauften Gutscheine") {
                overview.purchasedGifts.forEachIndexed { index, gift ->
                    Column(Modifier.testTag("membership.purchasedGift"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SelectionContainer { Text(gift.code, style = OasisType.Digits, color = OasisColors.Night) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${gift.tierName} · ${if (gift.redeemed) "Bereits eingelöst" else "Zum Weitergeben"}",
                                style = OasisType.Small,
                                color = OasisColors.Ink2,
                                modifier = Modifier.weight(1f)
                            )
                            if (!gift.redeemed) {
                                Row(
                                    Modifier.pressable({
                                        FileSharing.shareText(context, gift.code, "Weitergeben")
                                    }).padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Share,
                                        contentDescription = null,
                                        tint = OasisColors.Night,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text("Weitergeben", style = OasisType.SmallBold, color = OasisColors.Night)
                                }
                            }
                        }
                    }
                    if (index != overview.purchasedGifts.lastIndex) HorizontalDivider(color = OasisColors.PaperLine)
                }
            }
        }
        SideCard("Fair finanziert", tone = PanelTone.Night) {
            Text(
                "Oasis ist werbefrei und lebt von den Beiträgen seiner Mitglieder. Bei Abstimmungen hat jedes Mitglied eine Stimme, egal welche Stufe."
            )
            InlineLink("Zu den Abstimmungen", { navigator.open(AccountRoute.Votes) }, color = OasisColors.OnNight)
        }
        SideCard("Gut zu wissen", tone = PanelTone.Dune) {
            InlineLink("Stufen & Preise", { openWebsite("/preise") }, color = OasisColors.Night)
            InlineLink("AGB", { openWebsite("/agb") }, color = OasisColors.Night)
            InlineLink("Hilfe & Kontakt", { openWebsite("/hilfe") }, color = OasisColors.Night)
        }
    }
}

/** The status card (livewire/membership/partials/status.blade.php): subscription, gift, trial or read-only mode. */
@Composable
fun StatusCard(overview: MembershipOverview) {
    SectionCard("Status", Modifier.testTag("membership.statusCard")) {
        when (overview.state) {
            MembershipOverview.State.Subscription -> SubscriptionStatus(overview)
            MembershipOverview.State.Gift -> GiftStatus(overview)
            MembershipOverview.State.Trial -> TrialStatus(overview)
            MembershipOverview.State.Expired -> ExpiredStatus(overview)
        }
    }
}

/** Buying in the app needs the server's switch for in-app subscriptions (`apple.available`). */
private val MembershipOverview.canBuy: Boolean get() = apple?.available == true

/** The membership runs through Apple: it is changed and cancelled in the App Store, not here. */
private val MembershipOverview.managedByApple: Boolean get() = apple?.managedByApple == true

@Composable
private fun SubscriptionStatus(overview: MembershipOverview) {
    val subscription = overview.subscription ?: return
    val navigator = LocalNavigator.current
    StatusPill("Aktive Mitgliedschaft", Icons.Outlined.Check, Modifier.testTag("membership.active"))
    if (overview.isFoundingMember) FoundingPill()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile("Stufe", subscription.tierName, Modifier.weight(1f))
        Tile(
            "Abrechnungsstatus",
            if (subscription.onGracePeriod) "Gekündigt zum ${MembershipFormat.day(subscription.endsAt)}" else "Aktiv",
            Modifier.weight(1f)
        )
    }
    if (subscription.onGracePeriod) {
        Text(
            boldMarkdown(
                "**Kündigung vorgemerkt:** Dein Vertrag endet am ${MembershipFormat.day(
                    subscription.endsAt
                )}. Bis dahin kannst du Oasis ohne " +
                    "Einschränkungen weiternutzen. Danach wechselt dein Konto in den Lesemodus."
            ),
            style = OasisType.Small,
            color = OasisColors.Ink,
            modifier = Modifier
                .fillMaxWidth()
                .background(OasisColors.Sun.copy(alpha = 0.2f), RoundedCornerShape(OasisRadius.CHIP.dp))
                .padding(14.dp)
                .testTag("membership.gracePeriod")
        )
    }
    if (overview.managedByApple) {
        Text(
            "Deine Mitgliedschaft läuft über deine Apple-ID. Stufe, Laufzeit und Kündigung verwaltest du im App Store.",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
        val manageUrl = overview.apple?.manageUrl
        if (!manageUrl.isNullOrEmpty()) {
            OasisButton("Abo verwalten", { navigator.open(WebRoute(manageUrl)) }, Modifier.testTag("membership.manageApple"))
        }
    } else {
        OasisButton("Rechnungen ansehen", { navigator.open(MembershipRoute.Portal) }, Modifier.testTag("membership.portal"))
    }
    if (overview.canCancel && !overview.managedByApple) {
        InlineLink("Vertrag kündigen", { navigator.open(MembershipRoute.Cancel) }, Modifier.testTag("membership.cancel"), OasisColors.Night)
    }
    if (overview.canWithdraw && !overview.managedByApple) {
        InlineLink(
            "Mitgliedschaft widerrufen (14 Tage)",
            { navigator.open(MembershipRoute.Withdraw) },
            Modifier.testTag("membership.withdraw"),
            OasisColors.Muted
        )
    }
}

@Composable
private fun GiftStatus(overview: MembershipOverview) {
    val gift = overview.gift ?: return
    StatusPill("Geschenkte Mitgliedschaft", Icons.Outlined.CardGiftcard, Modifier.testTag("membership.giftActive"))
    Text(
        boldMarkdown(
            "Deine durch einen Gutschein aktivierte Mitgliedschaft (Stufe: **${gift.tierName}**) ist bis zum " +
                "**${MembershipFormat.day(gift.expiresAt)}** aktiv."
        ),
        style = OasisType.Body,
        color = OasisColors.Ink
    )
    if (overview.isFoundingMember) FoundingPill()
}

@Composable
private fun TrialStatus(overview: MembershipOverview) {
    val navigator = LocalNavigator.current
    StatusPill("Kostenloser Test", Icons.Outlined.Schedule, Modifier.testTag("membership.trial"), StatusTone.Dune)
    if (overview.isFoundingMember) FoundingPill()
    Text(
        boldMarkdown(
            "Du hast noch **${MembershipFormat.days(overview.trialDaysLeft ?: 0)}** in deinem kostenlosen 30-Tage-Test " +
                "(endet am ${MembershipFormat.day(overview.trialEndsAt)})."
        ),
        style = OasisType.Body,
        color = OasisColors.Ink
    )
    val seats = if (overview.remainingFoundingSeats > 0) {
        " Noch ${MembershipFormat.count(overview.remainingFoundingSeats)} Plätze mit dauerhafter Preisgarantie verfügbar."
    } else {
        ""
    }
    Text("Der Test endet von selbst. Es entstehen dir keine Kosten.$seats", style = OasisType.Small, color = OasisColors.Muted)
    if (overview.canBuy) {
        OasisButton("Mitgliedschaft wählen", { navigator.open(MembershipRoute.Checkout) }, Modifier.testTag("membership.choose"))
    }
}

@Composable
private fun ExpiredStatus(overview: MembershipOverview) {
    val navigator = LocalNavigator.current
    StatusPill("Test abgelaufen · Lesemodus", Icons.Outlined.Lock, Modifier.testTag("membership.expired"), StatusTone.Sun)
    Text(
        "Dein kostenloser Test ist beendet. Dein Konto befindet sich im Lesemodus: Du kannst alle Inhalte lesen, dein Konto exportieren " +
            "und löschen. Um wieder Beiträge zu verfassen und Nachrichten zu senden, wähle einen Mitgliedsbeitrag.",
        style = OasisType.Body,
        color = OasisColors.Ink
    )
    if (overview.canBuy) {
        OasisButton("Mitglied werden", { navigator.open(MembershipRoute.Checkout) }, Modifier.testTag("membership.choose"))
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.CHIP.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = OasisType.Caption.copy(fontWeight = FontWeight.Bold), color = OasisColors.Muted)
        Text(value, style = OasisType.BodyBold, color = OasisColors.Night)
    }
}
