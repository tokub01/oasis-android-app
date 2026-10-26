package de.velwebsolutions.oasis.membership.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/** The tone of a [StatusPill]. */
enum class StatusTone { Spring, Sun }

/**
 * The soft status pills of the status card ("Aktive Mitgliedschaft", "Gründungsmitglied mit Preisgarantie" …), as the
 * web's partial draws them (`bg-spring/15 text-spring-text`, `bg-sun/30 text-sun-ink`); the design's `Pill`
 * has no such light tones.
 */
@Composable
fun StatusPill(text: String, icon: ImageVector, modifier: Modifier, tone: StatusTone = StatusTone.Spring) {
    val foreground = when (tone) {
        StatusTone.Spring -> OasisColors.SpringText

        // StatusTone.Sun
        else -> OasisColors.SunInk
    }
    val background = when (tone) {
        StatusTone.Spring -> OasisColors.Spring.copy(alpha = 0.15f)
        else -> OasisColors.Sun.copy(alpha = 0.3f)
    }
    Row(
        modifier.background(background, CircleShape).padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
        Text(text, style = OasisType.SmallBold, color = foreground)
    }
}

/** "Gründungsmitglied mit Preisgarantie". */
@Composable
fun FoundingPill() {
    StatusPill("Gründungsmitglied mit Preisgarantie", Icons.Outlined.Shield, Modifier.testTag("membership.founding"), StatusTone.Sun)
}

/** The contract summary box of the cancel and withdraw pages (`dl` on dune). */
@Composable
fun ContractDetails(rows: List<Pair<String, String>>) {
    Column(
        Modifier.fillMaxWidth().background(OasisColors.Dune, RoundedCornerShape(OasisRadius.CHIP.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { (label, value) ->
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = OasisType.SmallBold, color = OasisColors.Muted)
                Text(value, style = OasisType.BodyBold, color = OasisColors.Night)
            }
        }
    }
}

/** A text link in the web's `o-link` style. */
@Composable
fun InlineLink(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color) {
    Box(modifier.defaultMinSize(minHeight = 44.dp).pressable(onClick), contentAlignment = Alignment.CenterStart) {
        Text(title, style = OasisType.SmallBold.copy(textDecoration = TextDecoration.Underline), color = color)
    }
}

/** Text with `**bold**` parts, like the Markdown of the iOS texts. */
fun boldMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { index, part ->
        if (index % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}

/** A bulleted list whose items may contain `**bold**` parts. */
@Composable
fun RichBullets(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = OasisType.Body, color = OasisColors.Ink2)
                Text(boldMarkdown(item), style = OasisType.Body, color = OasisColors.Ink2)
            }
        }
    }
}

/** The scrolling page of the membership screens (padding 20, at most 760 wide) with pull-to-refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembershipPage(onRefresh: (suspend () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val page: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                content = content
            )
        }
    }
    if (onRefresh == null) {
        page()
    } else {
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
        ) { page() }
    }
}

/** Opens a page of the website (`/agb`, `/hilfe` …) in a Custom Tab. */
@Composable
fun rememberOpenWebsite(navigator: Navigator): (String) -> Unit {
    val client = LocalApiClient.current
    return remember(client, navigator) {
        { path: String ->
            val url = client?.absoluteUrl(path)?.toString()
            if (url != null) navigator.open(WebRoute(url))
        }
    }
}

/** Back to the overview: pop when it is the previous screen, otherwise open it. */
fun backToOverview(navigator: Navigator) {
    val stack = navigator.sheetStack.ifEmpty { navigator.stack(navigator.selectedTab) }
    if (stack.dropLast(1).lastOrNull() == MembershipRoute.Overview) {
        navigator.back()
    } else {
        navigator.handle(MembershipRoute.Overview)
    }
}
