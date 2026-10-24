package de.velwebsolutions.oasis.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.design.components.CountBadge
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.live.ui.HintSettingsScreen
import de.velwebsolutions.oasis.live.ui.HintsInboxScreen
import javax.inject.Inject

/** Entry point of the Live module: the screen for each [LiveRoute] (mapped in the app). */
@Composable
fun LiveScreen(route: LiveRoute) {
    when (route) {
        LiveRoute.Inbox -> HintsInboxScreen()
        LiveRoute.Settings -> HintSettingsScreen()
    }
}

/** Hands the app shell's live state (bell, chat notice) to the composables of the header and the tab view. */
@HiltViewModel
internal class LiveShellViewModel @Inject constructor(val live: LiveCoordinator) : ViewModel()

/** The bell in the header with the number of unread Hinweise. */
@Composable
fun HintsButton() {
    val navigator = LocalNavigator.current
    val live = hiltViewModel<LiveShellViewModel>().live
    val unread by live.hints.collectAsStateWithLifecycle()
    HintsBell(unread) { navigator.open(LiveRoute.Inbox) }
}

/** The bell itself: rings once when a new hint arrives, not when hints are read (none during a pause). */
@Composable
internal fun HintsBell(unread: Int, onClick: () -> Unit) {
    val ring = remember { Animatable(1f) }
    val previous = remember { intArrayOf(unread) }
    LaunchedEffect(unread) {
        if (unread > previous[0]) {
            ring.snapTo(1.25f)
            ring.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
        previous[0] = unread
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .testTag("hints.bell")
            .semantics { contentDescription = if (unread > 0) "Hinweise, $unread neu" else "Hinweise" }
    ) {
        Box {
            Icon(
                Icons.Outlined.Notifications,
                contentDescription = null,
                tint = OasisColors.Night,
                modifier = Modifier.graphicsLayer {
                    scaleX = ring.value
                    scaleY = ring.value
                }
            )
            CountBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-10).dp).clearAndSetSemantics {})
        }
    }
}

/** The in-app notice about a new chat message ("Neue Nachricht von …"), above the tab bar. */
@Composable
fun ChatToastOverlay() {
    val navigator = LocalNavigator.current
    val live = hiltViewModel<LiveShellViewModel>().live
    val toast by live.counts.toast.collectAsStateWithLifecycle()
    ChatToastContent(
        toast = toast,
        onOpen = { current ->
            live.counts.dismissToast()
            navigator.handle(current.conversationId?.let { ChatRoute.Conversation(it) } ?: ChatRoute.Conversations)
        },
        onClose = live.counts::dismissToast
    )
}

/**
 * The notice for 15 seconds, like the web's chat toast: "Öffnen" goes to the chat, the cross closes it. Read out like
 * the web's role="status".
 */
@Composable
internal fun ChatToastContent(toast: ChatToast?, onOpen: (ChatToast) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            val current = toast ?: return@AnimatedVisibility
            Row(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .background(OasisColors.Night, RoundedCornerShape(OasisRadius.S.dp))
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("chatToast"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Forum, contentDescription = null, tint = OasisColors.SunOnNight)
                Text(current.text, style = OasisType.BodyBold, color = OasisColors.OnNight, modifier = Modifier.weight(1f))
                Text(
                    "Öffnen",
                    style = OasisType.SmallBold,
                    color = OasisColors.OnNight,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .border(1.dp, OasisColors.OnNight2, CircleShape)
                        .clickable(role = Role.Button) { onOpen(current) }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .testTag("chatToast.open")
                )
                IconButton(onClick = onClose, modifier = Modifier.size(44.dp).testTag("chatToast.close")) {
                    Icon(Icons.Outlined.Close, contentDescription = "Schließen", tint = OasisColors.OnNight2)
                }
            }
        }
    }
}
