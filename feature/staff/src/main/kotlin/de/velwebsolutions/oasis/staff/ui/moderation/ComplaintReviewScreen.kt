package de.velwebsolutions.oasis.staff.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.ComplaintDetail
import de.velwebsolutions.oasis.staff.api.Labelled
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.staff.ui.components.RadioList
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle
import de.velwebsolutions.oasis.staff.ui.components.boldLead
import kotlinx.coroutines.launch

/**
 * Review of a complaint (web: livewire.moderation.complaint-review). A different person than the original decider
 * decides it (DSA Art. 20, AGB § 12).
 */
@Composable
fun ComplaintReviewScreen(complaintId: Int) {
    val viewModel = hiltViewModel<ComplaintReviewViewModel, ComplaintReviewViewModel.Factory>(
        key = "complaint-$complaintId",
        creationCallback = { it.create(complaintId) }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    ComplaintReviewContent(
        state,
        onRefresh = viewModel::load,
        onResult = viewModel::selectResult,
        onExplanation = viewModel::updateExplanation,
        onStatement = viewModel::updateStatement,
        onReview = viewModel::review,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    )
}

@Composable
fun ComplaintReviewContent(
    state: ComplaintReviewUiState,
    onRefresh: suspend () -> Unit,
    onResult: (String) -> Unit,
    onExplanation: (String) -> Unit,
    onStatement: (StatementOfReasons) -> Unit,
    onReview: suspend () -> Unit,
    onDismissMessage: () -> Unit = {},
    onDismissError: () -> Unit = {}
) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var confirmReview by remember { mutableStateOf(false) }
    OasisScreen(
        "Widerspruch",
        message = state.message,
        error = state.error,
        onDismissMessage = onDismissMessage,
        onDismissError = onDismissError
    ) {
        StaffLoadable(state.complaint, onRefresh) { complaint ->
            StaffPage(onRefresh) {
                Text(
                    "← Fall #${complaint.caseId}",
                    style = OasisType.Small.copy(textDecoration = TextDecoration.Underline),
                    color = OasisColors.Night,
                    modifier = Modifier.pressable({ navigator.open(StaffRoute.ModerationCase(complaint.caseId)) })
                )
                StaffTitle("Widerspruch")
                Text(
                    "${complaint.status.label} · eingegangen am ${StaffDate.short(complaint.submittedAt)}",
                    style = OasisType.Small,
                    color = OasisColors.Ink2
                )
                SectionCard("Ursprüngliche Entscheidung") {
                    Text(
                        "${complaint.decision.outcome.label} · ${complaint.decision.ground.label}",
                        style = OasisType.BodyBold,
                        color = OasisColors.Ink
                    )
                    Text(
                        "Entschieden von ${complaint.decision.deciderName ?: "Gelöschtes Konto"} am ${StaffDate.short(
                            complaint.decision.decidedAt
                        )}",
                        style = OasisType.Caption,
                        color = OasisColors.Ink2
                    )
                    Text(boldLead("Regel: ", complaint.decision.ruleReference), style = OasisType.Small, color = OasisColors.Ink)
                    Text(boldLead("Begründung: ", complaint.decision.explanation), style = OasisType.Small, color = OasisColors.Ink)
                }
                SectionCard("Begründung des Widerspruchs") {
                    SelectionContainer { Text(complaint.reason, style = OasisType.Body, color = OasisColors.Ink) }
                }
                val result = complaint.result
                when {
                    result != null -> SectionCard("Ergebnis") {
                        Text(
                            "${complaint.status.label} · geprüft von ${result.reviewerName ?: "Gelöschtes Konto"} am ${StaffDate.short(
                                result.reviewedAt
                            )}",
                            style = OasisType.Small,
                            color = OasisColors.Ink,
                            modifier = Modifier.testTag("complaint.result")
                        )
                        Text(result.explanation.orEmpty(), style = OasisType.Body, color = OasisColors.Ink)
                    }

                    complaint.canReview -> ReviewForm(complaint, state, onResult, onExplanation, onStatement) {
                        // A chosen result is confirmed first; without one the server names what is missing.
                        val incomplete = state.result.isEmpty() ||
                            (state.result == "overturned" && (state.statement.outcome.isEmpty() || state.statement.ground.isEmpty()))
                        if (incomplete) onReview() else confirmReview = true
                    }

                    else -> Text(
                        "Diesen Widerspruch prüft eine andere Person aus dem Team, weil du die ursprüngliche Entscheidung getroffen hast.",
                        style = OasisType.Small,
                        color = OasisColors.Ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.XS.dp))
                            .padding(14.dp)
                            .testTag("complaint.otherReviewer")
                    )
                }
            }
        }
    }
    if (confirmReview) {
        ConfirmDialog(
            "Ergebnis mitteilen?",
            "Speichern und mitteilen",
            { scope.launch { onReview() } },
            { confirmReview = false },
            message = reviewSummary(state),
            confirmTag = "complaint.save.confirm"
        )
    }
}

/** What the result does, before it goes out to the complainant and the affected person. */
private fun reviewSummary(state: ComplaintReviewUiState): String {
    val complaint = state.complaint.valueOrNull
    if (state.result != "overturned" || complaint == null) {
        return "Die Entscheidung bleibt bestehen. Die Person, die widersprochen hat, bekommt die Begründung per E-Mail."
    }
    val outcome = complaint.outcomes.firstOrNull { it.value == state.statement.outcome }?.label.orEmpty()
    val ground = complaint.grounds.firstOrNull { it.value == state.statement.ground }?.label.orEmpty()
    return "Die Entscheidung wird geändert: $outcome · $ground. Die Betroffenen bekommen die Begründung per E-Mail."
}

@Composable
private fun ReviewForm(
    complaint: ComplaintDetail,
    state: ComplaintReviewUiState,
    onResult: (String) -> Unit,
    onExplanation: (String) -> Unit,
    onStatement: (StatementOfReasons) -> Unit,
    onSave: suspend () -> Unit
) {
    SectionCard("Prüfung") {
        RadioList(
            "Ergebnis",
            listOf(Labelled("upheld", "Entscheidung bleibt bestehen"), Labelled("overturned", "Entscheidung wird geändert")),
            state.result,
            onResult,
            "complaint.result",
            state.fieldErrors["result"]
        )
        if (state.result == "overturned") {
            Column(
                Modifier.fillMaxWidth().border(1.dp, OasisColors.DuneLine, RoundedCornerShape(OasisRadius.XS.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Neue Entscheidung", style = OasisType.BodyBold, color = OasisColors.Ink)
                Text(
                    "Die Begründung für die Betroffenen ist zugleich die Begründung des Ergebnisses.",
                    style = OasisType.Caption,
                    color = OasisColors.Muted
                )
                DecisionFields(state.statement, onStatement, complaint.outcomes, complaint.grounds, state.fieldErrors, "review")
            }
        } else {
            OasisTextField(
                "Begründung des Ergebnisses",
                state.explanation,
                onExplanation,
                Modifier.testTag("complaint.explanation"),
                error = state.fieldErrors["explanation"],
                lines = 5..12
            )
        }
        AsyncOasisButton("Ergebnis speichern und mitteilen", onSave, Modifier.testTag("complaint.save"))
    }
}
