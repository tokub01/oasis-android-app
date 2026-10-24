package de.velwebsolutions.oasis.staff.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.Decision
import de.velwebsolutions.oasis.staff.api.Labelled
import de.velwebsolutions.oasis.staff.api.ModerationCaseDetail
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.staff.ui.components.FactRow
import de.velwebsolutions.oasis.staff.ui.components.LinkText
import de.velwebsolutions.oasis.staff.ui.components.OptionPicker
import de.velwebsolutions.oasis.staff.ui.components.Sharing
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle
import de.velwebsolutions.oasis.staff.ui.components.boldLead
import de.velwebsolutions.oasis.staff.ui.components.staffBox
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch

/** What the case screen can do; the view model's methods in the app, fakes in tests. */
class CaseDetailActions(
    val onRefresh: suspend () -> Unit = {},
    val onSelectAssignee: (Int) -> Unit = {},
    val onStatement: (StatementOfReasons) -> Unit = {},
    val onReference: (String) -> Unit = {},
    val onAssign: suspend () -> Unit = {},
    val onDecide: suspend () -> Unit = {},
    val onEscalate: suspend (String) -> Unit = {},
    val onRecordReport: suspend () -> Unit = {},
    val onExtendEvidence: suspend () -> Unit = {},
    val onDeleteEvidence: suspend () -> Unit = {},
    val onLiftQuarantine: suspend () -> Unit = {},
    val onPrepareArchive: suspend () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onDismissError: () -> Unit = {}
)

/**
 * One moderation case (web: livewire.moderation.case-detail with the partials case-report, escalation and
 * decision-fields). The reporter is never shown.
 */
@Composable
fun CaseDetailScreen(caseId: Int) {
    val viewModel =
        hiltViewModel<CaseDetailViewModel, CaseDetailViewModel.Factory>(key = "case-$caseId", creationCallback = { it.create(caseId) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    CaseDetailContent(
        caseId,
        state,
        CaseDetailActions(
            onRefresh = viewModel::load,
            onSelectAssignee = viewModel::selectAssignee,
            onStatement = viewModel::updateStatement,
            onReference = viewModel::updateReference,
            onAssign = viewModel::assign,
            onDecide = viewModel::decide,
            onEscalate = viewModel::escalate,
            onRecordReport = viewModel::recordReport,
            onExtendEvidence = viewModel::extendEvidence,
            onDeleteEvidence = viewModel::deleteEvidence,
            onLiftQuarantine = viewModel::liftQuarantine,
            onPrepareArchive = viewModel::prepareArchive,
            onDismissMessage = viewModel::dismissMessage,
            onDismissError = viewModel::dismissError
        )
    )
}

@Composable
fun CaseDetailContent(caseId: Int, state: CaseDetailUiState, actions: CaseDetailActions) {
    val scope = rememberCoroutineScope()
    var confirmEscalation by remember { mutableStateOf<ModerationCaseDetail.EscalationOption?>(null) }
    var confirmDecision by remember { mutableStateOf(false) }
    var confirmDeleteEvidence by remember { mutableStateOf(false) }
    var confirmLiftQuarantine by remember { mutableStateOf(false) }
    OasisScreen(
        "Fall #$caseId",
        message = state.message,
        error = state.error,
        onDismissMessage = actions.onDismissMessage,
        onDismissError = actions.onDismissError
    ) {
        StaffLoadable(state.detail, actions.onRefresh) { detail ->
            StaffPage(actions.onRefresh) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StaffTitle("Fall #${detail.id}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(detail.status.label, Modifier.testTag("case.status"), tone = PillTone.Night)
                        Pill(detail.priority.label, tone = PillTone.Outline)
                    }
                }
                ReportSection(detail)
                val escalation = detail.escalation
                if (escalation != null) {
                    EscalatedSection(
                        detail,
                        escalation,
                        state,
                        actions,
                        onDeleteEvidence = { confirmDeleteEvidence = true },
                        onLiftQuarantine = { confirmLiftQuarantine = true }
                    )
                } else {
                    SectionCard("Eskalieren") {
                        Text(
                            "Nur in diesen zwei Fällen. Oasis sichert dann die Beweise und führt dich durch die nächsten Schritte. Eine " +
                                "Behörde informiert Oasis nie selbst.",
                            style = OasisType.Small,
                            color = OasisColors.Ink2
                        )
                        detail.escalationOptions.forEach { option ->
                            HorizontalDivider(color = OasisColors.PaperLine)
                            Text(boldLead("${option.label}: ", option.description), style = OasisType.Small, color = OasisColors.Ink)
                            OasisButton(
                                "Als „${option.label}“ eskalieren",
                                { confirmEscalation = option },
                                Modifier.testTag("case.escalate.${option.value}"),
                                kind = OasisButtonKind.Secondary,
                                fullWidth = false
                            )
                        }
                    }
                }
                if (detail.can.decide) {
                    SectionCard("Entscheidung") {
                        DecisionFields(state.statement, actions.onStatement, detail.outcomes, detail.grounds, state.fieldErrors, "case")
                        // A chosen measure is confirmed first; an incomplete form goes straight to the server, which names what is missing.
                        AsyncOasisButton(
                            "Entscheidung speichern und mitteilen",
                            {
                                if (state.statement.outcome.isEmpty() ||
                                    state.statement.ground.isEmpty()
                                ) {
                                    actions.onDecide()
                                } else {
                                    confirmDecision = true
                                }
                            },
                            Modifier.testTag("case.decide")
                        )
                    }
                }
                if (detail.decisions.isNotEmpty()) DecisionsSection(detail.decisions)
                if (detail.complaints.isNotEmpty()) ComplaintsSection(detail.complaints)
                if (detail.can.assign) AssignSection(detail, state, actions)
                HistorySection(detail.events)
            }
        }
    }
    confirmEscalation?.let { option ->
        ConfirmDialog(
            "Fall als „${option.label}“ eskalieren? Das lässt sich nicht rückgängig machen.",
            "Eskalieren",
            { scope.launch { actions.onEscalate(option.value) } },
            { confirmEscalation = null },
            destructive = true,
            confirmTag = "case.escalate.confirm"
        )
    }
    if (confirmDecision) {
        val detail = state.detail.valueOrNull
        val outcome = detail?.outcomes?.firstOrNull { it.value == state.statement.outcome }?.label.orEmpty()
        val ground = detail?.grounds?.firstOrNull { it.value == state.statement.ground }?.label.orEmpty()
        ConfirmDialog(
            "Entscheidung mitteilen?",
            "Speichern und mitteilen",
            { scope.launch { actions.onDecide() } },
            { confirmDecision = false },
            message = "$outcome · $ground. Die betroffene Person bekommt die Begründung per E-Mail.",
            confirmTag = "case.decide.confirm"
        )
    }
    if (confirmDeleteEvidence) {
        ConfirmDialog(
            "Alle Beweise dieses Falls endgültig löschen?",
            "Beweise löschen",
            { scope.launch { actions.onDeleteEvidence() } },
            { confirmDeleteEvidence = false },
            destructive = true
        )
    }
    if (confirmLiftQuarantine) {
        ConfirmDialog(
            "Quarantäne aufheben? Die Bilder werden dann wieder ausgeliefert, sobald der Tropfen freigegeben ist.",
            "Quarantäne aufheben",
            { scope.launch { actions.onLiftQuarantine() } },
            { confirmLiftQuarantine = false },
            destructive = true
        )
    }
}

/** "Meldung": what was reported and by which source; the reporter is never shown. */
@Composable
private fun ReportSection(detail: ModerationCaseDetail) {
    SectionCard("Meldung") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Gemeldeter Inhalt", style = OasisType.SmallBold, color = OasisColors.Ink)
            LinkText(detail.subjectUrl)
        }
        if (detail.report.kind == "chat_report") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Gemeldeter Klartext", style = OasisType.SmallBold, color = OasisColors.Ink)
                SandBox { Text(detail.report.plaintext.orEmpty(), style = OasisType.Digits.copy(fontSize = OasisType.Caption.fontSize)) }
            }
            FactRow(
                "Hinweis zur Prüfung",
                "Vom meldenden Mitglied übermittelt. Absender, Chat und Zeit sind per Signatur geprüft; ob der Text zur verschlüsselten " +
                    "Nachricht passt, kann der Server nicht prüfen."
            )
        }
        detail.report.check?.let { check ->
            FactRow("Quelle", "Automatische Erkennung (${check.sourceLabel}) · ${StaffDate.short(check.checkedAt)}")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Geprüfter Text", style = OasisType.SmallBold, color = OasisColors.Ink)
                SandBox { Text(check.checkedText ?: "Inhalt existiert nicht mehr.", style = OasisType.Small, color = OasisColors.Ink) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Einschätzung", style = OasisType.SmallBold, color = OasisColors.Ink)
                ScoreChips(check.scores)
                if (!check.reason.isNullOrEmpty()) Text(check.reason, style = OasisType.Small, color = OasisColors.Ink2)
                check.heldAt?.let { held ->
                    Text(
                        "Vorläufig ausgeblendet seit ${StaffDate.short(held)}. „Keine Maßnahme“ macht ihn wieder sichtbar.",
                        style = OasisType.SmallBold,
                        color = OasisColors.SunText
                    )
                }
            }
        }
        FactRow("Begründung der Meldung", detail.report.reason ?: "–")
        FactRow("Betroffenes Konto", detail.subjectUser?.let { "${it.displayName} (@${it.username})" } ?: "Nicht zugeordnet")
        FactRow("Eingang", StaffDate.short(detail.createdAt))
        Text("Wer gemeldet hat, ist im Moderationsbereich bewusst nicht sichtbar.", style = OasisType.Caption, color = OasisColors.Muted)
    }
}

@Composable
private fun SandBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().background(OasisColors.Sand, RoundedCornerShape(OasisRadius.CHIP.dp)).padding(10.dp)) {
        SelectionContainer { content() }
    }
}

/** The scores of the automated check as chips; flagged ones in the accent colour. */
@Composable
private fun ScoreChips(scores: List<ModerationCaseDetail.Score>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        scores.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { score ->
                    Text(
                        "${score.label}: ${score.percent} %",
                        style = if (score.flagged) OasisType.Caption.copy(fontWeight = FontWeight.Bold) else OasisType.Caption,
                        color = if (score.flagged) OasisColors.SunInk else OasisColors.Ink2,
                        modifier = Modifier
                            .background(if (score.flagged) OasisColors.Sun else OasisColors.Sand, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/** The escalated case: checklist, authority report, evidence and the admin steps. */
@Composable
private fun EscalatedSection(
    detail: ModerationCaseDetail,
    escalation: ModerationCaseDetail.Escalation,
    state: CaseDetailUiState,
    actions: CaseDetailActions,
    onDeleteEvidence: () -> Unit,
    onLiftQuarantine: () -> Unit
) {
    val context = LocalContext.current
    Column(
        Modifier.staffBox(OasisColors.Night, width = 2).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Eskaliert: ${escalation.type.label}",
            style = OasisType.heading(20),
            color = OasisColors.Night,
            modifier = Modifier.testTag("case.escalated")
        )
        Text(
            "${StaffDate.short(escalation.escalatedAt)} · ${escalation.escalatedByName ?: "automatisch erkannt"}",
            style = OasisType.Caption,
            color = OasisColors.Ink2
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            escalation.checklist.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = OasisType.Small, color = OasisColors.Ink)
            }
        }
        Text(
            "Kontaktweg der Behörde: ${escalation.authorityContact
                ?: "[noch festzulegen – vom Betreiber geprüften offiziellen Kontaktweg in config/oasis.php eintragen]"}",
            style = OasisType.Caption,
            color = OasisColors.Ink2
        )
        HorizontalDivider(color = OasisColors.PaperLine)
        Text("Meldung an eine Behörde", style = OasisType.BodyBold, color = OasisColors.Night)
        val reportedAt = escalation.authorityReportedAt
        if (reportedAt != null) {
            Text(
                "Vermerkt am ${StaffDate.short(reportedAt)}: ${escalation.authorityReference.orEmpty()}",
                style = OasisType.Small,
                color = OasisColors.Ink
            )
        } else {
            OasisTextField(
                "Behörde und Aktenzeichen, nachdem du gemeldet hast",
                state.reference,
                actions.onReference,
                Modifier.testTag("case.reference"),
                error = state.fieldErrors["reference"]
            )
            AsyncOasisButton("Meldung vermerken", actions.onRecordReport, Modifier.testTag("case.recordReport"), fullWidth = false)
        }
        HorizontalDivider(color = OasisColors.PaperLine)
        Text("Gesicherte Beweise (${escalation.evidence.size})", style = OasisType.BodyBold, color = OasisColors.Night)
        if (escalation.evidence.isEmpty()) Text("Keine Beweise (mehr) gespeichert.", style = OasisType.Small, color = OasisColors.Ink2)
        escalation.evidence.forEach { EvidenceRow(it) }
        if (detail.can.administrate) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (escalation.evidence.isNotEmpty()) {
                    val archive = state.archiveFile
                    if (archive != null) {
                        OasisButton(
                            "Beweis-Archiv teilen",
                            { Sharing.shareFile(context, archive, "application/zip", "Beweis-Archiv teilen") },
                            Modifier.testTag("case.evidence.share"),
                            fullWidth = false
                        )
                    } else {
                        AsyncOasisButton(
                            "Beweise für die Polizei herunterladen",
                            actions.onPrepareArchive,
                            Modifier.testTag("case.evidence.download"),
                            fullWidth = false
                        )
                    }
                    AsyncOasisButton(
                        "Aufbewahrung um ${detail.evidenceDays} Tage verlängern",
                        actions.onExtendEvidence,
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false
                    )
                    OasisButton("Beweise löschen", onDeleteEvidence, kind = OasisButtonKind.Secondary, fullWidth = false)
                }
                if (escalation.quarantined) {
                    OasisButton(
                        "Quarantäne aufheben",
                        onLiftQuarantine,
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false
                    )
                }
            }
        }
    }
}

/** One secured piece of evidence: kind, dates, checksum and the text behind a disclosure. */
@Composable
private fun EvidenceRow(evidence: ModerationCaseDetail.Evidence) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(OasisColors.Sand, RoundedCornerShape(OasisRadius.XS.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "${if (evidence.kind == "image") "Bild" else "Text"} · gesichert ${StaffDate.short(evidence.createdAt)} · gelöscht ab " +
                StaffDate.short(evidence.purgeAfter),
            style = OasisType.Caption.copy(fontWeight = FontWeight.Bold),
            color = OasisColors.Ink
        )
        evidence.sha256?.let { sha ->
            SelectionContainer {
                Text(
                    "SHA-256 $sha · ${NumberFormat.getIntegerInstance(Locale.GERMANY).format(evidence.bytes ?: 0)} Bytes",
                    style = OasisType.Digits.copy(fontSize = OasisType.Caption.fontSize),
                    color = OasisColors.Ink2
                )
            }
        }
        val content = evidence.content
        if (evidence.kind == "text" && !content.isNullOrEmpty()) {
            Text(
                if (expanded) "Text ausblenden" else "Text anzeigen",
                style = OasisType.SmallBold.copy(textDecoration = TextDecoration.Underline),
                color = OasisColors.Night,
                modifier = Modifier.pressable({ expanded = !expanded })
            )
            if (expanded) SelectionContainer { Text(content, style = OasisType.Small, color = OasisColors.Ink) }
        }
    }
}

/** "Entscheidungen": every statement of reasons, newest first. */
@Composable
private fun DecisionsSection(decisions: List<Decision>) {
    SectionCard("Entscheidungen") {
        decisions.forEach { decision ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(OasisColors.Sand, RoundedCornerShape(OasisRadius.XS.dp))
                    .padding(12.dp)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${decision.outcome.label} · ${decision.ground.label}", style = OasisType.BodyBold, color = OasisColors.Ink)
                    if (decision.isComplaintReview) Pill("nach Widerspruch", tone = PillTone.Sand)
                }
                Text(
                    "${decision.deciderName ?: "Gelöschtes Konto"} · ${StaffDate.short(decision.decidedAt)} · Mitgeteilt: " +
                        (decision.notifiedAt?.let(StaffDate::short) ?: "noch nicht"),
                    style = OasisType.Caption,
                    color = OasisColors.Ink2
                )
                Text(boldLead("Regel: ", decision.ruleReference), style = OasisType.Small, color = OasisColors.Ink)
                Text(boldLead("Tatsachen: ", decision.facts), style = OasisType.Small, color = OasisColors.Ink)
                Text(boldLead("Begründung: ", decision.explanation), style = OasisType.Small, color = OasisColors.Ink)
            }
        }
    }
}

@Composable
private fun ComplaintsSection(complaints: List<ModerationCaseDetail.ComplaintSummary>) {
    val navigator = LocalNavigator.current
    SectionCard("Widersprüche") {
        complaints.forEach { complaint ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(OasisColors.Sand, RoundedCornerShape(OasisRadius.XS.dp))
                    .pressable({ navigator.open(StaffRoute.ComplaintReview(complaint.id)) })
                    .padding(12.dp)
                    .testTag("case.complaint.${complaint.id}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${complaint.status.label} · ${StaffDate.short(complaint.submittedAt)}" +
                        (complaint.reviewerName?.let { " · geprüft von $it" } ?: ""),
                    style = OasisType.Small,
                    color = OasisColors.Ink,
                    modifier = Modifier.weight(1f)
                )
                Text("Ansehen", style = OasisType.SmallBold.copy(textDecoration = TextDecoration.Underline), color = OasisColors.Night)
            }
        }
    }
}

@Composable
private fun AssignSection(detail: ModerationCaseDetail, state: CaseDetailUiState, actions: CaseDetailActions) {
    SectionCard("Zuständig") {
        val options = detail.staff.map { Labelled(it.id.toString(), it.displayName + if (it.isMe) " (ich)" else "") }
        OptionPicker(
            "Zuständige Person",
            options,
            (state.assignTo ?: detail.assignDefault).toString(),
            { actions.onSelectAssignee(it.toInt()) },
            Modifier.testTag("case.assignee")
        )
        state.fieldErrors["assigned_to"]?.let { FieldError(it) }
        AsyncOasisButton("Zuweisen", actions.onAssign, Modifier.testTag("case.assign"), kind = OasisButtonKind.Secondary, fullWidth = false)
    }
}

/** "Verlauf": the append-only history. */
@Composable
private fun HistorySection(events: List<ModerationCaseDetail.Event>) {
    SectionCard("Verlauf") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            events.forEach { event ->
                Row(Modifier.height(IntrinsicSize.Min).semantics(mergeDescendants = true) {}) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(OasisColors.DuneLine))
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(event.type.label, style = OasisType.BodyBold, color = OasisColors.Ink)
                        Text(
                            "${event.actorName ?: "System"} · ${StaffDate.short(event.createdAt)}",
                            style = OasisType.Caption,
                            color = OasisColors.Ink2
                        )
                    }
                }
            }
        }
        Text("Der Verlauf lässt sich nicht ändern oder löschen.", style = OasisType.Caption, color = OasisColors.Muted)
    }
}
