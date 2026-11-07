package de.velwebsolutions.oasis.membership.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.MembershipTier
import kotlinx.coroutines.launch

/**
 * /app/mitgliedschaft (App\Livewire\Membership\Overview) in the app: status, vouchers bought on the web and the aside
 * cards. Buying happens through Google Play (PlayCheckoutScreen); redeeming and buying vouchers stays on the web.
 */
@Composable
fun OverviewScreen(viewModel: OverviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OverviewContent(
        state,
        onRefresh = viewModel.retry(viewModel::load),
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError,
        onSubscriptionManaged = viewModel::subscriptionManaged,
        onObjectToTerms = viewModel::objectToTerms,
        onWithdrawTermsObjection = viewModel::withdrawTermsObjection,
        onSetTier = viewModel::setTier,
        onSetAmount = viewModel::setAmount,
        onChangeTier = viewModel::changeTier
    )
}

/** The overview without its view model, for the app and for tests. */
@Composable
fun OverviewContent(
    state: OverviewUiState,
    onRefresh: suspend () -> Unit,
    onDismissMessage: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onSubscriptionManaged: () -> Unit = {},
    onObjectToTerms: suspend () -> Unit = {},
    onWithdrawTermsObjection: suspend () -> Unit = {},
    onSetTier: (String) -> Unit = {},
    onSetAmount: (String) -> Unit = {},
    onChangeTier: suspend () -> Unit = {}
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
            LoadableView(state.overview, onRetry = onRefresh) { overview ->
                OverviewSections(
                    overview,
                    onSubscriptionManaged,
                    termsChange = { TermsChangeCard(it, state.termsBusy, onObjectToTerms, onWithdrawTermsObjection) },
                    tierChange = { TierChangeCard(state, it, onSetTier, onSetAmount, onChangeTier) }
                )
            }
        }
    }
}

@Composable
private fun OverviewSections(
    overview: MembershipOverview,
    onSubscriptionManaged: () -> Unit,
    termsChange: @Composable (MembershipOverview.TermsChange) -> Unit,
    tierChange: @Composable (MembershipOverview) -> Unit
) {
    val navigator = LocalNavigator.current
    val openWebsite = rememberOpenWebsite(navigator)
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // An announced change of the AGB comes first, like the withdrawal notice of the web: it has a deadline.
        overview.termsChange?.let { termsChange(it) }
        StatusCard(overview, onSubscriptionManaged)
        if (overview.canChangeTier) tierChange(overview)
        PerksCard(overview, navigator)
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
        overview.termsAcceptedMoment?.let { accepted -> ContractTextCard(accepted, openWebsite) }
        SideCard("Fair finanziert", tone = PanelTone.Night) {
            Text(
                "Oasis ist werbefrei und lebt von den Beiträgen seiner Mitglieder. Deine Wünsche und Vorschläge sammeln wir in „Ideen für Oasis“."
            )
            InlineLink("Zu den Ideen", { navigator.open(NetworkRoute.Ideas()) }, color = OasisColors.OnNight)
        }
        SideCard("Gut zu wissen", tone = PanelTone.Dune) {
            InlineLink("Stufen & Preise", { openWebsite("/preise") }, color = OasisColors.Night)
            InlineLink("AGB", { openWebsite("/agb") }, color = OasisColors.Night)
            InlineLink("Hilfe & Kontakt", { openWebsite("/hilfe") }, color = OasisColors.Night)
        }
    }
}

/**
 * The contract text the member got with the contract confirmation (overview.blade.php „Dein Vertragstext“, Art. 246c
 * Nr. 2 EGBGB): when they agreed, and the website where the files are downloaded.
 */
@Composable
private fun ContractTextCard(accepted: String, openWebsite: (String) -> Unit) {
    SectionCard("Dein Vertragstext", Modifier.testTag("membership.contractText")) {
        Text(
            "Die AGB in der Fassung, der du zugestimmt hast, und die Widerrufsbelehrung haben wir dir mit der Vertragsbestätigung per " +
                "E-Mail geschickt. Sie bleiben für dich gültig, auch wenn wir die veröffentlichten AGB später ändern; auf der Website " +
                "unter „Mitgliedschaft“ kannst du sie herunterladen.",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
        Text(
            "Zugestimmt am $accepted",
            style = OasisType.BodyBold,
            color = OasisColors.Night,
            modifier = Modifier.testTag("membership.termsAccepted")
        )
        InlineLink(
            "Vertragstext auf der Website",
            { openWebsite("/app/mitgliedschaft") },
            Modifier.testTag("membership.contractText.website"),
            OasisColors.Night
        )
    }
}

/** "Deine Vorteile": the way to what the tier includes (and to the companion places and the supporter mark of a Mäzen). */
@Composable
private fun PerksCard(overview: MembershipOverview, navigator: Navigator) {
    Panel(Modifier.pressable({ navigator.open(MembershipRoute.Perks) }).testTag("membership.perks"), spacing = 6) {
        Text("Deine Vorteile", style = OasisType.heading(), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
        Text(
            "Was deine Stufe enthält" +
                (if (overview.features.companionSeats > 0) ", deine Plätze für andere und dein Unterstützer-Zeichen" else "") +
                ".",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
    }
}

/** The status card (livewire/membership/partials/status.blade.php): subscription, gift, or no membership yet. */
@Composable
fun StatusCard(overview: MembershipOverview, onSubscriptionManaged: () -> Unit = {}) {
    SectionCard("Status", Modifier.testTag("membership.statusCard")) {
        when (overview.state) {
            MembershipOverview.State.Subscription -> SubscriptionStatus(overview, onSubscriptionManaged)

            MembershipOverview.State.Gift -> GiftStatus(overview)

            // State.Expired, the last state: the place of a Mäzen gives access then (status.blade.php).
            else -> overview.companionSeat?.let { CompanionStatus(it) } ?: ExpiredStatus(overview)
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
                    "Einschränkungen weiternutzen. Danach endet dein Zugang, und du hast 30 Tage, um deine Daten herunterzuladen."
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
private fun CompanionStatus(seat: MembershipOverview.CompanionSeat) {
    val navigator = LocalNavigator.current
    StatusPill("Dein Platz bei ${seat.sponsor}", Icons.Outlined.Group, Modifier.testTag("membership.companion"))
    Text(
        boldMarkdown(
            "${seat.sponsor} nimmt dich als Mäzen mit. Du hast Oasis mit allem aus der Stufe **${seat.tierName}** und zahlst nichts, " +
                "solange ${seat.sponsor} Mäzen bleibt."
        ),
        style = OasisType.Body,
        color = OasisColors.Ink
    )
    OasisTextButton("Zu deinem Platz", { navigator.open(MembershipRoute.Perks) }, Modifier.testTag("membership.companion.perks"))
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

/**
 * „Unsere AGB ändern sich am …“ (overview.blade.php, AGB § 19): what changes and why, the way to the new version and
 * the button to object – or, once objected, the standing objection with the way to take it back. Both ask first, like
 * the web's `wire:confirm`; [busy] holds the button while the server answers. After the day („Unsere AGB haben sich am
 * … geändert“) the objection stands without a way back, and once the operator terminated the contract for it, the text
 * names its end.
 */
@Composable
fun TermsChangeCard(change: MembershipOverview.TermsChange, busy: Boolean, onObject: suspend () -> Unit, onWithdraw: suspend () -> Unit) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val day = change.effectiveDay
    var asking by remember { mutableStateOf(false) }
    SectionCard(change.title, Modifier.testTag("membership.termsChange"), tone = PanelTone.Warning) {
        Text("${change.summary} Grund: ${change.reason}", style = OasisType.Body, color = OasisColors.Night)
        val standing = change.standingText
        when {
            standing != null -> {
                Text(
                    standing,
                    style = OasisType.BodyBold,
                    color = OasisColors.Night,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.CHIP.dp))
                        .padding(14.dp)
                        .testTag("membership.termsObjected")
                )
                if (change.canWithdrawObjection) {
                    OasisButton(
                        "Widerspruch zurücknehmen",
                        { asking = true },
                        Modifier.testTag("membership.withdrawTermsObjection"),
                        kind = OasisButtonKind.Secondary,
                        enabled = !busy
                    )
                }
            }

            // Only an objector still gets the change after its day; without an objection on record the server sent it
            // anyway, so say what applies instead of offering an objection whose period is over.
            change.inEffect -> Text(
                "Die neue Fassung gilt seit dem $day für deinen Vertrag.",
                style = OasisType.Body,
                color = OasisColors.Night,
                modifier = Modifier.testTag("membership.termsInEffect")
            )

            else -> {
                Text(
                    "Wenn du bis zum $day nicht widersprichst, gilt die neue Fassung auch für deinen Vertrag. Widersprichst du, gilt für " +
                        "dich die bisherige Fassung weiter; wir können den Vertrag dann mit einer Frist von einem Monat zum Ende des " +
                        "bezahlten Zeitraums kündigen (AGB § 7).",
                    style = OasisType.Body,
                    color = OasisColors.Night
                )
                OasisButton("Ich widerspreche dieser Änderung", {
                    asking = true
                }, Modifier.testTag("membership.objectToTerms"), enabled = !busy)
            }
        }
        change.url?.let { url ->
            InlineLink("Neue Fassung lesen", { navigator.open(WebRoute(url)) }, Modifier.testTag("membership.termsUrl"), OasisColors.Night)
        }
    }
    if (asking && change.objected) {
        Question(
            "Widerspruch zurücknehmen?",
            "Zurücknehmen",
            { scope.launch { onWithdraw() } },
            "Die neue Fassung gilt dann ab dem $day auch für deinen Vertrag.",
            "membership.termsConfirm"
        ) { asking = false }
    } else if (asking) {
        Question(
            "Der Änderung zum $day widersprechen?",
            "Widersprechen",
            { scope.launch { onObject() } },
            "Für dich gilt dann die bisherige Fassung weiter; wir können den Vertrag deshalb zum Ende des bezahlten Zeitraums " +
                "kündigen (AGB § 7).",
            "membership.termsConfirm"
        ) { asking = false }
    }
}

/**
 * „Beitragsstufe ändern“ of a running Mollie membership (portal.blade.php, MembershipTierChanges, AGB § 5): the current
 * tier and when the new contribution applies, the tier choice with the web's labels, the Mäzen amount, the server's
 * field errors, and „Stufe ändern“ behind the web's confirmation. Store memberships change their tier in the store
 * (the status card says so) and never get this card.
 */
@Composable
fun TierChangeCard(
    state: OverviewUiState,
    overview: MembershipOverview,
    onSetTier: (String) -> Unit,
    onSetAmount: (String) -> Unit,
    onChangeTier: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    SectionCard("Beitragsstufe ändern", Modifier.testTag("membership.tierChange")) {
        Text(tierChangeLead(overview), style = OasisType.Small, color = OasisColors.Ink2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Neue Stufe", style = OasisType.SmallBold, color = OasisColors.Ink)
            Column(Modifier.testTag("membership.tier.picker")) {
                MembershipTier.all.forEach { tier ->
                    val chosen = tier.key == state.tier
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = chosen, role = Role.RadioButton) { onSetTier(tier.key) }
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("membership.tier.${tier.key}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = chosen,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = OasisColors.Night, unselectedColor = OasisColors.FieldLine)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(tier.label, style = OasisType.Body, color = OasisColors.Ink)
                    }
                }
            }
            state.tierErrors["tier"]?.let { FieldError(it) }
        }
        if (state.asksForAmount) {
            OasisTextField(
                "Dein Monatsbeitrag in Euro (ab ${MembershipTier.minimumMaezen})",
                state.amount,
                onSetAmount,
                modifier = Modifier.testTag("membership.tier.amount"),
                prompt = "${MembershipTier.minimumMaezen}",
                error = state.tierErrors["amount"],
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        OasisButton(
            "Stufe ändern",
            { asking = true },
            Modifier.testTag("membership.tier.change"),
            enabled =
                !state.tierBusy && state.tier.isNotEmpty()
        )
    }
    if (asking) {
        Question(
            "Stufe jetzt ändern?",
            "Stufe ändern",
            { scope.launch { onChangeTier() } },
            "Die neue Stufe gilt ab sofort, der neue Beitrag ab deiner nächsten Abbuchung. Die Bestätigung mit dem Vertragstext bekommst du per E-Mail.",
            "membership.tier.confirm"
        ) { asking = false }
    }
}

/**
 * „Derzeit: Stufe „Oase“. Die neue Stufe gilt ab sofort, der neue Beitrag ab deiner nächsten Abbuchung am … .
 * Wechselst du in eine kleinere Stufe, …“ (portal.blade.php; the contribution itself is on the invoices).
 */
fun tierChangeLead(overview: MembershipOverview): String {
    val current = overview.subscription?.let { "Derzeit: Stufe „${it.tierName}“. " }.orEmpty()
    val next = overview.subscription?.paidUntil?.let { " am ${MembershipFormat.day(it)}" }.orEmpty()
    return current + "Die neue Stufe gilt ab sofort, der neue Beitrag ab deiner nächsten Abbuchung$next. " +
        "Wechselst du in eine kleinere Stufe, bleibt Bestehendes erhalten; Neues richtet sich nach der neuen Stufe (AGB § 5)."
}
