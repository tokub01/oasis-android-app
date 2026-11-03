package de.velwebsolutions.oasis.membership.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.membership.api.FinanceReport
import de.velwebsolutions.oasis.membership.api.Transparency
import de.velwebsolutions.oasis.membership.data.MembershipRepository
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** „Wohin dein Beitrag geht“: the page, the report shown in full (the latest or an earlier one) and a failure. */
data class TransparencyUiState(
    val page: Loadable<Transparency> = Loadable.Idle,
    val shown: FinanceReport? = null,
    val error: String? = null
)

/** „Wohin dein Beitrag geht“ (/transparenz, docs/oasis-admin.md): the figures and the reports; an earlier one replaces the shown. */
@HiltViewModel
class TransparencyViewModel @Inject constructor(private val repository: MembershipRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(TransparencyUiState())
    val state: StateFlow<TransparencyUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** Loads the page again; a failed first load shows the error page, a failed refresh a snackbar. */
    suspend fun load() {
        mutableState.update { it.copy(page = it.page.refreshing()) }
        val result = attempt { repository.transparency() }
        result.onSuccess { page -> mutableState.update { it.copy(page = Loadable.Loaded(page), shown = page.report) } }
        result.onFailure { error ->
            mutableState.update {
                it.copy(page = it.page.failing(error), error = it.page.valueOrNull?.let { (error as ApiError).userMessage })
            }
        }
    }

    /** One of „Frühere Berichte“. */
    suspend fun show(id: Int) {
        val result = attempt { repository.transparencyReport(id) }
        result.onSuccess { report -> mutableState.update { it.copy(shown = report) } }
        result.onFailure { error -> mutableState.update { it.copy(error = (error as ApiError).userMessage) } }
    }

    /** Hides the error once the snackbar showed it. */
    fun dismissError() = mutableState.update { it.copy(error = null) }
}

/** „1.234“ – a figure as the web shows it. */
fun figureText(value: Int): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY).format(value)

/** „Wohin dein Beitrag geht“ (/transparenz). */
@Composable
fun TransparencyScreen(viewModel: TransparencyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TransparencyContent(state, onRefresh = viewModel.retry(viewModel::load), onShow = { id ->
        viewModel.viewModelScope.launch { viewModel.show(id) }
    }, viewModel::dismissError)
}

/** The page without its view model, for the app and for tests. */
@Composable
fun TransparencyContent(state: TransparencyUiState, onRefresh: suspend () -> Unit, onShow: (Int) -> Unit, onDismissError: () -> Unit) {
    OasisScreen(title = "Wohin dein Beitrag geht", error = state.error, onDismissError = onDismissError) {
        LoadableView(state.page, onRetry = onRefresh) { page ->
            MembershipPage(onRefresh) {
                PageHeader(
                    "Wohin dein Beitrag geht",
                    eyebrow = "Transparenz",
                    lead = "Oasis lebt von den Beiträgen seiner Mitglieder, nicht von Werbung. Hier steht, was wir einnehmen und wofür " +
                        "wir es ausgeben – nur mit echten Zahlen. Oasis wird betrieben von ${page.operator}."
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Heading("Oasis heute")
                    Figure(page.figures.activeMembers, "Mitglieder mit aktiver Mitgliedschaft")
                    Figure(page.figures.oases, "Oasen")
                    Figure(page.figures.ideasDone, "umgesetzte Ideen")
                    Text(
                        "Diese Zahlen zählt der Server selbst und aktualisiert sie laufend.",
                        style = OasisType.Small,
                        color = OasisColors.Muted
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Heading("Einnahmen und Ausgaben")
                    val report = state.shown
                    if (report == null) {
                        DashedNote("Noch keine Zahlen veröffentlicht.")
                    } else {
                        FinanceReportCard(report)
                        if (page.earlier.isNotEmpty()) Text("Frühere Berichte", style = OasisType.BodyBold, color = OasisColors.Night)
                        page.earlier.forEach { item ->
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(item.periodLabel) }
                                },
                                style = OasisType.Body,
                                color = OasisColors.Night,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 44.dp)
                                    .clickable(role = Role.Button) { onShow(item.id) }
                                    .padding(vertical = 10.dp)
                                    .testTag("transparency.report.${item.id}")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = OasisType.heading(24), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Figure(value: Int, label: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(figureText(value), style = OasisType.figure(), color = OasisColors.Night)
        Text(label, style = OasisType.Body, color = OasisColors.Ink2)
    }
}

/** A report in full: income, costs per category with their share, the total and „Überschuss“ / „Fehlbetrag“. */
@Composable
private fun FinanceReportCard(report: FinanceReport) {
    Panel(Modifier.testTag("transparency.report"), spacing = 10) {
        Text(report.periodLabel, style = OasisType.heading(21), color = OasisColors.Night)
        AmountRow("Einnahmen", report.income)
        report.costs.forEach { cost ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    buildAnnotatedString {
                        append(cost.label)
                        report.share(cost)?.let { share -> withStyle(SpanStyle(color = OasisColors.Muted)) { append(" $share") } }
                    },
                    style = OasisType.Body,
                    color = OasisColors.Ink2,
                    modifier = Modifier.weight(1f)
                )
                Text(FinanceReport.euros(cost.amount), style = OasisType.Body, color = OasisColors.Ink2)
            }
        }
        HorizontalDivider(color = OasisColors.DuneLine)
        AmountRow("Ausgaben gesamt", report.costsTotal)
        AmountRow(if (report.balance >= 0) "Überschuss" else "Fehlbetrag", abs(report.balance))
        report.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = OasisType.Body, color = OasisColors.Ink) }
    }
}

@Composable
private fun AmountRow(label: String, amount: Double) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = OasisType.BodyBold, color = OasisColors.Ink, modifier = Modifier.weight(1f))
        Text(FinanceReport.euros(amount), style = OasisType.BodyBold, color = OasisColors.Ink)
    }
}
