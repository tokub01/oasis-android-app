package de.velwebsolutions.oasis.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ChatPerson
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute

/**
 * Whose profile an avatar or a member row opens (the same on the web and iOS): the network profile of [username]. The
 * „?“ of someone no longer in the chat, a deleted account and anyone without a username open nothing.
 */
data class ProfileLink(val username: String, val name: String) {
    /** What TalkBack reads. */
    val description: String get() = "Profil von $name öffnen"

    /** The screen it opens, on the current tab's stack, so „Zurück“ returns to the chat. */
    val route: AppRoute get() = NetworkRoute.Profile(username)

    companion object {
        /** The link of a member, or null without a username. */
        fun of(person: ChatPerson?): ProfileLink? = person?.takeIf { it.username.isNotBlank() }?.let { ProfileLink(it.username, it.name) }
    }
}

/** The other person of a 1:1 chat, whose avatar heads it; null in a group or with nobody else left. */
fun ChatConversation.directProfile(myId: String): ProfileLink? = if (isGroup) null else ProfileLink.of(others(myId).singleOrNull())

/** How [link]'s profile opens: on the current tab's stack; null without a link (screens without a navigator). */
@Composable
internal fun profileOpener(link: ProfileLink?): ((AppRoute) -> Unit)? = if (link == null) null else LocalNavigator.current::open

/** Makes an avatar or a member row open [link]'s profile through [open]; without either it stays as it is. */
internal fun Modifier.opensProfile(link: ProfileLink?, open: ((AppRoute) -> Unit)?): Modifier = if (link == null || open == null) {
    this
} else {
    clickable(role = Role.Button) { open(link.route) }.semantics { contentDescription = link.description }
}
