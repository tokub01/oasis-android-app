package de.velwebsolutions.oasis.membership

import androidx.compose.runtime.Composable
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.navigation.MembershipRoute

/** Entry point of the Membership module: the screen for each [MembershipRoute] (mapped in the app). */
@Composable
fun MembershipScreen(route: MembershipRoute) {
    OasisScreen(title = "Mitgliedschaft") { LoadingView() }
}

/** The trial countdown under the bar of every screen in a tab; gone for members and once the trial is over. */
@Composable
fun TrialBanner(me: Me) = Unit
