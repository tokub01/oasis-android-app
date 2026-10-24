package de.velwebsolutions.oasis.app.ui

import androidx.compose.runtime.Composable
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.chat.ChatScreen
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.live.LiveScreen
import de.velwebsolutions.oasis.membership.MembershipScreen
import de.velwebsolutions.oasis.network.NetworkScreen
import de.velwebsolutions.oasis.staff.StaffScreen

/** Maps every route to the screen of the module that owns it. */
@Composable
fun AppDestination(route: AppRoute) {
    when (route) {
        is NetworkRoute -> NetworkScreen(route)

        is ChatRoute -> ChatScreen(route)

        is AccountRoute -> AccountScreen(route)

        is LiveRoute -> LiveScreen(route)

        is MembershipRoute -> MembershipScreen(route)

        is StaffRoute -> StaffScreen(route)

        // Websites open in a Custom Tab (Navigator.openWeb) and are never pushed.
        is WebRoute -> Unit
    }
}
