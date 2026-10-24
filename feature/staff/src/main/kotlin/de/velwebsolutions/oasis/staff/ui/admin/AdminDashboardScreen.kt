package de.velwebsolutions.oasis.staff.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.AdminDashboard
import de.velwebsolutions.oasis.staff.ui.components.StaffArea
import de.velwebsolutions.oasis.staff.ui.components.StaffAreaLinks
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle
import de.velwebsolutions.oasis.staff.ui.components.TileGrid
import de.velwebsolutions.oasis.staff.ui.components.boldLead
import kotlinx.coroutines.launch

/** Admin statistics (web: livewire.admin.dashboard). Sums only, never people or content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(viewModel: AdminDashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    OasisScreen("Statistik", error = state.error, onDismissError = viewModel::dismissError) {
        StaffLoadable(state.dashboard, viewModel::load) { stats ->
            StaffPage(viewModel::load, spacing = 22) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Admin")
                    StaffTitle("Statistik")
                    Text(
                        "Nur Summen. Zeitraumwerte beziehen sich auf die letzten ${stats.days} Tage.",
                        style = OasisType.Small,
                        color = OasisColors.Ink2
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("admin.period")) {
                        stats.periods.forEachIndexed { index, period ->
                            SegmentedButton(
                                selected = state.days == period,
                                onClick = { scope.launch { viewModel.selectDays(period) } },
                                shape = SegmentedButtonDefaults.itemShape(index, stats.periods.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = OasisColors.Night,
                                    activeContentColor = OasisColors.OnNight,
                                    inactiveContainerColor = OasisColors.Paper,
                                    inactiveContentColor = OasisColors.Night
                                )
                            ) { Text("$period Tage", style = OasisType.Caption) }
                        }
                    }
                }
                Panel { StaffAreaLinks(StaffArea.adminPages) }
                RevenueCard(stats.revenue) { month -> scope.launch { viewModel.showMonth(month) } }
                DashboardSection("KI-Prüfung (Mistral)", "(dieser Monat, nur diese Installation)") {
                    TileGrid(stats.ai.tiles)
                    stats.ai.note?.let { Text(it, style = OasisType.Caption, color = OasisColors.Ink2) }
                }
                DashboardSection("Mitglieder und Beiträge") {
                    TileGrid(stats.members.tiles)
                    if (stats.members.byTier.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            stats.members.byTier.forEach { tier ->
                                Text(
                                    boldLead(tier.name, ": ${tier.count}"),
                                    style = OasisType.Small,
                                    color = OasisColors.Ink,
                                    modifier = Modifier.background(
                                        OasisColors.Paper,
                                        CircleShape
                                    ).padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                DashboardSection("Netzwerk") {
                    TileGrid(stats.network.tiles)
                    WeekChart(stats.network.weeks)
                }
                DashboardSection("Empfehlungen") { TileGrid(stats.referrals.tiles) }
                DashboardSection("Chat", "(nur Metadaten – Inhalte sind verschlüsselt)") { TileGrid(stats.chat.tiles) }
                DashboardSection("Moderation") { TileGrid(stats.moderation.tiles) }
            }
        }
    }
}

@Composable
private fun DashboardSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(OasisType.heading().toSpanStyle().copy(color = OasisColors.Night)) { append(title) }
                if (subtitle != null) withStyle(OasisType.Small.toSpanStyle().copy(color = OasisColors.Muted)) { append(" $subtitle") }
            },
            modifier = Modifier.semantics { heading() }
        )
        content()
    }
}

/** The dark revenue card with month browsing (never into the future). */
@Composable
private fun RevenueCard(revenue: AdminDashboard.Revenue, onShow: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(OasisColors.Night, RoundedCornerShape(OasisRadius.M.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(revenue.title, style = OasisType.heading(), color = OasisColors.OnNight, modifier = Modifier.semantics { heading() })
        Text(revenue.period, style = OasisType.Caption, color = OasisColors.OnNightMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OasisButton("‹ Vormonat", {
                onShow(revenue.previousMonth)
            }, Modifier.testTag("revenue.previous"), kind = OasisButtonKind.Secondary, fullWidth = false)
            revenue.nextMonth?.let { next ->
                OasisButton("Nächster Monat ›", { onShow(next) }, kind = OasisButtonKind.Secondary, fullWidth = false)
                OasisButton("Dieser Monat", { onShow("") }, kind = OasisButtonKind.Accent, fullWidth = false)
            }
        }
        Text(revenue.amount, style = OasisType.display(44), color = OasisColors.OnNight, modifier = Modifier.testTag("revenue.amount"))
        Text(revenue.payments, style = OasisType.Small, color = OasisColors.OnNightMuted)
        Text(revenue.fees, style = OasisType.Caption, color = OasisColors.OnNightMuted)
        Text(revenue.comparison, style = OasisType.Caption, color = OasisColors.OnNightMuted)
    }
}

/** "Die letzten 12 Wochen": new accounts and Tropfen per week as bars, read out per week by TalkBack. */
@Composable
private fun WeekChart(weeks: List<AdminDashboard.Week>) {
    val maximum = maxOf(1, weeks.maxOfOrNull { maxOf(it.registered, it.posts) } ?: 1)
    Column(
        Modifier.fillMaxWidth().background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Die letzten 12 Wochen", style = OasisType.BodyBold, color = OasisColors.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend("Neue Konten", OasisColors.Spring)
            Legend("Tropfen", OasisColors.Sun)
        }
        weeks.forEach { week ->
            Row(
                Modifier.clearAndSetSemantics {
                    contentDescription = "Woche ab ${StaffDate.dayMonth(week.week)}: ${week.registered} neue Konten, ${week.posts} Tropfen"
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(StaffDate.dayMonth(week.week), style = OasisType.Caption, color = OasisColors.Ink, modifier = Modifier.width(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Bar(week.registered, maximum, OasisColors.Spring)
                    Bar(week.posts, maximum, OasisColors.Sun)
                }
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = OasisType.Caption, color = color)
    }
}

@Composable
private fun Bar(value: Int, maximum: Int, color: Color) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp)) {
        val width = ((maxWidth - 30.dp) * (value.toFloat() / maximum)).coerceAtLeast(2.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(width).height(10.dp).background(color, CircleShape))
            Text("$value", style = OasisType.Caption, color = OasisColors.Ink)
        }
    }
}
