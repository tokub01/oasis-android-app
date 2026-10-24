package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.navigation.NetworkRoute

/** Entry point of the Network module: the screen for each [NetworkRoute] (mapped in the app). */
@Composable
fun NetworkScreen(route: NetworkRoute) {
    OasisScreen(title = route.toString()) { LoadingView() }
}
