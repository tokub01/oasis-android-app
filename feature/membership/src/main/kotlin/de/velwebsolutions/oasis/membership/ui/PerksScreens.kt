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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisLogo
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.SettingToggle
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.api.MembershipPerks
import de.velwebsolutions.oasis.membership.api.SupporterInput
import kotlinx.coroutines.launch

/** The tier a companion place gives (TierFeatures::COMPANION_TIER), where the server does not name it. */
private const val COMPANION_TIER = "Oase"

/** What the perks page can ask its view model for. */
data class PerksCallbacks(
    val onRefresh: suspend () -> Unit,
    val onCreateSeat: suspend () -> Unit,
    val onReleaseSeat: suspend (Int) -> Unit,
    val onGiveBack: suspend () -> Unit,
    val onSupporter: suspend (SupporterInput) -> Unit,
    val onDismissMessage: () -> Unit,
    val onDismissError: () -> Unit
)

/** /app/mitgliedschaft/vorteile (App\Livewire\Membership\Perks) in the app. */
@Composable
fun PerksScreen(viewModel: PerksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PerksContent(
        state,
        PerksCallbacks(
            onRefresh = viewModel::load,
            onCreateSeat = viewModel::createSeat,
            onReleaseSeat = viewModel::releaseSeat,
            onGiveBack = viewModel::giveBack,
            onSupporter = viewModel::updateSupporter,
            onDismissMessage = viewModel::dismissMessage,
            onDismissError = viewModel::dismissError
        )
    )
}

/** The perks page without its view model, for the app and for tests. */
@Composable
fun PerksContent(state: PerksUiState, callbacks: PerksCallbacks) {
    OasisScreen(
        title = "Deine Vorteile",
        message = state.message,
        error = state.error,
        onDismissMessage = callbacks.onDismissMessage,
        onDismissError = callbacks.onDismissError
    ) {
        MembershipPage(callbacks.onRefresh) {
            val tierName = state.perks.valueOrNull?.tierName.orEmpty()
            PageHeader(
                "Deine Vorteile",
                eyebrow = if (tierName.isEmpty()) "Mitgliedschaft" else "Mitgliedschaft $tierName",
                lead = "Was deine Mitgliedschaft enthält. Das ganze Netzwerk gehört allen; die höheren Stufen geben dir mehr Raum zum " +
                    "Gestalten, Erinnerungen und Plätze für deine Leute."
            )
            LoadableView(state.perks, onRetry = callbacks.onRefresh) { perks ->
                PerksSections(
                    perks,
                    callbacks,
                    heldSeat = perks.heldSeat?.takeIf { !state.paying },
                    state.companionTierName ?: COMPANION_TIER
                )
            }
        }
    }
}

@Composable
private fun PerksSections(perks: MembershipPerks, callbacks: PerksCallbacks, heldSeat: MembershipPerks.HeldSeat?, companionTier: String) {
    val navigator = LocalNavigator.current
    val openWebsite = rememberOpenWebsite(navigator)
    val scope = rememberCoroutineScope()
    var givingBack by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // A held place shows only while the member does not pay (Perks.php `isPaying`).
        heldSeat?.let { held ->
            SectionCard("Dein Platz bei ${held.sponsor}", Modifier.testTag("perks.heldSeat")) {
                Text(
                    if (held.active) {
                        "${held.sponsor} nimmt dich als Mäzen mit. Du hast Oasis mit allem aus der Stufe $companionTier, solange " +
                            "${held.sponsor} Mäzen bleibt."
                    } else {
                        "${held.sponsor} ist gerade kein Mäzen mehr, deshalb ruht dein Platz. Mit einer eigenen Mitgliedschaft geht es sofort weiter."
                    },
                    style = OasisType.Body,
                    color = OasisColors.Ink2
                )
                OasisButton("Platz zurückgeben", { givingBack = true }, kind = OasisButtonKind.Secondary, fullWidth = false)
            }
        }
        SectionCard("In ${perks.tierName} enthalten") {
            perks.highlights.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = OasisColors.SpringText, modifier = Modifier.size(20.dp))
                    Text(line, style = OasisType.Body, color = OasisColors.Ink)
                }
            }
            if (perks.tier != "maezen") InlineLink("Stufen vergleichen", { openWebsite("/preise") }, color = OasisColors.Night)
        }
        if (perks.features.companionSeats > 0) CompanionSeats(perks, callbacks)
        if (perks.features.supporterBadge) {
            SectionCard(
                "Sichtbar unterstützen, wenn du magst",
                detail = "Beides ist aus, bis du es einschaltest, und lässt sich jederzeit zurücknehmen. Deine Stufe sieht sonst niemand."
            ) {
                SettingToggle(
                    "Unterstützer-Zeichen am Profil",
                    perks.supporterBadge,
                    { on -> scope.launch { callbacks.onSupporter(SupporterInput(supporterBadge = on)) } },
                    detail = "Ein kleines Zeichen neben deinem Namen: trägt Oasis mit."
                )
                SettingToggle(
                    "Auf der Dankesliste stehen",
                    perks.thankYouList,
                    { on -> scope.launch { callbacks.onSupporter(SupporterInput(thankYouList = on)) } },
                    detail = "Dein Name auf der öffentlichen Dankesliste."
                )
            }
        }
    }
    if (givingBack) {
        Question("Den Platz wirklich zurückgeben?", "Platz zurückgeben", { scope.launch { callbacks.onGiveBack() } }) { givingBack = false }
    }
}

/** "Menschen mitnehmen": the given places (taken or open with their link) and a new link while places are free. */
@Composable
private fun CompanionSeats(perks: MembershipPerks, callbacks: PerksCallbacks) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var releasing by remember { mutableStateOf<MembershipPerks.Seat?>(null) }
    SectionCard(
        "Menschen mitnehmen",
        Modifier.testTag("perks.seats"),
        detail = "Als Mäzen vergibst du ${perks.features.companionSeats} volle Plätze. Wer deinen Link annimmt, hat Oasis mit allem aus " +
            "$COMPANION_TIER, solange du Mäzen bist, und zahlt nichts."
    ) {
        perks.seats.forEach { seat ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(OasisColors.Sand, RoundedCornerShape(OasisRadius.S.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .testTag("perks.seat.${seat.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val member = seat.member
                val url = seat.url
                if (member != null) {
                    val since = seat.acceptedAt?.let { ", seit ${MembershipFormat.day(it)}" }.orEmpty()
                    Text("${member.displayName} ist dabei$since", style = OasisType.BodyBold, color = OasisColors.Night)
                } else if (url != null) {
                    Text("Offener Platz – Link zum Weitergeben:", style = OasisType.SmallBold, color = OasisColors.Ink)
                    SelectionContainer {
                        Text(url, style = OasisType.Digits.copy(fontSize = OasisType.Caption.fontSize), color = OasisColors.Ink)
                    }
                    Row(
                        Modifier.pressable({ FileSharing.shareText(context, url, "Link teilen") }).padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(16.dp))
                        Text("Link teilen", style = OasisType.SmallBold, color = OasisColors.Night)
                    }
                }
                OasisButton("Platz freigeben", { releasing = seat }, kind = OasisButtonKind.Secondary, fullWidth = false)
            }
        }
        if (perks.remainingSeats > 0) {
            AsyncOasisButton(
                "Link für einen Platz erstellen (${perks.remainingSeats} frei)",
                callbacks.onCreateSeat,
                fullWidth = false,
                modifier = Modifier.testTag("perks.createSeat")
            )
        }
    }
    releasing?.let { seat ->
        val name = seat.member?.displayName
        Question(
            "Den Platz freigeben?",
            "Platz freigeben",
            { scope.launch { callbacks.onReleaseSeat(seat.id) } },
            if (name != null) "$name verliert dann den Zugang, außer mit eigener Mitgliedschaft." else "Der Link gilt dann nicht mehr."
        ) { releasing = null }
    }
}

/** A question before a step that ends something (the web's `wire:confirm`). */
@Composable
private fun Question(title: String, confirmLabel: String, onConfirm: () -> Unit, message: String? = null, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OasisColors.Paper,
        title = { Text(title, style = OasisType.heading(), color = OasisColors.Night) },
        text = message?.let { { Text(it, style = OasisType.Body, color = OasisColors.Ink2) } },
        confirmButton = {
            OasisTextButton(confirmLabel, {
                onDismiss()
                onConfirm()
            }, Modifier.testTag("perks.confirm"), color = OasisColors.SunText)
        },
        dismissButton = { OasisTextButton("Abbrechen", onDismiss) }
    )
}

/** /app/mitgliedschaft/mitkommen/{token} (App\Livewire\Membership\CompanionLanding) in the app. */
@Composable
fun CompanionLinkScreen(
    token: String,
    viewModel: CompanionLinkViewModel =
        hiltViewModel<CompanionLinkViewModel, CompanionLinkViewModel.Factory>(creationCallback = { it.create(token) })
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    CompanionLinkContent(
        state,
        onRetry = viewModel::load,
        onAccept = { viewModel.accept { navigator.handle(NetworkRoute.Source) } },
        onDismissError = viewModel::dismissError
    )
}

/** The companion link page without its view model, for the app and for tests. */
@Composable
fun CompanionLinkContent(
    state: CompanionLinkUiState,
    onRetry: suspend () -> Unit,
    onAccept: suspend () -> Unit,
    onDismissError: () -> Unit = {}
) {
    val navigator = LocalNavigator.current
    OasisScreen("Mitkommen", error = state.error, onDismissError = onDismissError) {
        MembershipPage {
            LoadableView(state.sponsor, onRetry = onRetry) { sponsor ->
                Panel(tone = PanelTone.Night, spacing = 16) {
                    OasisLogo(wordmark = false)
                    if (sponsor != null) {
                        NightTitle("$sponsor nimmt dich mit nach Oasis")
                        Text(
                            "$sponsor trägt Oasis als Mäzen und schenkt dir einen vollen Platz: alles aus $COMPANION_TIER, " +
                                "ohne dass du etwas zahlst, solange $sponsor Mäzen bleibt. Ihr folgt euch nicht automatisch, " +
                                "und niemand sonst erfährt, woher dein Platz kommt.",
                            style = OasisType.Lead,
                            color = OasisColors.OnNight2
                        )
                        AsyncOasisButton(
                            "Platz annehmen",
                            onAccept,
                            kind = OasisButtonKind.Accent,
                            modifier = Modifier.testTag("companion.accept")
                        )
                    } else {
                        NightTitle("Dieser Link gilt nicht mehr")
                        Text(
                            "Der Platz ist schon vergeben oder wurde freigegeben. Frag die Person, die dir den Link geschickt hat, nach einem neuen.",
                            style = OasisType.Lead,
                            color = OasisColors.OnNight2
                        )
                        OasisButton("Eigene Mitgliedschaft ansehen", {
                            navigator.open(MembershipRoute.Overview)
                        }, kind = OasisButtonKind.Accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun NightTitle(text: String) {
    Text(text, style = OasisType.display(30), color = OasisColors.OnNight, modifier = Modifier.semantics { heading() })
}
