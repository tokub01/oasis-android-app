package de.velwebsolutions.oasis.account.ui.member

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.api.ComplaintDecision
import de.velwebsolutions.oasis.account.api.Suspension
import de.velwebsolutions.oasis.account.ui.components.AccountPage
import de.velwebsolutions.oasis.account.ui.components.DataExportButton
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.account.ui.components.SubHeading
import de.velwebsolutions.oasis.account.ui.components.webUrl
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.EmptyStateView
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

private fun bold(before: String, bold: String, after: String = "") = buildAnnotatedString {
    append(before)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
    append(after)
}

/**
 * Konto gesperrt (/app/gesperrt): the accusation, how it was detected, the strike count and the statement; plus what
 * the member can still do. Without an active lock the app goes back to the Quelle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SuspendedScreen(viewModel: SuspensionViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    OasisScreen(
        title = "Konto gesperrt",
        message = ui.message,
        error = ui.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    viewModel.load()
                    viewModel.refreshMe()
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            AccountPage(maxWidth = 640) {
                val state = ui.state
                if (state is Loadable.Failed && state.error == ApiError.NotFound) {
                    EmptyStateView("Dein Konto ist nicht gesperrt.") {
                        AsyncOasisButton("Weiter zu Oasis", viewModel::refreshMe, fullWidth = false)
                    }
                } else {
                    LoadableView(state, onRetry = viewModel::load) { suspension ->
                        SuspendedContent(
                            suspension,
                            ui,
                            onStatement = viewModel::setStatement,
                            onSend = viewModel::save,
                            rules = { navigator.open(WebRoute(it)) },
                            export = { DataExportButton(viewModel::export, viewModel::showError) },
                            membership = { navigator.open(MembershipRoute.Overview) },
                            settings = { navigator.open(AccountRoute.Settings) },
                            signOut = viewModel::signOut
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SuspendedContent(
    suspension: Suspension,
    ui: SuspensionUiState,
    onStatement: (String) -> Unit,
    onSend: suspend () -> Unit,
    rules: (String) -> Unit,
    export: @Composable () -> Unit,
    membership: () -> Unit,
    settings: () -> Unit,
    signOut: suspend () -> Unit
) {
    val rulesUrl = webUrl("community-regeln")
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow(suspension.label)
            Text(
                if (suspension.isBan) "Dein Konto ist dauerhaft gesperrt" else "Dein Konto ist vorübergehend gesperrt",
                style = OasisType.display(30),
                color = OasisColors.Night,
                modifier = Modifier.semantics { heading() }.testTag("suspended.title")
            )
            val next = when {
                suspension.isProvisional ->
                    "Ein Mensch aus dem Moderationsteam prüft das, in der Regel innerhalb von ${suspension.reviewHours} Stunden. Bis dahin kannst du nichts schreiben und Oasis nicht lesen."

                suspension.isBan -> "Du kannst Oasis nicht mehr nutzen."

                else -> "Das Moderationsteam hebt die Sperre auf, wenn es dein Konto wieder freigibt."
            }
            Text("Seit ${GermanTime.dayAndTime(suspension.startedAt)}. $next", style = OasisType.Body, color = OasisColors.Ink2)
        }
        Column(
            Modifier.fillMaxWidth().background(OasisColors.Dune, RoundedCornerShape(OasisRadius.TILE.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SubHeading("Der Vorwurf")
            Text(suspension.accusation, style = OasisType.Body, color = OasisColors.Ink)
            val excerpt = suspension.excerpt
            if (!excerpt.isNullOrEmpty()) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(OasisColors.Sun))
                    Text("„$excerpt“", style = OasisType.Body, color = OasisColors.Ink2, modifier = Modifier.padding(start = 12.dp))
                }
            }
            if (suspension.isProvisional) {
                Text(
                    "Erkannt hat das ein automatisiertes Verfahren (Sprachmodell). Ob wirklich ein Verstoß vorliegt, entscheidet ein Mensch.",
                    style = OasisType.Small,
                    color = OasisColors.Ink2
                )
            }
            Text(
                bold(
                    "Bestätigte Verstöße bisher: ",
                    "${suspension.strikes} von ${suspension.strikeLimit}",
                    ". Nach ${suspension.strikeLimit} bestätigten Verstößen sperren wir ein Konto dauerhaft."
                ),
                style = OasisType.Small,
                color = OasisColors.Ink2
            )
            LinkText("Community-Regeln", { rules(rulesUrl) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SubHeading("Deine Stellungnahme")
            Text(
                if (suspension.isBan) {
                    "Hältst du die Sperre für falsch? Schreib uns, warum – das ist deine Beschwerde gegen die Entscheidung."
                } else {
                    "Du kannst dich entschuldigen, den Zusammenhang erklären oder widersprechen. Das Moderationsteam liest es, bevor es entscheidet."
                },
                style = OasisType.Body,
                color = OasisColors.Ink2
            )
            OasisTextField(
                "Stellungnahme",
                ui.statement,
                onStatement,
                modifier = Modifier.testTag("suspended.statement"),
                error = ui.statementError,
                hint =
                    suspension.statementAt?.let {
                        "Zuletzt gesendet am ${GermanTime.dayAndTime(it)}. Du kannst sie bis zur Entscheidung ändern."
                    }
                        ?: "Höchstens ${suspension.statementMax} Zeichen.",
                lines = 6..12
            )
            AsyncOasisButton("Stellungnahme senden", onSend, modifier = Modifier.testTag("suspended.send"), fullWidth = false)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SubHeading("Was du weiterhin kannst")
            export()
            LinkText("Mitgliedschaft ansehen oder kündigen", membership)
            LinkText("Einstellungen und Konto löschen", settings)
            val scope = rememberCoroutineScope()
            OasisTextButton("Abmelden", { scope.launch { signOut() } }, Modifier.testTag("suspended.signout"))
        }
    }
}

/** Widerspruch einlegen (/app/moderation/complaint/{decision}) for the affected member. */
@Composable
internal fun ComplaintScreen(decisionId: Int) {
    val viewModel = hiltViewModel<ComplaintViewModel, ComplaintViewModel.Factory>(creationCallback = { it.create(decisionId) })
    val navigator = LocalNavigator.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OasisScreen(title = "Widerspruch", error = ui.error, onDismissError = viewModel::dismissError) {
        AccountPage(maxWidth = 640, spacing = 18) {
            LoadableView(ui.state, onRetry = viewModel::load) { decision ->
                ComplaintContent(decision, ui, viewModel::setReason, viewModel::submit) {
                    if (!navigator.back()) navigator.dismissSheet()
                }
            }
        }
    }
}

@Composable
internal fun ComplaintContent(
    decision: ComplaintDecision,
    ui: ComplaintUiState,
    onReason: (String) -> Unit,
    onSubmit: suspend () -> Unit,
    onDone: () -> Unit
) {
    if (ui.submitted) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Danke, dein Widerspruch ist da.",
                style = OasisType.display(30),
                color = OasisColors.Night,
                modifier = Modifier.testTag("complaint.thanks")
            )
            Text("Wir haben dir eine Eingangsbestätigung per E-Mail geschickt.", style = OasisType.BodyBold, color = OasisColors.Ink)
            Text(
                "Eine andere Person aus unserem Team als die, die zuerst entschieden hat, prüft die Entscheidung. Das Ergebnis und die Begründung bekommst du per E-Mail.",
                style = OasisType.Body,
                color = OasisColors.Ink2
            )
            OasisButton("Fertig", onDone, fullWidth = false)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Widerspruch einlegen", style = OasisType.display(30), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
        Text("Eine andere Person aus unserem Team prüft die Entscheidung noch einmal.", style = OasisType.Body, color = OasisColors.Ink2)
        Column(
            Modifier.fillMaxWidth().background(OasisColors.Paper, RoundedCornerShape(OasisRadius.TILE.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("${decision.outcome} · ${decision.ground}", style = OasisType.BodyBold, color = OasisColors.Ink)
            if (decision.ruleReference.isNotEmpty()) {
                Text(
                    bold("", "Regel:", " ${decision.ruleReference}"),
                    style = OasisType.Small,
                    color = OasisColors.Ink
                )
            }
            Text(bold("", "Begründung:", " ${decision.explanation}"), style = OasisType.Small, color = OasisColors.Ink)
        }
        Text(
            "Du kannst bis zum ${GermanTime.day(
                decision.deadline
            )} widersprechen. Der Widerspruch ist kostenlos. Unabhängig davon stehen dir außergerichtliche Streitbeilegung und der Rechtsweg offen.",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
        OasisTextField(
            "Warum hältst du die Entscheidung für falsch?",
            ui.reason,
            onReason,
            modifier = Modifier.testTag("complaint.reason"),
            error = ui.reasonError,
            lines = 6..12
        )
        AsyncOasisButton(
            "Widerspruch einlegen",
            onSubmit,
            modifier = Modifier.testTag("complaint.submit"),
            enabled = ui.reason.isNotBlank()
        )
    }
}

/** Oasis-Begriffe (/app/begriffe). */
@Composable
internal fun GlossaryScreen(viewModel: GlossaryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OasisScreen(title = "Begriffe") {
        AccountPage(spacing = 16) {
            PageHeader("Oasis-Begriffe", eyebrow = "Kurz erklärt", lead = "Oasis hat ein paar eigene Wörter. Hier steht, was sie bedeuten.")
            LoadableView(state, onRetry = viewModel::load) { terms ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    terms.forEach { term ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
                                .padding(18.dp)
                                .semantics(mergeDescendants = true) {},
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(term.term, style = OasisType.BodyBold, color = OasisColors.Night)
                            Text(term.explanation, style = OasisType.Body, color = OasisColors.Ink2)
                        }
                    }
                }
            }
        }
    }
}
