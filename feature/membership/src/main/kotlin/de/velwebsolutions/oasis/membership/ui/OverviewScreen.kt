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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
        onDismissError = viewModel::dismissError,
        onSubscriptionManaged = viewModel::subscriptionManaged
    )
}

/** The overview without its view model, for the app and for tests. */
@Composable
fun OverviewContent(
    state: OverviewUiState,
    onRefresh: suspend () -> Unit,
    onDismissMessage: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onSubscriptionManaged: () -> Unit = {}
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
            LoadableView(state.overview, onRetry = onRefresh) { overview -> OverviewSections(overview, onSubscriptionManaged) }
        }
    }
}

@Composable
private fun OverviewSections(overview: MembershipOverview, onSubscriptionManaged: () -> Unit) {
    val navigator = LocalNavigator.current
    val openWebsite = rememberOpenWebsite(navigator)
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        StatusCard(overview, onSubscriptionManaged)
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

/** The status card (livewire/membership/partials/status.blade.php): subscription, gift, or no membership yet. */
@Composable
fun StatusCard(overview: MembershipOverview, onSubscriptionManaged: () -> Unit = {}) {
    SectionCard("Status", Modifier.testTag("membership.statusCard")) {
        when (overview.state) {
            MembershipOverview.State.Subscription -> SubscriptionStatus(overview, onSubscriptionManaged)

            MembershipOverview.State.Gift -> GiftStatus(overview)

            // State.Expired, the last state.
            else -> ExpiredStatus(overview)
        }
    }
}

/** Buying in the app needs the server's switch for Google Play subscriptions (`google.available`). */
private val MembershipOverview.canBuy: Boolean get() = google?.available == true

/** A membership billed by a store: changed and cancelled there, not here. */
data class StoreManagement(val hint: String, val manageUrl: String?, val tag: String)

/** Where the running membership is managed: in the App Store (bought on an iPhone), in the Play Store, or here (null). */
fun MembershipOverview.storeManagement(): StoreManagement? = when {
    apple?.managedByApple == true -> StoreManagement(
        "Deine Mitgliedschaft läuft über deine Apple-ID. Stufe, Laufzeit und Kündigung verwaltest du im App Store.",
        apple.manageUrl,
        "membership.manageApple"
    )

    google?.managedByGoogle == true -> StoreManagement(
        "Deine Mitgliedschaft läuft über Google Play. Stufe, Laufzeit und Kündigung verwaltest du im Play Store.",
        google.manageUrl,
        "membership.manageGoogle"
    )

    else -> null
}

@Composable
private fun SubscriptionStatus(overview: MembershipOverview, onSubscriptionManaged: () -> Unit) {
    val subscription = overview.subscription ?: return
    val navigator = LocalNavigator.current
    // Back from the store's subscription page (a Custom Tab): the membership may have changed there (iOS reloads when
    // its manage sheet closes).
    var managingSubscription by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (managingSubscription) {
            managingSubscription = false
            onSubscriptionManaged()
        }
    }
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
    val store = overview.storeManagement()
    if (store != null) {
        Text(store.hint, style = OasisType.Small, color = OasisColors.Ink2)
        val manageUrl = store.manageUrl
        if (!manageUrl.isNullOrEmpty()) {
            OasisButton(
                "Abo verwalten",
                {
                    managingSubscription = true
                    navigator.open(WebRoute(manageUrl))
                },
                Modifier.testTag(store.tag)
            )
        }
    } else {
        OasisButton("Rechnungen ansehen", { navigator.open(MembershipRoute.Portal) }, Modifier.testTag("membership.portal"))
    }
    if (overview.canCancel && store == null) {
        InlineLink("Vertrag kündigen", { navigator.open(MembershipRoute.Cancel) }, Modifier.testTag("membership.cancel"), OasisColors.Night)
    }
    if (overview.canWithdraw && store == null) {
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
private fun ExpiredStatus(overview: MembershipOverview) {
    val navigator = LocalNavigator.current
    StatusPill("Noch keine Mitgliedschaft", Icons.Outlined.Lock, Modifier.testTag("membership.expired"), StatusTone.Sun)
    Text(
        "Oasis ist werbefrei und wird von den Mitgliedern getragen, deshalb gibt es keinen kostenlosen Zugang. Wähle deinen Beitrag, " +
            "dann geht es los. Bis dahin kannst du in den Einstellungen deine Daten herunterladen oder dein Konto löschen.",
        style = OasisType.Body,
        color = OasisColors.Ink
    )
    if (overview.canBuy) {
        OasisButton("Mitglied werden", { navigator.open(MembershipRoute.Checkout) }, Modifier.testTag("membership.choose"))
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
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
