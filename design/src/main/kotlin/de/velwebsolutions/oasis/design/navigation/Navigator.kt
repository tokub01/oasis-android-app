package de.velwebsolutions.oasis.design.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf

/** The main sections of the bottom bar – the same five as the web's app navigation (layouts/app.blade.php). */
enum class MainTab(val title: String) {
    Source("Quelle"),
    Discover("Entdecken"),
    Oases("Oasen"),
    Chats("Chats"),
    Profile("Profil")
}

/**
 * Navigation state: selected tab, one stack per tab and an optional sheet with its own stack (Navigator on iOS).
 * Screens push routes onto the current tab (or onto the sheet while one is open); deep links (push notifications, app
 * links) switch to the right tab first.
 */
@Stable
class Navigator {
    var selectedTab by mutableStateOf(MainTab.Source)

    /** The pushed screens of each tab above its root screen. */
    private val stacks: Map<MainTab, SnapshotStateList<AppRoute>> = MainTab.entries.associateWith { mutableStateListOf() }

    /** A route presented as a sheet (compose, report, new chat …) and the screens pushed inside it. */
    val sheetStack: SnapshotStateList<AppRoute> = mutableStateListOf()

    val sheet: AppRoute? get() = sheetStack.firstOrNull()

    /** False while the sheet's screen must not be swiped away (a composer with a draft asks first). */
    var sheetDismissible by mutableStateOf(true)

    /** Opened when a route is a website ([WebRoute]); set by the app (Custom Tabs). */
    var openWeb: (String) -> Unit = {}

    /** The stack of a tab (without its root screen). */
    fun stack(tab: MainTab): SnapshotStateList<AppRoute> = stacks.getValue(tab)

    /** Push a route onto the current tab, or onto the sheet while one is open. */
    fun open(route: AppRoute) {
        if (route is WebRoute) return openWeb(route.url)
        if (sheetStack.isNotEmpty()) sheetStack.add(route) else stack(selectedTab).add(route)
    }

    /** Present a route as a sheet. */
    fun present(route: AppRoute) {
        sheetStack.clear()
        sheetStack.add(route)
    }

    fun dismissSheet() {
        sheetStack.clear()
        sheetDismissible = true
    }

    /** Go back one screen: inside the sheet first, otherwise on the current tab. Returns false at a root. */
    fun back(): Boolean {
        if (sheetStack.isNotEmpty()) {
            sheetStack.removeAt(sheetStack.lastIndex)
            return true
        }
        val stack = stack(selectedTab)
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Back to the root of a tab (re-selecting a tab does this too). */
    fun popToRoot(tab: MainTab = selectedTab) = stack(tab).clear()

    /** Select a tab; selecting the active tab again pops it to its root, like the web's logo link. */
    fun select(tab: MainTab) {
        if (tab == selectedTab) popToRoot(tab)
        selectedTab = tab
    }

    /** Open a route from outside (push notification, link): pick the matching tab, reset it and push the route. */
    fun handle(route: AppRoute) {
        if (route is WebRoute) return openWeb(route.url)
        dismissSheet()
        val tab = tab(route)
        selectedTab = tab
        val stack = stack(tab)
        stack.clear()
        if (rootRoute(tab) != route) stack.add(route)
    }

    /** Open a URL (app link or push payload `url`); returns false if it is not an app screen. */
    fun handle(url: String): Boolean {
        val route = DeepLink.route(url) ?: return false
        handle(route)
        return true
    }

    companion object {
        /** The root screen of a tab; the profile tab shows the member's own profile. */
        fun rootRoute(tab: MainTab): AppRoute? = when (tab) {
            MainTab.Source -> NetworkRoute.Source
            MainTab.Discover -> NetworkRoute.Discover
            MainTab.Oases -> NetworkRoute.Oases
            MainTab.Chats -> ChatRoute.Conversations
            MainTab.Profile -> null
        }

        /** The tab a deep-linked route belongs to. */
        fun tab(route: AppRoute): MainTab = when (route) {
            is ChatRoute -> MainTab.Chats

            NetworkRoute.Discover, NetworkRoute.Swipe, is NetworkRoute.Search, is NetworkRoute.Tag -> MainTab.Discover

            NetworkRoute.Oases, is NetworkRoute.Oasis, NetworkRoute.CreateOasis, is NetworkRoute.EditOasis,
            is NetworkRoute.CloseOasis, is NetworkRoute.OasisStatistics, is NetworkRoute.Invitation
            -> MainTab.Oases

            NetworkRoute.Source, is NetworkRoute.Post, is NetworkRoute.EditPost, is NetworkRoute.CarryPost, is NetworkRoute.Compose ->
                MainTab.Source

            else -> MainTab.Profile
        }
    }
}

/** The navigator of the running app. */
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator provided") }
