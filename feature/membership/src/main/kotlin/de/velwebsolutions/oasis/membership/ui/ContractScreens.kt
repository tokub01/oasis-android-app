package de.velwebsolutions.oasis.membership.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.ErrorStateView
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisCard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.membership.api.CancellationDetails
import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.api.WithdrawalDetails
import java.io.File
import kotlinx.coroutines.launch

/** /app/mitgliedschaft/kuendigen (App\Livewire\Membership\Cancel): § 312k BGB confirmation with "Jetzt kündigen". */
@Composable
fun CancelScreen(viewModel: CancelViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CancelContent(state, viewModel::load, viewModel::confirm, viewModel::prepareExport, viewModel::dismissError)
}

/** The cancel page without its view model, for the app and for tests. */
@Composable
fun CancelContent(
    state: ContractUiState<CancellationDetails>,
    onRetry: suspend () -> Unit,
    onConfirm: suspend () -> Unit,
    onExport: suspend () -> Unit,
    onDismissError: () -> Unit = {}
) {
    val navigator = LocalNavigator.current
    val openWebsite = rememberOpenWebsite(navigator)
    OasisScreen("Mitgliedschaft kündigen", error = state.error, onDismissError = onDismissError) {
        MembershipPage {
            PageHeader("Mitgliedschaft kündigen", eyebrow = "Mitgliedschaft", lead = "Bestätigung deiner Kündigung gemäß § 312k BGB.")
            ContractState(state.details, onRetry) { details ->
                val date = details.effectiveDate?.let(MembershipFormat::day) ?: "Periodenende"
                OasisCard(padding = 20) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        ContractDetails(
                            listOf(
                                "Vertrag:" to "Oasis Mitgliedschaft (Stufe: ${details.tierName})",
                                "Name:" to details.name,
                                "E-Mail:" to details.email,
                                "Art der Kündigung:" to "Ordentlich zum Ende der Abrechnungsperiode",
                                "Wirksam zum:" to date
                            )
                        )
                        Text("Was passiert nach der Kündigung?", style = OasisType.BodyBold, color = OasisColors.Ink)
                        RichBullets(
                            listOf(
                                "Deine Mitgliedschaft bleibt bis zum $date vollständig aktiv.",
                                "Danach wird dein Konto in den **Lesemodus** versetzt. Du kannst weiterhin alle Inhalte lesen.",
                                "Du hast anschließend **30 Tage Zeit**, um deine Daten jederzeit herunterzuladen.",
                                "Wir senden dir unmittelbar eine Eingangsbestätigung in Textform per E-Mail mit Datum und Uhrzeit."
                            )
                        )
                        ContractActions("Jetzt kündigen", "cancel.confirm", onConfirm)
                    }
                }
            }
            SideCard("Deine Daten mitnehmen", tone = PanelTone.Dune) {
                Text("Lade dir jederzeit eine Kopie deiner Daten herunter – vor oder nach der Kündigung.")
                DataExportLink(state.exportFile, onExport)
            }
            SideCard("Auch ohne Anmeldung", tone = PanelTone.Night) {
                Text("Kündigen kannst du ebenso über das öffentliche Formular „Verträge hier kündigen“.")
                InlineLink("Verträge hier kündigen", { openWebsite("/vertraege-kuendigen") }, color = OasisColors.OnNight)
            }
        }
    }
}

/** /app/mitgliedschaft/widerrufen (App\Livewire\Membership\Withdraw): withdrawal within 14 days with "Jetzt widerrufen". */
@Composable
fun WithdrawScreen(viewModel: WithdrawViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WithdrawContent(state, viewModel::load, viewModel::confirm, viewModel::prepareExport, viewModel::dismissError)
}

/** The withdraw page without its view model, for the app and for tests. */
@Composable
fun WithdrawContent(
    state: ContractUiState<WithdrawalDetails>,
    onRetry: suspend () -> Unit,
    onConfirm: suspend () -> Unit,
    onExport: suspend () -> Unit,
    onDismissError: () -> Unit = {}
) {
    val navigator = LocalNavigator.current
    val openWebsite = rememberOpenWebsite(navigator)
    OasisScreen("Mitgliedschaft widerrufen", error = state.error, onDismissError = onDismissError) {
        MembershipPage {
            PageHeader(
                "Mitgliedschaft widerrufen",
                eyebrow = "Mitgliedschaft",
                lead = "Gesetzliches Widerrufsrecht innerhalb von 14 Tagen (§ 355 BGB)."
            )
            ContractState(state.details, onRetry) { details ->
                OasisCard(padding = 20) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        ContractDetails(
                            listOf(
                                "Vertrag:" to "Oasis Mitgliedschaft (Stufe: ${details.tierName})",
                                "Name:" to details.name,
                                "E-Mail:" to details.email,
                                "Rechtsgrundlage:" to "14-tägiges gesetzliches Widerrufsrecht"
                            )
                        )
                        Text("Folgen des Widerrufs:", style = OasisType.BodyBold, color = OasisColors.Ink)
                        RichBullets(
                            listOf(
                                "Deine Mitgliedschaft wird **mit sofortiger Wirkung** beendet.",
                                "Etwaige bereits gezahlte Beträge werden dir unverzüglich über dein ursprüngliches Zahlungsmittel erstattet.",
                                "Dein Konto wechselt in den Lesemodus. Du kannst deine Daten innerhalb von 30 Tagen herunterladen."
                            )
                        )
                        ContractActions("Jetzt widerrufen", "withdraw.confirm", onConfirm)
                    }
                }
            }
            SideCard("Widerrufsbelehrung", tone = PanelTone.Dune) {
                Text("Alle Einzelheiten zu Frist, Folgen und Erstattung stehen in der Widerrufsbelehrung.")
                InlineLink("Widerrufsbelehrung lesen", { openWebsite("/widerrufsbelehrung") }, color = OasisColors.Night)
            }
            SideCard("Deine Daten mitnehmen", tone = PanelTone.Night) {
                Text("Nach dem Widerruf hast du 30 Tage Zeit, deine Daten herunterzuladen. Das geht auch jetzt schon.")
                DataExportLink(state.exportFile, onExport, color = OasisColors.OnNight)
            }
        }
    }
}

/** The page's contract: loading, the confirmation, or – when the page is not reachable – the server's reason. */
@Composable
private fun <D> ContractState(state: Loadable<D>, onRetry: suspend () -> Unit, content: @Composable (D) -> Unit) {
    when (state) {
        is Loadable.Failed -> NotAvailable(state.error, onRetry)
        is Loadable.Loaded -> content(state.value)
        else -> LoadingView()
    }
}

/** The confirming button and the way back; afterwards the overview shows the outcome. */
@Composable
private fun ContractActions(confirmTitle: String, tag: String, onConfirm: suspend () -> Unit) {
    val navigator = LocalNavigator.current
    AsyncOasisButton(
        confirmTitle,
        onClick = {
            onConfirm()
            backToOverview(navigator)
        },
        modifier = Modifier.testTag(tag)
    )
    Box(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).pressable({
            backToOverview(navigator)
        }),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Abbrechen und zurück zur Mitgliedschaft",
            style = OasisType.SmallBold.copy(textDecoration = TextDecoration.Underline),
            color = OasisColors.Muted
        )
    }
}

/** "Daten herunterladen" like the web's direct download: the ZIP is fetched, then handed to the share sheet. */
@Composable
private fun DataExportLink(file: File?, onExport: suspend () -> Unit, color: androidx.compose.ui.graphics.Color = OasisColors.Night) {
    val context = LocalContext.current
    if (file != null) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 44.dp)
                .pressable({ FileSharing.shareFile(context, file, FileSharing.mimeType(file), "ZIP-Datei sichern oder teilen") })
                .testTag("contract.exportShare"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Share, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text("ZIP-Datei sichern oder teilen", style = OasisType.SmallBold, color = color)
        }
    } else {
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        Row(
            Modifier
                .defaultMinSize(minHeight = 44.dp)
                .pressable({
                    if (!running) {
                        running = true
                        scope.launch {
                            try {
                                onExport()
                            } finally {
                                running = false
                            }
                        }
                    }
                })
                .testTag("contract.export"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Daten herunterladen", style = OasisType.SmallBold.copy(textDecoration = TextDecoration.Underline), color = color)
            if (running) CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
        }
    }
}

/**
 * The web redirects to the overview with a flash message when the page is not reachable (no contract, period over);
 * the app shows that message with the way back.
 */
@Composable
private fun NotAvailable(error: ApiError, onRetry: suspend () -> Unit) {
    val navigator = LocalNavigator.current
    if (error is ApiError.Conflict) {
        OasisCard(padding = 20) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Banner(error.userMessage, Modifier.testTag("membership.notAvailable"), kind = BannerKind.Info)
                OasisButton("Zur Mitgliedschaft", { backToOverview(navigator) }, fullWidth = false)
            }
        }
    } else {
        ErrorStateView(error, onRetry)
    }
}
