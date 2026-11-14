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
import de.velwebsolutions.oasis.design.components.ConsentCheck
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.TierLoadableView
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.PaperBox
import de.velwebsolutions.oasis.network.ui.components.StatGrid
import de.velwebsolutions.oasis.network.ui.components.WeekChart
import de.velwebsolutions.oasis.network.ui.components.WeekRow
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.membersLabel
import de.velwebsolutions.oasis.network.ui.components.postsStayLine
import de.velwebsolutions.oasis.network.ui.retry

/**
 * "„…“ schließen?" (/crowds/{slug}/schliessen): host-only confirmation with an explicit checkbox; Posts stay with their
 * authors. Like the web, closing leads to the crowd overview; the closed crowd's screens leave the tab they were opened
 * on (a crowd reached from the Start or a profile) as well.
 */
@Composable
fun CloseOasisScreen(
    slug: String,
    viewModel: CloseOasisViewModel = hiltViewModel<CloseOasisViewModel, CloseOasisViewModel.Factory>(creationCallback = { it.create(slug) })
) {
    val closing by viewModel.closing.collectAsStateWithLifecycle()
    val confirmed by viewModel.confirmed.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(closed) {
        if (closed) {
            navigator.popToRoot()
            navigator.popToRoot(MainTab.Oases)
            navigator.selectedTab = MainTab.Oases
        }
    }
    OasisScreen(title = "Crowd schließen") {
        LoadableView(closing, onRetry = viewModel.retry(viewModel::load)) { info ->
            NetworkPage {
                block("header") { PageHeader("„${info.name}“ schließen?") }
                block("form") {
                    PaperBox(spacing = 16) {
                        Bullets(
                            listOf(
                                "Die Crowd verschwindet für alle ${membersLabel(info.membersCount)}.",
                                postsStayLine(info.postsCount),
                                "Das lässt sich nicht rückgängig machen."
                            )
                        )
                        ConsentCheck(
                            "Ja, ich möchte die Crowd endgültig schließen.",
                            confirmed,
                            viewModel::setConfirmed,
                            Modifier.testTag("closeOasis.confirm")
                        )
                        (error?.message("confirm") ?: error?.userMessage)?.let { FieldError(it) }
                        AsyncOasisButton(
                            "Crowd schließen",
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

/** Host statistics (/crowds/{slug}/statistik): sums only, never lists of people. */
@Composable
fun OasisStatisticsScreen(
    slug: String,
    viewModel: OasisStatisticsViewModel =
        hiltViewModel<OasisStatisticsViewModel, OasisStatisticsViewModel.Factory>(creationCallback = { it.create(slug) })
) {
    val statistics by viewModel.statistics.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    OasisScreen(title = "Statistik") {
        TierLoadableView(
            statistics,
            "Statistik deiner Crowd",
            onRetry = viewModel.retry(viewModel::load),
            feature = "Wie deine Crowd wächst und wie lebendig sie ist: neue Mitglieder und Posts pro Woche über zwölf Wochen, " +
                "nur für Gastgeber:innen. Wer was liest oder schreibt, zeigen die Zahlen nie."
        ) { stats ->
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
                            stats.totals.posts to "Posts insgesamt",
                            stats.totals.posts30Days to "Posts in 30 Tagen",
                            stats.totals.activeAuthors30Days to "Menschen, die in 30 Tagen geschrieben haben",
                            stats.totals.removed to "Von euch entfernte Posts"
                        )
                    )
                }
                block("weeks") {
                    WeekChart(
                        "Die letzten 12 Wochen",
                        listOf("Neue Mitglieder", "Posts"),
                        stats.weekly.map { WeekRow(it.week, listOf(it.joined, it.posts)) }
                    )
                }
            }
        }
    }
}
