package de.velwebsolutions.oasis.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.design.components.CountBadge
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.OasisLogo
import de.velwebsolutions.oasis.design.components.ScreenChrome
import de.velwebsolutions.oasis.design.components.StatusToast
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.live.ChatToastOverlay
import de.velwebsolutions.oasis.live.HintsButton
import de.velwebsolutions.oasis.membership.TrialBanner

private val MainTab.icon: ImageVector
    get() = when (this) {
        MainTab.Source -> Icons.Outlined.WaterDrop
        MainTab.Discover -> Icons.Outlined.Explore
        MainTab.Oases -> Icons.Outlined.Groups
        MainTab.Chats -> Icons.Outlined.Forum
        MainTab.Profile -> Icons.Outlined.AccountCircle
    }

/**
 * The five main sections as a bottom bar (Quelle, Entdecken, Oasen, Chats, Profil), each with its own stack, plus the
 * web header's actions on every tab root: search, Hinweise bell and the "Mehr" menu with the account pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabs(me: Me, viewModel: RootViewModel) {
    val navigator = LocalNavigator.current
    val chats by viewModel.live.chats.collectAsStateWithLifecycle()
    val confirmation by viewModel.confirmation.collectAsStateWithLifecycle()

    LaunchedEffect(me.id) { viewModel.tabsAppeared(me.id) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.foreground() }
    LaunchedEffect(navigator.selectedTab) { viewModel.live.setShowingChats(navigator.selectedTab == MainTab.Chats) }

    val header: @Composable (@Composable RowScope.() -> Unit) -> Unit = { actions -> AppHeader(me, viewModel, actions) }
    val banner: @Composable () -> Unit = { TrialBanner(me) }
    val tabStates = rememberSaveableStateHolder()

    Scaffold(
        containerColor = OasisColors.Sand,
        bottomBar = {
            NavigationBar(containerColor = OasisColors.Paper) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = navigator.selectedTab == tab,
                        onClick = { navigator.select(tab) },
                        icon = {
                            BadgedBox(badge = {
                                if (tab == MainTab.Chats &&
                                    chats > 0
                                ) {
                                    Badge(containerColor = OasisColors.Sun) { Text("$chats") }
                                }
                            }) {
                                Icon(tab.icon, contentDescription = null)
                            }
                        },
                        label = { Text(tab.title, style = OasisType.Caption) },
                        modifier = Modifier.testTag("tab.${tab.name.lowercase()}"),
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OasisColors.Night,
                            selectedTextColor = OasisColors.Night,
                            indicatorColor = OasisColors.Dune,
                            unselectedIconColor = OasisColors.Muted,
                            unselectedTextColor = OasisColors.Muted
                        )
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val tab = navigator.selectedTab
            tabStates.SaveableStateProvider(tab.name) {
                val root = Navigator.rootRoute(tab) ?: NetworkRoute.Profile(me.username)
                RouteStack(root, navigator.stack(tab).toList(), onBack = {
                    navigator.back()
                }, rootChrome = ScreenChrome(rootHeader = header, banner = banner), banner = banner)
            }
            StatusToast(confirmation, null, onDismissMessage = viewModel::clearConfirmation, onDismissError = {})
            ChatToastOverlay()
        }
    }

    val sheet = navigator.sheet
    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = { navigator.dismissSheet() },
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || navigator.sheetDismissible },
            ),
            containerColor = OasisColors.Sand,
            dragHandle = null
        ) {
            Box(Modifier.fillMaxWidth().fillMaxSize(0.96f)) {
                RouteStack(
                    root = sheet,
                    stack = navigator.sheetStack.drop(1),
                    onBack = { navigator.back() },
                    rootChrome = ScreenChrome(onClose = { navigator.dismissSheet() }),
                    banner = null
                )
            }
        }
    }
}

/** A screen with nothing else around it (lock screen, welcome tour); pushed screens go onto the source tab's stack. */
@Composable
fun StandaloneStack(root: AppRoute) {
    val navigator = LocalNavigator.current
    RouteStack(root, navigator.stack(navigator.selectedTab).toList(), onBack = {
        navigator.back()
    }, rootChrome = ScreenChrome(), banner = null)
}

/** One entry of a displayed stack; the position keeps entries unique when a route is pushed twice. */
private data class StackEntry(val index: Int, val route: AppRoute)

/** A root screen with the routes pushed above it, each in its own saved state and view-model store. */
@Composable
private fun RouteStack(
    root: AppRoute,
    stack: List<AppRoute>,
    onBack: () -> Unit,
    rootChrome: ScreenChrome,
    banner: (@Composable () -> Unit)?
) {
    val entries = (listOf(root) + stack).mapIndexed(::StackEntry)
    NavDisplay(
        backStack = entries,
        onBack = { onBack() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = { entry ->
            NavEntry(entry, contentKey = "${entry.index}:${entry.route}") {
                val chrome = if (entry.index == 0) rootChrome else ScreenChrome(onBack = onBack, banner = banner)
                CompositionLocalProvider(LocalScreenChrome provides chrome) {
                    AppDestination(entry.route)
                }
            }
        }
    )
}

/** The web's phone header: logo on the left; the screen's actions, search, Hinweise bell and "Mehr" on the right. */
@Composable
private fun AppHeader(me: Me, viewModel: RootViewModel, actions: @Composable RowScope.() -> Unit) {
    val navigator = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(OasisRadius.CHIP.dp))
                .clickable {
                    navigator.popToRoot(MainTab.Source)
                    navigator.selectedTab = MainTab.Source
                }
                .semantics { contentDescription = "Oasis – zur Quelle" }
        ) {
            OasisLogo(size = 22.dp)
        }
        Spacer(Modifier.weight(1f))
        actions()
        IconButton(onClick = { navigator.open(NetworkRoute.Search()) }) {
            Icon(Icons.Outlined.Search, contentDescription = "Suchen", tint = OasisColors.Night)
        }
        HintsButton()
        MoreMenu(me, viewModel)
    }
}

/**
 * The web's "Mehr" disclosure on phones (layouts/partials/app-navigation.blade.php): a bordered pill with a dot for
 * unread hints, opening a paper panel with the account pages, moderation for staff, and sign-out.
 */
@Composable
private fun MoreMenu(me: Me, viewModel: RootViewModel) {
    val navigator = LocalNavigator.current
    val hints by viewModel.live.hints.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .padding(start = 4.dp)
                .heightIn(min = 44.dp)
                .clip(CircleShape)
                .border(1.dp, OasisColors.DuneLine, CircleShape)
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("more.menu")
                .semantics { contentDescription = "Mehr" },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mehr", style = OasisType.BodyBold, color = OasisColors.Night)
            if (hints > 0) Box(Modifier.size(10.dp).clip(CircleShape).border(5.dp, OasisColors.Sun, CircleShape))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = OasisColors.Paper,
            shape = RoundedCornerShape(OasisRadius.TILE.dp)
        ) {
            Column(Modifier.width(240.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                fun go(route: AppRoute) {
                    open = false
                    navigator.open(route)
                }
                MorePages.forEach { (title, route) -> MoreRow(title, badge = if (route == LiveRoute.Inbox) hints else 0) { go(route) } }
                if (me.isStaff) {
                    MoreRow("Moderation") { go(StaffRoute.Moderation) }
                    if (me.isAdmin) MoreRow("Verwaltung") { go(StaffRoute.AdminDashboard) }
                }
                MoreRow("Abmelden") {
                    open = false
                    viewModel.signOut()
                }
            }
        }
    }
}

/** The web's `$more` list in the same order, without icons. */
private val MorePages: List<Pair<String, AppRoute>> = listOf(
    "Hinweise" to LiveRoute.Inbox,
    "Freund:innen" to NetworkRoute.Friends,
    "Gemerkt" to NetworkRoute.Bookmarks,
    "Einstellungen" to AccountRoute.Settings,
    "Mitgliedschaft" to MembershipRoute.Overview,
    "Deine Zahlen" to NetworkRoute.Statistics,
    "Menschen einladen" to NetworkRoute.Invitations,
    "Privatsphäre" to AccountRoute.Privacy,
    "Blockiert & stumm" to AccountRoute.Safety,
    "Abstimmungen" to AccountRoute.Votes,
    "Begriffe erklärt" to AccountRoute.Glossary
)

@Composable
private fun MoreRow(title: String, badge: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(OasisRadius.CHIP.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = OasisType.Body, color = OasisColors.Night, modifier = Modifier.weight(1f))
        CountBadge(badge)
    }
}
