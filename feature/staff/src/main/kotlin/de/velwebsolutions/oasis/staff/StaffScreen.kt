package de.velwebsolutions.oasis.staff

import androidx.compose.runtime.Composable
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.navigation.StaffRoute

/** Entry point of the Staff module: the screen for each [StaffRoute] (mapped in the app). */
@Composable
fun StaffScreen(route: StaffRoute) {
    OasisScreen(title = "Moderation") { LoadingView() }
}
