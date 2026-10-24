package de.velwebsolutions.oasis.network.ui.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowCircleRight
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.SkeletonLayout
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.ui.PostListState
import de.velwebsolutions.oasis.network.ui.components.ComposeFab
import de.velwebsolutions.oasis.network.ui.components.FabInset
import de.velwebsolutions.oasis.network.ui.components.FeedHandlers
import de.velwebsolutions.oasis.network.ui.components.OasisRow
import de.velwebsolutions.oasis.network.ui.components.SectionTitle
import de.velwebsolutions.oasis.network.ui.components.TextLink
import de.velwebsolutions.oasis.network.ui.components.UnderlinedLink
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.feedSection
import de.velwebsolutions.oasis.network.ui.components.handlers
import java.time.Instant

/**
 * "Quelle" (/app): own Tropfen and those of friends, followed people and oases, newest first, with bundled replies – or
 * "Ganz Oasis". No ranking and an honest end; the side blocks (Deine Woche, Deine Oasen, a running vote, inviting) follow
 * the feed like on a phone-sized web page. Switching the view keeps the page and swaps the cards in place.
 */
@Composable
fun SourceScreen(viewModel: SourceViewModel = hiltViewModel<SourceViewModel>()) {
    val state by viewModel.list.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val scrollToTop by viewModel.scrollToTop.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val listState = rememberLazyListState()
    val me = (session as? SessionState.SignedIn)?.me

    LaunchedEffect(Unit) {
        if (me?.onboarded == false && !viewModel.askedWelcome) {
            viewModel.askedWelcome = true
            navigator.present(AccountRoute.Welcome)
        }
        viewModel.list.loadIfNeeded()
    }
    LaunchedEffect(scrollToTop) { if (scrollToTop > 0) listState.animateScrollToItem(0) }

    OasisScreen(
        title = "Quelle",
        message = state.status,
        error = state.actionError?.userMessage,
        onDismissMessage = viewModel.list::dismissStatus,
        onDismissError = viewModel.list::dismissError
    ) {
        val canPost = state.context?.canPost != false
        Box(Modifier.fillMaxSize()) {
            LoadableView(state.state, onRetry = viewModel.list::reload, skeleton = SkeletonLayout.Cards) {
                val handlers = viewModel.list.handlers(viewModel::launch)
                FeedPage(listState, handlers, bottomInset = if (canPost) FabInset else 0.dp) {
                    sourceContent(state, me?.displayName.orEmpty(), handlers, onSelectView = viewModel::selectView)
                }
            }
            if (state.state.valueOrNull != null && canPost) {
                ComposeFab(listState, onClick = { navigator.present(NetworkRoute.Compose()) }, Modifier.testTag("source.compose"))
            }
        }
    }
}

/** The Quelle's page: greeting, view switch, composer entry, next steps, the feed and the side blocks. */
@OptIn(ExperimentalMaterial3Api::class)
fun LazyListScope.sourceContent(
    state: PostListState<SourceContext>,
    displayName: String,
    handlers: FeedHandlers,
    onSelectView: (String) -> Unit
) {
    val context = state.context
    val showAll = context?.showAll ?: false
    block("header") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Quelle")
            Text(
                "Hallo $displayName.",
                style = OasisType.display(28),
                color = OasisColors.Night,
                modifier = Modifier.semantics {
                    heading()
                }
            )
            Text(
                if (showAll) {
                    "Hier siehst du der Reihe nach, was alle auf Oasis teilen – ohne Algorithmus."
                } else {
                    "Hier siehst du der Reihe nach, was deine Freund:innen und die Menschen und Oasen teilen, denen du folgst."
                },
                style = OasisType.Body,
                color = OasisColors.Ink2
            )
        }
    }
    block("view") {
        val navigator = LocalNavigator.current
        // The switch shows the chosen view at once; until the member chooses, what the server chose (members who follow
        // nobody start with Ganz Oasis).
        val selection = state.filter ?: if (showAll) "alle" else "gefolgt"
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f).testTag("source.view")) {
                listOf("gefolgt" to "Gefolgt", "alle" to "Ganz Oasis").forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = selection == value,
                        onClick = { if (selection != value) onSelectView(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = OasisColors.Night,
                            activeContentColor = OasisColors.OnNight,
                            inactiveContainerColor = OasisColors.Paper,
                            inactiveContentColor = OasisColors.Night,
                            activeBorderColor = OasisColors.Night,
                            inactiveBorderColor = OasisColors.DuneLine
                        ),
                        icon = {}
                    ) { Text(label, style = OasisType.SmallBold) }
                }
            }
            OasisButton(
                "Durchblättern",
                { navigator.open(NetworkRoute.Swipe) },
                kind = OasisButtonKind.Accent,
                fullWidth = false,
                modifier = Modifier.testTag("source.swipe")
            )
        }
    }
    block("compose") {
        val navigator = LocalNavigator.current
        if (context?.canPost == false) {
            Panel(tone = PanelTone.Dune, spacing = 6) {
                Text("Dein Konto ist gerade nur zum Lesen. Du kannst alles lesen, herunterladen und löschen.", style = OasisType.Body)
                UnderlinedLink("Mitglied werden", { navigator.open(MembershipRoute.Overview) })
            }
        } else {
            ComposeEntry(
                "Was möchtest du teilen?",
                Modifier.testTag("source.composeEntry"),
                accessibilityLabel = "Dein Tropfen: Was möchtest du teilen?"
            ) {
                navigator.present(NetworkRoute.Compose())
            }
        }
    }
    if (context?.followsAnyone == false) {
        block("steps") {
            Panel {
                SectionTitle("Nächste Schritte")
                StepLink("Entdecken, wer schon da ist", NetworkRoute.Discover)
                StepLink("Einer Oase folgen", NetworkRoute.Oases)
                StepLink("Profil vervollständigen", NetworkRoute.EditProfile)
                StepLink("Menschen einladen", NetworkRoute.Invitations)
            }
        }
    }
    feedSection(
        state,
        handlers,
        dimmed = state.isReloading,
        empty = if (showAll) {
            "Noch hat niemand etwas geteilt. Schreib den ersten Tropfen!"
        } else {
            "Noch keine Tropfen. Folge Menschen oder Oasen, dann erscheinen ihre Tropfen hier – oder schau unter „Ganz Oasis“, was alle teilen."
        }
    )
    if (state.reachedEnd || state.posts.isEmpty()) sideBlocks(context)
}

private fun LazyListScope.sideBlocks(context: SourceContext?) {
    context?.week?.let { week ->
        block("side.week") {
            val navigator = LocalNavigator.current
            Panel(tone = PanelTone.Night, spacing = 16) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow("Nur für dich", onNight = true)
                    SectionTitle("Deine Woche", onNight = true)
                }
                Row(verticalAlignment = Alignment.Top) {
                    WeekNumber(week.reactions, "Reaktionen", Modifier.weight(1f))
                    WeekNumber(week.followers, "neu dabei", Modifier.weight(1f))
                    WeekNumber(week.karawane, "Karawane", Modifier.weight(1f))
                }
                UnderlinedLink("Alle deine Zahlen", { navigator.open(NetworkRoute.Statistics) }, color = OasisColors.OnNight2)
            }
        }
    }
    context?.oases?.let { oases ->
        block("side.oases") {
            SideCard("Deine Oasen") {
                if (oases.isEmpty()) Text("Du folgst noch keiner Oase.")
                oases.forEach { OasisRow(it) }
                TextLink("Alle Oasen", NetworkRoute.Oases)
            }
        }
    }
    context?.openVote?.let { vote ->
        block("side.vote") {
            val navigator = LocalNavigator.current
            Panel {
                Pill("Abstimmung läuft", tone = PillTone.Spring)
                Text(vote.title, style = OasisType.CardTitle, color = OasisColors.Night)
                Text("endet am ${dayMonth(vote.endsAt)}", style = OasisType.Small, color = OasisColors.Muted)
                OasisButton("Mitbestimmen", { navigator.open(AccountRoute.Votes) }, fullWidth = false)
            }
        }
    }
    block("side.invite") {
        val navigator = LocalNavigator.current
        SideCard("Gemeinsam ist schöner", tone = PanelTone.Dune) {
            Text("Lade Menschen ein, mit denen du gern redest. Ihr folgt euch dann automatisch.")
            OasisButton("Einladen", { navigator.open(NetworkRoute.Invitations) }, kind = OasisButtonKind.Secondary, fullWidth = false)
        }
    }
}

@Composable
private fun WeekNumber(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clearAndSetSemantics { contentDescription = "$value $label" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$value", style = OasisType.figure(), color = OasisColors.OnNight)
        Text(label, style = OasisType.Small, color = OasisColors.OnNightMuted)
    }
}

@Composable
private fun StepLink(title: String, route: AppRoute) {
    val navigator = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).clickable(role = Role.Button) { navigator.open(route) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.ArrowCircleRight, contentDescription = null, tint = OasisColors.Night)
        Text(title, style = OasisType.Body.copy(textDecoration = TextDecoration.Underline), color = OasisColors.Night)
    }
}

/** "3. November". */
fun dayMonth(instant: Instant): String = GermanTime.format(instant, "d. MMMM")

/** The entry to the composer at the top of the Quelle and an oasis: a field-like button that opens the composer sheet. */
@Composable
fun ComposeEntry(prompt: String, modifier: Modifier = Modifier, accessibilityLabel: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(OasisRadius.M.dp)
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .background(OasisColors.Paper, shape)
            .border(1.dp, OasisColors.PaperLine, shape)
            .pressable(onClick)
            .clearAndSetSemantics {
                contentDescription = accessibilityLabel ?: prompt
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(prompt, style = OasisType.Body, color = OasisColors.Muted, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.EditNote, contentDescription = null, tint = OasisColors.Night)
    }
}
