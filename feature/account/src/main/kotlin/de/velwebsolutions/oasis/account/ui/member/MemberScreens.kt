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
import de.velwebsolutions.oasis.account.api.SuspensionComplaint
import de.velwebsolutions.oasis.account.ui.components.AccountPage
import de.velwebsolutions.oasis.account.ui.components.DataExportButton
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.account.ui.components.SubHeading
import de.velwebsolutions.oasis.account.ui.components.webUrl
import de.velwebsolutions.oasis.account.ui.retry
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
 * Konto gesperrt (/app/gesperrt): a lock with a duration ("bis zum …") or the permanent ban, the accusation, the strike
 * count of the last months and the statement; plus what the member can still do. Without an active lock the app goes
 * back to the Start.
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
                        AsyncOasisButton("Weiter zu Mildcrowd", viewModel::refreshMe, fullWidth = false)
                    }
                } else {
                    LoadableView(state, onRetry = viewModel.retry(viewModel::load)) { suspension ->
                        SuspendedContent(
                            suspension,
                            ui,
                            onStatement = viewModel::setStatement,
                            onSend = viewModel::save,
                            onComplaintReason = viewModel::setComplaintReason,
                            onComplain = viewModel::complain,
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
    onComplaintReason: (String) -> Unit,
    onComplain: suspend () -> Unit,
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
            val endsAt = suspension.endsAtLabel
            Text(
                when {
                    suspension.isBan -> "Dein Konto ist dauerhaft gesperrt"
                    endsAt != null -> "Dein Konto ist bis zum $endsAt gesperrt"
                    else -> "Dein Konto ist gesperrt"
                },
                style = OasisType.display(30),
                color = OasisColors.Night,
                modifier = Modifier.semantics { heading() }.testTag("suspended.title")
            )
            val next = when {
                suspension.isBan -> "Du kannst Mildcrowd nicht mehr nutzen."
                endsAt != null -> "Die Sperre endet am $endsAt von selbst. Bis dahin kannst du nichts schreiben und Mildcrowd nicht lesen."
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
            Text(
                "Entschieden hat das ein Mensch aus dem Moderationsteam; die Begründung hast du per E-Mail bekommen.",
                style = OasisType.Small,
                color = OasisColors.Ink2
            )
            Text(
                bold(
                    "Bestätigte Verstöße in den letzten ${suspension.strikeMonths} Monaten: ",
                    "${suspension.strikes} von ${suspension.strikeLimit}",
                    ". Nach ${suspension.strikeLimit} bestätigten Verstößen innerhalb von ${suspension.strikeMonths} Monaten " +
                        "sperren wir ein Konto dauerhaft."
                ),
                style = OasisType.Small,
                color = OasisColors.Ink2
            )
            LinkText("Community-Regeln", { rules(rulesUrl) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SubHeading("Deine Stellungnahme")
            Text(
                "Du kannst dich entschuldigen oder den Zusammenhang erklären. Das Moderationsteam liest deine Stellungnahme. " +
                    "Eine Stellungnahme ist keine Beschwerde gegen die Entscheidung – die legst du unten ein.",
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
                        "Zuletzt gesendet am ${GermanTime.dayAndTime(it)}. Du kannst sie während der Sperre ändern."
                    }
                        ?: "Höchstens ${suspension.statementMax} Zeichen.",
                lines = 6..12
            )
            AsyncOasisButton("Stellungnahme senden", onSend, modifier = Modifier.testTag("suspended.send"), fullWidth = false)
        }
        ComplaintSection(suspension.complaint ?: SuspensionComplaint(), ui, onComplaintReason, onComplain, rules)
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

/**
 * „Beschwerde gegen die Entscheidung“ on the lock screen (suspended.blade.php, AGB § 15): the procedure, then the form
 * while the complaint is possible, the thanks once it is in, or the DSA address when it is not possible here.
 */
@Composable
private fun ComplaintSection(
    complaint: SuspensionComplaint,
    ui: SuspensionUiState,
    onReason: (String) -> Unit,
    onComplain: suspend () -> Unit,
    open: (String) -> Unit
) {
    val termsUrl = webUrl("agb#beschwerde")
    val email = complaint.email ?: DSA_EMAIL
    val mail = { open("mailto:$email") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubHeading("Beschwerde gegen die Entscheidung")
        Text(
            "Hältst du die Sperre für falsch? Dann leg Beschwerde gegen die Entscheidung ein, die zu ihr geführt hat. Eine andere " +
                "Person aus dem Moderationsteam als die, die entschieden hat, prüft sie – nicht automatisiert – und schreibt dir das " +
                "Ergebnis mit Begründung per E-Mail. Ist die Beschwerde berechtigt, heben wir die Sperre auf. " +
                "Die Beschwerde ist kostenlos.",
            style = OasisType.Body,
            color = OasisColors.Ink2
        )
        LinkText("AGB § 15", { open(termsUrl) })
        val deadline = complaint.deadline
        when {
            complaint.submittedAt != null || ui.complaintSubmitted -> Text(
                "Danke, deine Beschwerde ist da. Die Eingangsbestätigung haben wir dir per E-Mail geschickt; das Ergebnis und die " +
                    "Begründung folgen per E-Mail.",
                style = OasisType.BodyBold,
                color = OasisColors.SpringText,
                modifier = Modifier.testTag("suspended.complaintThanks")
            )

            complaint.allowed && deadline != null -> {
                OasisTextField(
                    "Warum hältst du die Entscheidung für falsch?",
                    ui.complaintReason,
                    onReason,
                    modifier = Modifier.testTag("suspended.complaint"),
                    error = ui.complaintError,
                    hint = "Möglich bis zum ${GermanTime.day(deadline)}.",
                    lines = 6..12,
                    maxLength = COMPLAINT_MAX
                )
                AsyncOasisButton(
                    "Beschwerde einlegen",
                    onComplain,
                    modifier = Modifier.testTag("suspended.complain"),
                    fullWidth = false,
                    enabled = ui.complaintReason.isNotBlank()
                )
            }

            else -> {
                val why = if (complaint.decisionId == null) ", weil zu dieser Sperre kein Fall hinterlegt ist" else ""
                Text(
                    "Hier geht das gerade nicht$why. Schreib deine Beschwerde per E-Mail an $email, oder nutze den Link in der E-Mail " +
                        "zur Entscheidung.",
                    style = OasisType.Body,
                    color = OasisColors.Ink2,
                    modifier = Modifier.testTag("suspended.complaintClosed")
                )
            }
        }
        Text(
            "Du kannst die Beschwerde auch per E-Mail an $email schicken. Unabhängig davon steht dir der Rechtsweg offen; eine " +
                "außergerichtliche Streitbeilegungsstelle gibt es für Mildcrowd nicht (AGB § 15 und § 20).",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
        LinkText("E-Mail an $email", mail, Modifier.testTag("suspended.complaintMail"))
    }
}

/** The web's `maxlength` of a complaint's reason (SubmitComplaint::rules). */
private const val COMPLAINT_MAX = 10_000

/** The DSA contact address (`mildcrowd.mail.dsa`) when an older server sends none. */
private const val DSA_EMAIL = "info@velwebsolutions.de"

/** Beschwerde einlegen (/app/moderation/complaint/{decision}) for the affected member. */
@Composable
internal fun ComplaintScreen(decisionId: Int) {
    val viewModel = hiltViewModel<ComplaintViewModel, ComplaintViewModel.Factory>(creationCallback = { it.create(decisionId) })
    val navigator = LocalNavigator.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OasisScreen(title = "Beschwerde", error = ui.error, onDismissError = viewModel::dismissError) {
        AccountPage(maxWidth = 640, spacing = 18) {
            LoadableView(ui.state, onRetry = viewModel.retry(viewModel::load)) { decision ->
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
                "Danke, deine Beschwerde ist da.",
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
        Text("Beschwerde einlegen", style = OasisType.display(30), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
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
            "Du kannst bis zum ${GermanTime.day(decision.deadline)} Beschwerde einlegen. Die Beschwerde ist kostenlos. Unabhängig davon " +
                "steht dir der Rechtsweg offen. Eine außergerichtliche Streitbeilegungsstelle gibt es für Mildcrowd nicht (AGB § 15 und § 20).",
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
            "Beschwerde einlegen",
            onSubmit,
            modifier = Modifier.testTag("complaint.submit"),
            enabled = ui.reason.isNotBlank()
        )
    }
}

/** Mildcrowd-Begriffe (/app/begriffe). */
@Composable
internal fun GlossaryScreen(viewModel: GlossaryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OasisScreen(title = "Begriffe") {
        AccountPage(spacing = 16) {
            PageHeader(
                "Mildcrowd-Begriffe",
                eyebrow = "Kurz erklärt",
                lead = "Mildcrowd hat ein paar eigene Wörter. Hier steht, was sie bedeuten."
            )
            LoadableView(state, onRetry = viewModel.retry(viewModel::load)) { terms ->
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
