package de.velwebsolutions.oasis.staff.ui.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisCard
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.Labelled
import de.velwebsolutions.oasis.staff.api.MollieLookup
import de.velwebsolutions.oasis.staff.api.MolliePayment
import de.velwebsolutions.oasis.staff.api.PaymentRecord
import de.velwebsolutions.oasis.staff.ui.components.FactRow
import de.velwebsolutions.oasis.staff.ui.components.OptionPicker
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.components.StaffHeading
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle
import de.velwebsolutions.oasis.staff.ui.components.staffBox
import java.text.NumberFormat
import java.util.Locale

/**
 * Payment search (web: livewire.admin.payments): local records, live Mollie lookups, sync and – super admins only –
 * refunds.
 */
@Composable
fun AdminPaymentsScreen(viewModel: AdminPaymentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OasisScreen(
        "Zahlungen",
        message = state.message,
        error = state.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        StaffLoadable(state.page, viewModel::load) { page ->
            StaffPage(viewModel::load, spacing = 16) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Admin")
                    StaffTitle("Zahlungen")
                    Text(
                        "Für Buchhaltung und Rückfragen zu Zahlungen. Belege bleiben auch nach einer Kontolöschung gesperrt erhalten" +
                            (
                                page.filters.retentionYears?.let {
                                    " und werden nach $it Jahren (ab Ende des Kalenderjahres) automatisch gelöscht"
                                }
                                    ?: ""
                                ) +
                            ". Jede Abfrage bei Mollie wird protokolliert.",
                        style = OasisType.Small,
                        color = OasisColors.Ink2
                    )
                }
                Panel {
                    OasisTextField(
                        "Suche",
                        state.search,
                        viewModel::updateSearch,
                        Modifier.testTag("payments.search"),
                        hint = "Name, E-Mail, Benutzername, Belegnummer (#12) oder Mollie-ID (tr_… / cst_…).",
                        keyboardOptions = OasisKeyboard.NoCapitalization
                    )
                    OptionPicker(
                        "Status",
                        listOf(Labelled("", "Alle")) + page.filters.statuses,
                        state.statusFilter,
                        viewModel::selectStatus
                    )
                    if (page.filters.mollieId) AsyncOasisButton("Direkt bei Mollie suchen", viewModel::searchMollie, fullWidth = false)
                }
                state.mollie?.let { MolliePanel(it, viewModel::closeMollie) }
                StaffHeading("Belege in Oasis (${state.total})")
                if (state.records.isEmpty()) {
                    Text(
                        "Keine Belege gefunden." +
                            if (page.filters.mollieId) " Die ID lässt sich trotzdem direkt bei Mollie suchen." else "",
                        style = OasisType.Body,
                        color = OasisColors.Ink2
                    )
                }
                state.records.forEach { record -> RecordCard(record, state, viewModel) }
                if (state.hasMore) {
                    LaunchedEffect(state.records.size) { viewModel.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = OasisColors.Night)
                    }
                }
            }
        }
    }
}

/** "Live bei Mollie": a payment or a customer with their payments, or the German reason why there is nothing. */
@Composable
private fun MolliePanel(lookup: MollieLookup, onClose: () -> Unit) {
    Column(
        Modifier.staffBox(OasisColors.Night, width = 2, radius = OasisRadius.M).padding(18.dp).testTag("payments.mollie"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StaffHeading("Live bei Mollie", Modifier.weight(1f))
            OasisButton("Schließen", onClose, kind = OasisButtonKind.Secondary, fullWidth = false)
        }
        val data = lookup.mollie
        val payment = data?.payment
        val customer = data?.customer
        when {
            lookup.error != null -> Text(lookup.error, style = OasisType.BodyBold, color = OasisColors.SunText)

            data?.kind == "payment" && payment != null -> {
                PaymentFacts(payment)
                DashboardLink(payment.dashboard)
            }

            customer != null -> {
                FactRow("Kunde", customer.id)
                FactRow("Name", customer.name ?: "–")
                FactRow("E-Mail", customer.email ?: "–")
                FactRow("Angelegt", MollieFormat.date(customer.created))
                DashboardLink(customer.dashboard)
                val payments = data.payments.orEmpty()
                Text(
                    "Zahlungen dieser Kundin / dieses Kunden bei Mollie (${payments.size})",
                    style = OasisType.BodyBold,
                    color = OasisColors.Ink
                )
                if (payments.isEmpty()) Text("Keine Zahlungen bei Mollie.", style = OasisType.Small, color = OasisColors.Ink)
                payments.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${item.id} · ${MollieFormat.amount(item.amount)}", style = OasisType.SmallBold, color = OasisColors.Ink)
                        Text(
                            "${MollieFormat.status(item.status)} · ${MollieFormat.method(item.method) ?: "Zahlungsmittel offen"} · " +
                                MollieFormat.date(item.created) + if (item.subscription != null) " · Abo" else "",
                            style = OasisType.Caption,
                            color = OasisColors.Ink2
                        )
                    }
                    HorizontalDivider(color = OasisColors.PaperLine)
                }
            }
        }
    }
}

@Composable
private fun PaymentFacts(payment: MolliePayment) {
    val refunded = payment.refunded?.toDoubleOrNull() ?: 0.0
    FactRow("Zahlung", payment.id)
    FactRow("Status", MollieFormat.status(payment.status))
    FactRow(
        "Betrag",
        MollieFormat.amount(payment.amount) + if (refunded > 0) " · erstattet ${MollieFormat.amount(payment.refunded)}" else ""
    )
    FactRow("Zahlungsmittel", MollieFormat.method(payment.method) ?: "–")
    FactRow("Beschreibung", payment.description ?: "–")
    FactRow("Erstellt", MollieFormat.date(payment.created))
    FactRow("Bezahlt", MollieFormat.date(payment.paid))
    FactRow("Kunde", payment.customer ?: "–")
    FactRow("Abo", payment.subscription ?: "–")
}

@Composable
private fun DashboardLink(link: String?) {
    val navigator = LocalNavigator.current
    if (!link.isNullOrEmpty()) OasisButton("Im Mollie-Dashboard öffnen", { navigator.open(WebRoute(link)) }, fullWidth = false)
}

/** German display of Mollie's raw values, like the web's helpers. */
object MollieFormat {
    private val statuses = mapOf(
        "paid" to "Bezahlt",
        "open" to "Offen",
        "pending" to "In Bearbeitung",
        "failed" to "Fehlgeschlagen",
        "canceled" to "Abgebrochen",
        "expired" to "Abgelaufen",
        "erstattet" to "Erstattet"
    )
    private val methods = mapOf(
        "creditcard" to "Kreditkarte",
        "paypal" to "PayPal",
        "directdebit" to "SEPA-Lastschrift",
        "banktransfer" to "Überweisung",
        "applepay" to "Apple Pay",
        "googlepay" to "Google Pay",
        "ideal" to "iDEAL",
        "bancontact" to "Bancontact",
        "klarna" to "Klarna"
    )

    fun status(value: String): String = statuses[value] ?: value

    fun method(value: String?): String? = value?.let { methods[it] ?: it }

    /** "5.00" → "5,00 €". */
    fun amount(value: String?): String {
        val number = value?.toDoubleOrNull() ?: return "–"
        val format = NumberFormat.getNumberInstance(Locale.GERMANY).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return format.format(number) + " €"
    }

    fun date(value: String?): String = value?.let(OasisJson::parseInstant)?.let(StaffDate::medium) ?: "–"
}

/** One local payment record with its actions and the refund form. */
@Composable
private fun RecordCard(record: PaymentRecord, state: AdminPaymentsUiState, viewModel: AdminPaymentsViewModel) {
    OasisCard(Modifier.testTag("payment.${record.id}"), padding = 16) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "#${record.id} · ${record.kind} · ${record.amount}",
                    style = OasisType.BodyBold,
                    color = OasisColors.Night,
                    modifier = Modifier.weight(1f)
                )
                Pill(record.statusLabel + if (record.refunded) " · erstattet" else "", tone = PillTone.Sand)
            }
            Text(
                buildAnnotatedString {
                    append("${record.customerName ?: "–"} · ${record.customerEmail ?: "–"}")
                    if (record.accountDeleted) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = OasisColors.SunText)) {
                            append(" · Konto gelöscht – Beleg gesperrt")
                        }
                    }
                },
                style = OasisType.Small,
                color = OasisColors.Ink2
            )
            Text(
                "${record.tier} · ${record.rhythm} · angelegt ${StaffDate.medium(record.createdAt)}" +
                    (record.paidAt?.let { " · bezahlt ${StaffDate.medium(it)}" } ?: "") +
                    " · ${record.molliePaymentId ?: "keine Mollie-Zahlung"}",
                style = OasisType.Caption,
                color = OasisColors.Muted
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (record.canSync) AsyncOasisButton("Jetzt mit Mollie abgleichen", { viewModel.sync(record) }, fullWidth = false)
                if (record.molliePaymentId != null) {
                    AsyncOasisButton("Zahlung bei Mollie prüfen", {
                        viewModel.checkPayment(record)
                    }, kind = OasisButtonKind.Secondary, fullWidth = false)
                }
                if (record.mollieCustomerId != null) {
                    AsyncOasisButton(
                        "Alle Zahlungen der Person bei Mollie",
                        { viewModel.checkCustomer(record) },
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false
                    )
                }
                if (record.canRefund && state.refundFor != record.id) {
                    OasisButton("Erstatten", { viewModel.startRefund(record) }, kind = OasisButtonKind.Secondary, fullWidth = false)
                }
            }
            record.refunds.forEach { refund ->
                Text(
                    "Erstattet ${refund.amount} am ${StaffDate.medium(refund.createdAt)} von ${refund.adminName ?: "gelöschtem Konto"}: " +
                        "„${refund.reason}“ (${refund.mollieRefundId.orEmpty()})",
                    style = OasisType.Caption,
                    color = OasisColors.Ink2
                )
            }
            if (state.refundFor == record.id) RefundForm(record, state, viewModel)
        }
    }
}

@Composable
private fun RefundForm(record: PaymentRecord, state: AdminPaymentsUiState, viewModel: AdminPaymentsViewModel) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, OasisColors.DuneLine, RoundedCornerShape(OasisRadius.XS.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Das Geld geht über Mollie auf das ursprüngliche Zahlungsmittel zurück. Die Mitgliedschaft läuft weiter; beenden kann sie " +
                "nur die Kündigung oder der Widerruf.",
            style = OasisType.Caption,
            color = OasisColors.Ink2
        )
        OasisTextField("Betrag in Euro (höchstens ${record.refundable})", state.refundAmount, {
            viewModel.editRefund(amount = it)
        }, error = state.refundErrors["amount"])
        OasisTextField(
            "Begründung (für die Buchhaltung)",
            state.refundReason,
            { viewModel.editRefund(reason = it) },
            error = state.refundErrors["reason"],
            lines = 3..10
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncOasisButton("Jetzt erstatten", { viewModel.refund(record) }, fullWidth = false)
            OasisButton("Abbrechen", viewModel::cancelRefund, kind = OasisButtonKind.Secondary, fullWidth = false)
        }
    }
}
