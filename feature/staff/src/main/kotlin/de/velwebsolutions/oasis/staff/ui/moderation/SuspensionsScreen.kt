package de.velwebsolutions.oasis.staff.ui.moderation

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisCard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.Suspension
import de.velwebsolutions.oasis.staff.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.staff.ui.components.DeadlineLook
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle
import de.velwebsolutions.oasis.staff.ui.components.boldLead
import kotlinx.coroutines.launch

/**
 * Locked accounts (web: livewire.moderation.suspensions): accusation, excerpt, the member's statement, strikes and
 * "Freigeben" (a permanent ban only by admins).
 */
@Composable
fun SuspensionsScreen(viewModel: SuspensionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SuspensionsContent(state, viewModel::load, viewModel::release, viewModel::dismissMessage, viewModel::dismissError)
}

@Composable
fun SuspensionsContent(
    state: SuspensionsUiState,
    onRefresh: suspend () -> Unit,
    onRelease: suspend (Suspension) -> Unit,
    onDismissMessage: () -> Unit = {},
    onDismissError: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var confirmRelease by remember { mutableStateOf<Suspension?>(null) }
    OasisScreen(
        "Sperren",
        message = state.message,
        error = state.error,
        onDismissMessage = onDismissMessage,
        onDismissError = onDismissError
    ) {
        StaffLoadable(state.list, onRefresh) { list ->
            StaffPage(onRefresh, spacing = 14) {
                StaffTitle("Sperren")
                Text(
                    "Konten, die gerade gesperrt sind. Eine vorläufige Sperre beginnt automatisch, wenn die Prüfung einen Tropfen " +
                        "zurückhält. Entscheide den verknüpften Fall oder gib das Konto hier frei. Nach ${list.strikesForBan} " +
                        "bestätigten Verstößen wird ein Konto dauerhaft gesperrt; das hebt nur ein Admin auf.",
                    style = OasisType.Small,
                    color = OasisColors.Ink2
                )
                if (list.active.isEmpty()) {
                    Text(
                        "Gerade ist kein Konto gesperrt.",
                        style = OasisType.Body,
                        color = OasisColors.Ink2,
                        modifier = Modifier.fillMaxWidth().background(
                            OasisColors.Paper,
                            RoundedCornerShape(OasisRadius.S.dp)
                        ).padding(18.dp)
                    )
                }
                list.active.forEach { lock -> LockCard(lock, list.strikesForBan, list.reviewHours) { confirmRelease = lock } }
                if (list.ended.isNotEmpty()) {
                    Text(
                        "Zuletzt beendet",
                        style = OasisType.heading(19),
                        color = OasisColors.Night,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    list.ended.forEach { lock ->
                        Text(
                            boldLead(
                                lock.user.displayName,
                                " · ${lock.label} · ${lock.resultLabel ?: "beendet"} · ${StaffDate.withUhr(lock.endedAt)}"
                            ),
                            style = OasisType.Small,
                            color = OasisColors.Ink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.CHIP.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
    confirmRelease?.let { lock ->
        ConfirmDialog(
            "Konto von ${lock.user.displayName} freigeben?",
            "Freigeben",
            { scope.launch { onRelease(lock) } },
            { confirmRelease = null },
            confirmTag = "suspension.release.confirm"
        )
    }
}

/** One active lock. */
@Composable
private fun LockCard(lock: Suspension, limit: Int, reviewHours: Int, onRelease: () -> Unit) {
    val navigator = LocalNavigator.current
    OasisCard(padding = 16) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lock.user.displayName) }
                    withStyle(SpanStyle(color = OasisColors.Muted)) { append(" @${lock.user.username}") }
                },
                style = OasisType.Body,
                color = OasisColors.Ink
            )
            Text(
                "${lock.label} · seit ${StaffDate.withUhr(lock.startedAt)} · Verstöße ${lock.user.activeStrikes ?: 0} von $limit",
                style = OasisType.Caption,
                color = OasisColors.Ink2
            )
            lock.deadlineState?.let { state ->
                Text(
                    "Wartet seit ${lock.waitingHours ?: 0} Std. von höchstens $reviewHours" +
                        if (state == "overdue") " – Frist überschritten" else "",
                    style = OasisType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = DeadlineLook.foreground(state),
                    modifier = Modifier.background(
                        DeadlineLook.background(state),
                        CircleShape
                    ).padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lock.caseId?.let { caseId ->
                    OasisButton("Fall entscheiden", {
                        navigator.open(StaffRoute.ModerationCase(caseId))
                    }, kind = OasisButtonKind.Secondary, fullWidth = false)
                }
                if (lock.canRelease == true) {
                    OasisButton("Freigeben", onRelease, Modifier.testTag("suspension.release.${lock.id}"), fullWidth = false)
                }
            }
            Text(boldLead("Vorwurf: ", lock.accusation.orEmpty()), style = OasisType.Small, color = OasisColors.Ink)
            lock.excerpt?.let { excerpt ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(OasisColors.Sun))
                    Text("„$excerpt“", style = OasisType.Small, color = OasisColors.Ink2, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Column(
                Modifier.fillMaxWidth().background(OasisColors.Dune, RoundedCornerShape(OasisRadius.XS.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Stellungnahme des Mitglieds", style = OasisType.SmallBold, color = OasisColors.Night)
                val statement = lock.statement
                if (statement != null) {
                    Text(statement, style = OasisType.Body, color = OasisColors.Ink)
                    Text(StaffDate.withUhr(lock.statementAt), style = OasisType.Caption, color = OasisColors.Muted)
                } else {
                    Text("Noch keine.", style = OasisType.Caption, color = OasisColors.Muted)
                }
            }
        }
    }
}
