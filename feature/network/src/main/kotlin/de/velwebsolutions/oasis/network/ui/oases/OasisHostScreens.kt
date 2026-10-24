package de.velwebsolutions.oasis.network.ui.oases

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.PaperBox
import de.velwebsolutions.oasis.network.ui.components.StatGrid
import de.velwebsolutions.oasis.network.ui.components.ToggleRow
import de.velwebsolutions.oasis.network.ui.components.WeekChart
import de.velwebsolutions.oasis.network.ui.components.WeekRow
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.membersLabel

/**
 * "„…“ schließen?" (/oasen/{slug}/schliessen): host-only confirmation with an explicit checkbox; Tropfen stay with their
 * authors. Like the web, closing leads to the oasis overview.
 */
@Composable
fun CloseOasisScreen(slug: String) {
    val viewModel = hiltViewModel<CloseOasisViewModel, CloseOasisViewModel.Factory>(creationCallback = { it.create(slug) })
    val closing by viewModel.closing.collectAsStateWithLifecycle()
    val confirmed by viewModel.confirmed.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(closed) {
        if (closed) {
            navigator.popToRoot(MainTab.Oases)
            navigator.selectedTab = MainTab.Oases
        }
    }
    OasisScreen(title = "Oase schließen") {
        LoadableView(closing, onRetry = viewModel::load) { info ->
            NetworkPage {
                block("header") { PageHeader("„${info.name}“ schließen?") }
                block("form") {
                    PaperBox(spacing = 16) {
                        Bullets(
                            listOf(
                                "Die Oase verschwindet für alle ${membersLabel(info.membersCount)}.",
                                "Ihre ${info.postsCount} Tropfen bleiben bei den Menschen, die sie geschrieben haben – nur ohne Oase.",
                                "Das lässt sich nicht rückgängig machen."
                            )
                        )
                        ToggleRow(
                            "Ja, ich möchte die Oase endgültig schließen.",
                            confirmed,
                            viewModel::setConfirmed,
                            Modifier.testTag("closeOasis.confirm"),
                            style = OasisType.Body,
                            tint = OasisColors.SunText
                        )
                        (error?.message("confirm") ?: error?.userMessage)?.let { FieldError(it) }
                        AsyncOasisButton(
                            "Oase schließen",
                            viewModel::close,
                            kind = OasisButtonKind.Destructive,
                            fullWidth = false,
                            enabled = confirmed,
                            modifier = Modifier.testTag("closeOasis.submit")
                        )
                    }
                }
            }
        }
    }
}

/** Host statistics (/oasen/{slug}/statistik): sums only, never lists of people. */
@Composable
fun OasisStatisticsScreen(slug: String) {
    val viewModel = hiltViewModel<OasisStatisticsViewModel, OasisStatisticsViewModel.Factory>(creationCallback = { it.create(slug) })
    val statistics by viewModel.statistics.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    OasisScreen(title = "Statistik") {
        LoadableView(statistics, onRetry = viewModel::load) { stats ->
            NetworkPage {
                block("header") {
                    PageHeader(
                        "Statistik: ${stats.name}",
                        eyebrow = "Nur für Gastgeber:innen",
                        lead = "Nur Summen. Wer was liest oder schreibt, zeigen wir hier bewusst nicht."
                    )
                }
                block("tiles") {
                    StatGrid(
                        listOf(
                            stats.totals.members to "Mitglieder",
                            stats.totals.hosts to "Gastgeber:innen",
                            stats.totals.posts to "Tropfen insgesamt",
                            stats.totals.posts30Days to "Tropfen in 30 Tagen",
                            stats.totals.activeAuthors30Days to "Menschen, die in 30 Tagen geschrieben haben",
                            stats.totals.removed to "Von euch entfernte Tropfen"
                        )
                    )
                }
                block("weeks") {
                    WeekChart(
                        "Die letzten 12 Wochen",
                        listOf("Neue Mitglieder", "Tropfen"),
                        stats.weekly.map { WeekRow(it.week, listOf(it.joined, it.posts)) }
                    )
                }
            }
        }
    }
}
