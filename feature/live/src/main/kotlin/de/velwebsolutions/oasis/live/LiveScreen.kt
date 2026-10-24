package de.velwebsolutions.oasis.live

import androidx.compose.runtime.Composable
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.navigation.LiveRoute

/** Entry point of the Live module: the screen for each [LiveRoute] (mapped in the app). */
@Composable
fun LiveScreen(route: LiveRoute) {
    OasisScreen(title = "Hinweise") { LoadingView() }
}

/** The bell in the header with the number of unread Hinweise. */
@Composable
fun HintsButton() = Unit

/** The in-app notice about a new chat message ("Neue Nachricht von …"), above the tab bar. */
@Composable
fun ChatToastOverlay() = Unit
