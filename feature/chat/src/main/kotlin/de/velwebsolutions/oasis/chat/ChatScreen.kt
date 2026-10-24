package de.velwebsolutions.oasis.chat

import androidx.compose.runtime.Composable
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.navigation.ChatRoute

/** Entry point of the Chat module: the screen for each [ChatRoute] (mapped in the app). Web: /app/nachrichten. */
@Composable
fun ChatScreen(route: ChatRoute) {
    OasisScreen(title = "Chats") { LoadingView() }
}
