package de.velwebsolutions.oasis.staff.ui.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.components.RelativeTimeText
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.SettingToggle
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.CaseSummary
import de.velwebsolutions.oasis.staff.api.ModerationOverview
import de.velwebsolutions.oasis.staff.api.QueueFilter
import de.velwebsolutions.oasis.staff.ui.components.DeadlineLook
import de.velwebsolutions.oasis.staff.ui.components.OptionPicker
import de.velwebsolutions.oasis.staff.ui.components.StaffArea
import de.velwebsolutions.oasis.staff.ui.components.StaffAreaLinks
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.components.StaffHeading
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle
import de.velwebsolutions.oasis.staff.ui.components.staffBox

/** The moderation start page (web: livewire.moderation.overview with the embedded queue and the staff navigation). */
@Composable
fun ModerationOverviewScreen(viewModel: ModerationOverviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModerationOverviewContent(state, viewModel::load, viewModel::apply, viewModel::loadMore, viewModel::dismissError)
}

/** The start page without its view model, for the app and for tests. */
@Composable
fun ModerationOverviewContent(
    state: ModerationOverviewUiState,
    onRefresh: suspend () -> Unit,
    onFilter: (QueueFilter) -> Unit,
    onLoadMore: suspend () -> Unit,
    onDismissError: () -> Unit = {}
) {
    OasisScreen("Moderation", error = state.error, onDismissError = onDismissError) {
        StaffLoadable(state.overview, onRefresh) { overview ->
            StaffPage(onRefresh, spacing = 20) {
                if (overview.waitingLocks.count > 0) WaitingLocksBanner(overview.waitingLocks)
                Header(overview.figures)
                FigureGrid(overview.figures, onFilter)
                StaffAreas(overview.navigation, overview.waitingLocks.count)
                QueueSection(state, onFilter, onLoadMore)
                ActivitySection(overview.activity)
            }
        }
    }
}

@Composable
private fun Header(figures: ModerationOverview.Figures) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StaffTitle("Moderation")
        Text(
            "Meldungen nach Art. 16 DSA. Jede Entscheidung braucht eine Begründung, die die Betroffenen verstehen.",
            style = OasisType.Body,
            color = OasisColors.Ink2
        )
        Text(mutedLead("Älteste wartende Meldung: ", figures.oldestWaitingLabel), style = OasisType.Small)
        Text(mutedLead("Median bis zur Entscheidung (30 Tage): ", figures.medianLabel), style = OasisType.Small)
    }
}

private fun mutedLead(lead: String, value: String) = buildAnnotatedString {
    withStyle(SpanStyle(color = OasisColors.Muted)) { append(lead) }
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = OasisColors.Ink)) { append(value) }
}

/** "n vorläufige Sperren warten auf eine Entscheidung" with the deadline state, links to the locks. */
@Composable
private fun WaitingLocksBanner(locks: ModerationOverview.WaitingLocks) {
    val navigator = LocalNavigator.current
    val color = DeadlineLook.foreground(locks.state)
    Column(
        Modifier
            .fillMaxWidth()
            .background(DeadlineLook.background(locks.state), RoundedCornerShape(OasisRadius.S.dp))
            .pressable({ navigator.open(StaffRoute.Suspensions) })
            .padding(16.dp)
            .testTag("moderation.waitingLocks"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "${locks.count} ${if (locks.count == 1) "vorläufige Sperre wartet" else "vorläufige Sperren warten"} auf eine Entscheidung",
            style = OasisType.BodyBold,
            color = OasisColors.Night
        )
        Text(
            "Die älteste wartet seit ${locks.oldestHours ?: 0} Std. – zugesagt sind höchstens ${locks.reviewHours} Std." +
                if (locks.state == "overdue") " Frist überschritten." else "",
            style = OasisType.Small,
            color = color
        )
        Text("Zu den Sperren", style = OasisType.SmallBold.copy(textDecoration = TextDecoration.Underline), color = OasisColors.Night)
    }
}

/** The six figure cards; each opens the queue with the matching filter like the web's links. */
@Composable
private fun FigureGrid(figures: ModerationOverview.Figures, onFilter: (QueueFilter) -> Unit) {
    data class Card(val label: String, val value: Int, val hint: String, val filter: QueueFilter, val highlight: Boolean)
    val cards = listOf(
        Card("Aktive Fälle", figures.active, "offen, in Prüfung oder mit Beschwerde", QueueFilter(), false),
        Card("Ohne Zuständigkeit", figures.unassigned, "noch niemandem zugewiesen", QueueFilter(status = "open"), true),
        Card("Dringend", figures.urgent, "zuerst bearbeiten", QueueFilter(priority = "urgent"), true),
        Card("Meine Fälle", figures.mine, "dir zugewiesen", QueueFilter(mine = true), false),
        Card(
            "Offene Beschwerden",
            figures.complaints,
            "Art. 20 DSA, zweite Person entscheidet",
            QueueFilter(status = "complaint_open"),
            true
        ),
        Card("Entschieden", figures.decidedWeek, "in den letzten 7 Tagen", QueueFilter(status = "decided"), false)
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { card ->
                    val alert = card.highlight && card.value > 0
                    val foreground = if (alert) OasisColors.SunInk else OasisColors.Ink
                    Column(
                        Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 104.dp)
                            .background(if (alert) OasisColors.Sun else OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
                            .semantics(mergeDescendants = true) {}
                            .clickable(onClickLabel = "Zeigt diese Fälle in der Warteschlange", role = Role.Button) {
                                onFilter(card.filter)
                            }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("${card.value}", style = OasisType.heading(28), color = foreground)
                        Text(card.label, style = OasisType.SmallBold, color = foreground)
                        Text(card.hint, style = OasisType.Caption, color = if (alert) OasisColors.SunInk else OasisColors.Muted)
                    }
                }
            }
        }
    }
}

/** The staff navigation of the web layout: Sperren (with the waiting count), Rollen and the admin pages. */
@Composable
private fun StaffAreas(navigation: ModerationOverview.Navigation, waitingLocks: Int) {
    val areas = listOf(StaffArea.Suspensions) +
        (if (navigation.canManageRoles) listOf(StaffArea.Roles) else emptyList()) +
        (if (navigation.canAdministrate) listOf(StaffArea.Admin) + StaffArea.adminPages else emptyList())
    Panel(spacing = 6) {
        Text("${navigation.displayName} · ${navigation.role.label}", style = OasisType.Caption, color = OasisColors.Muted)
        StaffAreaLinks(areas, waitingLocks)
    }
}

/** Filters and the queue (web: livewire.moderation.queue). */
@Composable
private fun QueueSection(state: ModerationOverviewUiState, onFilter: (QueueFilter) -> Unit, onLoadMore: suspend () -> Unit) {
    val navigator = LocalNavigator.current
    val filter = state.filter
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StaffHeading("Warteschlange")
        Panel {
            OptionPicker("Status", QueueFilter.statuses, filter.status, {
                onFilter(filter.copy(status = it))
            }, Modifier.testTag("queue.status"))
            OptionPicker("Priorität", QueueFilter.priorities, filter.priority, { onFilter(filter.copy(priority = it)) })
            OasisTextField(
                "Gemeldeter Link enthält",
                filter.search,
                { onFilter(filter.copy(search = it)) },
                keyboardOptions = OasisKeyboard.NoCapitalization
            )
            SettingToggle("Nur meine Fälle", filter.mine, { onFilter(filter.copy(mine = it)) })
        }
        if (state.cases.isEmpty() && !state.isLoadingQueue) {
            Text(
                "Keine Fälle für diese Auswahl. Gute Arbeit.",
                style = OasisType.Body,
                color = OasisColors.Ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp)).padding(28.dp)
            )
        }
        state.cases.forEach { item ->
            CaseRow(item, Modifier.pressable({ navigator.open(StaffRoute.ModerationCase(item.id)) }).testTag("queue.case.${item.id}"))
        }
        if (state.hasMore) {
            LaunchedEffect(state.cases.size) { onLoadMore() }
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = OasisColors.Night)
            }
        }
    }
}

/** One queue row: case number with badges, reported link with the reason, status, assignee, arrival. */
@Composable
private fun CaseRow(item: CaseSummary, modifier: Modifier) {
    Column(
        modifier.staffBox().padding(14.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${item.id}", style = OasisType.BodyBold.copy(textDecoration = TextDecoration.Underline), color = OasisColors.Night)
            item.escalation?.let { Pill(it.label, tone = PillTone.Night) }
            if (item.priority.value == "urgent") Pill(item.priority.label, tone = PillTone.Sun)
            Box(Modifier.weight(1f))
            Text(StaffDate.short(item.createdAt), style = OasisType.Caption, color = OasisColors.Muted)
        }
        Text(item.subjectUrl, style = OasisType.SmallBold, color = OasisColors.Night, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        item.reasonExcerpt?.let { Text(it, style = OasisType.Small, color = OasisColors.Ink2) }
        Text("${item.status.label} · Zuständig: ${item.assigneeName ?: "–"}", style = OasisType.Caption, color = OasisColors.Ink2)
    }
}

/** "Letzte Aktivität". */
@Composable
private fun ActivitySection(activity: List<ModerationOverview.Activity>) {
    val navigator = LocalNavigator.current
    SectionCard("Letzte Aktivität") {
        if (activity.isEmpty()) Text("Noch keine Aktivität.", style = OasisType.Body, color = OasisColors.Ink2)
        activity.forEach { event ->
            Column(
                Modifier.fillMaxWidth().pressable({ navigator.open(StaffRoute.ModerationCase(event.caseId)) }),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Fall #${event.caseId} · ${event.type.label}", style = OasisType.BodyBold, color = OasisColors.Night)
                Row {
                    Text("${event.actorName ?: "System"} · ", style = OasisType.Caption, color = OasisColors.Muted)
                    RelativeTimeText(event.createdAt)
                }
            }
        }
    }
}
