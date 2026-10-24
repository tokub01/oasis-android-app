package de.velwebsolutions.oasis.staff.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Euro
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.CountBadge
import de.velwebsolutions.oasis.design.components.EmptyStateView
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.DeepLink
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.Labelled
import de.velwebsolutions.oasis.staff.api.StatTile
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/** Date formats of the staff pages, always in German time like the web (`d.m.Y H:i` in Europe/Berlin). */
object StaffDate {
    /** "22.10.2026 13:37". */
    fun short(instant: Instant?): String = instant?.let { GermanTime.format(it, "dd.MM.yyyy HH:mm") } ?: "–"

    /** "22.10.2026, 13:37 Uhr" (Sperren, Datenpannen). */
    fun withUhr(instant: Instant?): String = instant?.let(GermanTime::dayAndTime) ?: "–"

    /** "22. Okt. 2026, 13:37" (Zahlungen). */
    fun medium(instant: Instant?): String = instant?.let { GermanTime.format(it, "d. MMM yyyy, HH:mm") } ?: "–"

    /** A stored wall-clock value ("2026-11-01T10:00") as "1. Nov. 2026, 10:00", without a zone shift (votes, like the web). */
    fun wallClock(value: String): String {
        val instant = GermanTime.parse(value, "yyyy-MM-dd'T'HH:mm", ZoneOffset.UTC) ?: return value
        return GermanTime.format(instant, "d. MMM yyyy, HH:mm", ZoneOffset.UTC)
    }

    /** A date as the web's `datetime-local` value in German time ("2026-11-01T10:00"). */
    fun localInput(instant: Instant): String = GermanTime.format(instant, "yyyy-MM-dd'T'HH:mm")

    /** "3. Aug." for the week chart. */
    fun dayMonth(instant: Instant): String = GermanTime.format(instant, "d. MMM")
}

/** The waiting-time state of a provisional lock (`ok`, `soon`, `overdue`) as the web colours it. */
object DeadlineLook {
    fun background(state: String?): Color = when (state) {
        "overdue" -> OasisColors.Sun.copy(alpha = 0.35f)
        "soon" -> OasisColors.Sun.copy(alpha = 0.2f)
        else -> OasisColors.Dune
    }

    fun foreground(state: String?): Color = if (state == "overdue") OasisColors.SunText else OasisColors.Night
}

/** The pages of the web's staff navigation. */
enum class StaffArea(val key: String, val title: String, val icon: ImageVector, val route: StaffRoute) {
    Suspensions("suspensions", "Sperren", Icons.Outlined.Lock, StaffRoute.Suspensions),
    Roles("roles", "Rollen", Icons.Outlined.ManageAccounts, StaffRoute.Roles),
    Admin("admin", "Statistik", Icons.Outlined.BarChart, StaffRoute.AdminDashboard),
    Votes("votes", "Abstimmungen", Icons.Outlined.Verified, StaffRoute.AdminVotes),
    Payments("payments", "Zahlungen", Icons.Outlined.Euro, StaffRoute.AdminPayments),
    Breaches("breaches", "Datenpannen", Icons.Outlined.GppMaybe, StaffRoute.AdminBreaches),
    System("system", "System", Icons.Outlined.Dns, StaffRoute.AdminSystem)
    ;

    companion object {
        /** The admin pages, listed on the moderation start page and on the statistics. */
        val adminPages = listOf(Votes, Payments, Breaches, System)
    }
}

/** Links to staff pages, one row each; Sperren carries the number of locks waiting for a decision. */
@Composable
fun StaffAreaLinks(areas: List<StaffArea>, waitingLocks: Int = 0) {
    val navigator = LocalNavigator.current
    Column {
        areas.forEach { area ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp)
                    .pressable({ navigator.open(area.route) })
                    .testTag("staff.nav.${area.key}"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(area.icon, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(20.dp))
                Text(area.title, style = OasisType.BodyBold, color = OasisColors.Night)
                if (area == StaffArea.Suspensions && waitingLocks > 0) {
                    CountBadge(waitingLocks, Modifier.semantics { contentDescription = "$waitingLocks warten auf Entscheidung" })
                }
                Box(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = OasisColors.Muted)
            }
        }
    }
}

/** A grid of statistic tiles (value and label), like `x-network.stat`. */
@Composable
fun TileGrid(tiles: List<StatTile>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile ->
                    Column(
                        Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 78.dp)
                            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
                            .padding(14.dp)
                            .semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(tile.value, style = OasisType.heading(24), color = OasisColors.Night, maxLines = 1)
                        Text(tile.label, style = OasisType.Caption, color = OasisColors.Ink2)
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** A label/value row of a definition list. */
@Composable
fun FactRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = OasisType.SmallBold, color = OasisColors.Ink)
        SelectionContainer { Text(value, style = OasisType.Body, color = OasisColors.Ink) }
    }
}

/** A radio list (the web's radio fieldsets): one selectable row per option. */
@Composable
fun RadioList(legend: String, options: List<Labelled>, selection: String, onSelect: (String) -> Unit, tag: String, error: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(legend, style = OasisType.BodyBold, color = OasisColors.Ink, modifier = Modifier.semantics { heading() })
        options.forEach { option ->
            val selected = selection == option.value
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp)
                    .pressable({ onSelect(option.value) })
                    .semantics { this.selected = selected }
                    .testTag("$tag.${option.value}"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = OasisColors.Night
                )
                Text(option.label, style = OasisType.Body, color = OasisColors.Ink)
            }
        }
        if (error != null) FieldError(error)
    }
}

/**
 * Loads a screen and shows the web's states: spinner, error with retry, and the staff gate – "not allowed" and the
 * two-factor requirement with a way to the settings (the web redirects there with the same message).
 */
@Composable
fun <T> StaffLoadable(state: Loadable<T>, onRetry: suspend () -> Unit, content: @Composable (T) -> Unit) {
    val error = (state as? Loadable.Failed)?.error
    when {
        // The API answers both refusals with 403; only the two-factor one names it (EnsureStaff::TWO_FACTOR_REQUIRED).
        error is ApiError.Forbidden && error.serverMessage.contains("Zwei-Faktor") -> {
            val navigator = LocalNavigator.current
            EmptyStateView("Zwei-Faktor-Anmeldung nötig", message = error.serverMessage, icon = Icons.Outlined.Security) {
                OasisButton(
                    "Zu den Einstellungen",
                    { navigator.open(AccountRoute.Settings) },
                    Modifier.testTag("staff.twofactor.settings"),
                    fullWidth = false
                )
            }
        }

        error is ApiError.Forbidden -> EmptyStateView("Kein Zugang", message = error.userMessage, icon = Icons.Outlined.PanTool)

        else -> LoadableView(state, onRetry, content = content)
    }
}

/** Opens a link of the staff pages: Oasis links in the app, others in the in-app browser. */
@Composable
fun LinkText(url: String) {
    val navigator = LocalNavigator.current
    Text(
        url,
        style = OasisType.Small.copy(textDecoration = TextDecoration.Underline),
        color = OasisColors.Night,
        modifier = Modifier.pressable({ navigator.open(DeepLink.route(url) ?: WebRoute(url)) })
    )
}

/** "**Regel:** Text" – a bold lead-in followed by plain text. */
fun boldLead(lead: String, text: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lead) }
    append(text)
}

/** The page title of a staff screen (display font, a heading for TalkBack). */
@Composable
fun StaffTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = OasisType.display(), color = OasisColors.Night, modifier = modifier.semantics { heading() })
}

/** A section heading. */
@Composable
fun StaffHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = OasisType.heading(), color = OasisColors.Night, modifier = modifier.semantics { heading() })
}

/** A paper box with a border (cards of the queue, breaches, the Mollie panel). */
fun Modifier.staffBox(border: Color = OasisColors.PaperLine, width: Int = 1, radius: Int = OasisRadius.S): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return fillMaxWidth().background(OasisColors.Paper, shape).border(width.dp, border, shape)
}

/** The scrolling page of the staff screens (padding 16, at most 760 wide) with pull-to-refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffPage(onRefresh: suspend () -> Unit, spacing: Int = 18, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    onRefresh()
                } finally {
                    refreshing = false
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(spacing.dp),
                content = content
            )
        }
    }
}
