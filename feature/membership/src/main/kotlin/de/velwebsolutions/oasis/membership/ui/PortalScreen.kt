package de.velwebsolutions.oasis.membership.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.api.MembershipPayment

/**
 * /app/mitgliedschaft/portal (App\Livewire\Membership\Portal) in the app: the paid contributions of a web membership
 * with their invoices (view, print and "Teilen"). The payment method is changed on the web, not in the app.
 */
@Composable
fun PortalScreen(viewModel: PortalViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PortalContent(state, viewModel::load, viewModel::openInvoice, viewModel::dismissMessage, viewModel::dismissError)
    state.invoiceFile?.let { InvoiceSheet(it, viewModel::closeInvoice) }
}

@Composable
fun PortalContent(
    state: PortalUiState,
    onRefresh: suspend () -> Unit,
    onOpenInvoice: (MembershipPayment) -> Unit,
    onDismissMessage: () -> Unit = {},
    onDismissError: () -> Unit = {}
) {
    val navigator = LocalNavigator.current
    val openWebsite = rememberOpenWebsite(navigator)
    OasisScreen(
        "Rechnungen",
        message = state.message,
        error = state.error,
        onDismissMessage = onDismissMessage,
        onDismissError = onDismissError
    ) {
        MembershipPage(onRefresh) {
            PageHeader("Rechnungen", eyebrow = "Mitgliedschaft", lead = "Lade die Rechnungen zu deinen Beiträgen herunter.")
            SectionCard("Bezahlte Beiträge") {
                LoadableView(state.payments, onRetry = onRefresh) { payments ->
                    if (payments.isEmpty()) {
                        Text(
                            "Noch keine bezahlten Beiträge vorhanden.",
                            style = OasisType.Small,
                            color = OasisColors.Ink2,
                            modifier = Modifier.testTag("portal.empty")
                        )
                    } else {
                        Column {
                            payments.forEachIndexed { index, payment ->
                                PaymentRow(payment, loading = state.loadingInvoice == payment.id) { onOpenInvoice(payment) }
                                if (index != payments.lastIndex) HorizontalDivider(color = OasisColors.PaperLine)
                            }
                        }
                    }
                }
            }
            InlineLink("Zur Mitgliedschaft", { backToOverview(navigator) }, color = OasisColors.Night)
            SideCard("Fragen zur Rechnung?", tone = PanelTone.Dune) {
                Text("Jede Rechnung kannst du einzeln herunterladen. Bei Fragen hilft dir unsere Hilfe-Seite weiter.")
                InlineLink("Hilfe & Kontakt", { openWebsite("/hilfe") }, color = OasisColors.Night)
            }
        }
    }
}

@Composable
private fun PaymentRow(payment: MembershipPayment, loading: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(MembershipFormat.day(payment.paidAt), style = OasisType.BodyBold, color = OasisColors.Night)
            Text(payment.amountLabel + if (payment.refunded) " · erstattet" else "", style = OasisType.Small, color = OasisColors.Ink2)
        }
        Row(
            Modifier.defaultMinSize(minHeight = 44.dp).pressable(onOpen, enabled = !loading).testTag("portal.invoice.${payment.id}"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = OasisColors.Night, strokeWidth = 2.dp)
            } else {
                Text(
                    "Rechnung ${payment.invoiceNumber}",
                    style = OasisType.SmallBold.copy(textDecoration = TextDecoration.Underline),
                    color = OasisColors.Night
                )
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    tint = OasisColors.Night,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** /app/mitgliedschaft/rechnungen/{id} opened from a link: downloads the invoice and shows it with "Teilen". */
@Composable
fun InvoiceScreen(
    paymentId: Int,
    viewModel: InvoiceViewModel = hiltViewModel<InvoiceViewModel, InvoiceViewModel.Factory>(
        key = "invoice-$paymentId",
        creationCallback = { it.create(paymentId) }
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InvoiceContent(paymentId, state, viewModel::load, viewModel::showPreview, viewModel::closePreview)
}

/** The invoice page: loading, the server's error, or the invoice with "Rechnung ansehen" and "Teilen". */
@Composable
fun InvoiceContent(paymentId: Int, state: InvoiceUiState, onRetry: suspend () -> Unit, onShow: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    OasisScreen("Rechnung") {
        LoadableView(state.file, onRetry = onRetry) { file ->
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = OasisColors.Spring, modifier = Modifier.size(40.dp))
                Text("Rechnung OAS-$paymentId", style = OasisType.heading(), color = OasisColors.Night)
                OasisButton("Rechnung ansehen", onShow, Modifier.testTag("invoice.view"), fullWidth = false)
                OasisButton(
                    "Teilen",
                    { FileSharing.shareFile(context, file, FileSharing.mimeType(file), "Teilen") },
                    Modifier.testTag("invoice.pageShare"),
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false
                )
            }
        }
    }
    state.preview?.let { InvoiceSheet(it, onClose) }
}
