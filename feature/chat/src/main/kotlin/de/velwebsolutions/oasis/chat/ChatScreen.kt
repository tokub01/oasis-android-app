package de.velwebsolutions.oasis.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.chat.core.BiometricPrompter
import de.velwebsolutions.oasis.chat.ui.ChatGate
import de.velwebsolutions.oasis.chat.ui.ChatSettingsScreen
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsScreen
import de.velwebsolutions.oasis.chat.ui.ConversationListScreen
import de.velwebsolutions.oasis.chat.ui.ConversationScreen
import de.velwebsolutions.oasis.chat.ui.NewConversationScreen
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Entry point of the Chat module: the screen for each [ChatRoute] (mapped in the app). Web: /app/nachrichten. Every chat
 * screen sits behind the chat gate, which shows setup or unlock until the keys are open in memory.
 */
@Composable
fun ChatScreen(route: ChatRoute) {
    val root: ChatRootViewModel = hiltViewModel()
    val me by root.me.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.let(root.prompter::attach)
        onDispose { activity?.let(root.prompter::detach) }
    }
    val member = me
    if (member == null) {
        OasisScreen(title = "Nachrichten") { LoadingView() }
        return
    }
    key(member.id) {
        ChatGate {
            when (route) {
                ChatRoute.Conversations -> ConversationListScreen()
                is ChatRoute.Conversation -> ConversationScreen(route.id)
                ChatRoute.NewConversation -> NewConversationScreen(username = null)
                is ChatRoute.NewConversationWith -> NewConversationScreen(username = route.username)
                is ChatRoute.Details -> ConversationDetailsScreen(route.conversationId)
                ChatRoute.Settings, ChatRoute.Devices -> ChatSettingsScreen()
            }
        }
    }
}

/** Who is signed in (the chat is bound to that member) and the biometric prompt of the visible activity. */
@HiltViewModel
class ChatRootViewModel @Inject constructor(session: Session, val prompter: BiometricPrompter) : ViewModel() {
    val me: StateFlow<Me?> = session.state
        .map { (it as? SessionState.SignedIn)?.me }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.me)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
