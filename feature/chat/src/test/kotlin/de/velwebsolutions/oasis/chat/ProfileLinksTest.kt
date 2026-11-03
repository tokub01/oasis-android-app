package de.velwebsolutions.oasis.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.velwebsolutions.oasis.chat.api.ChatAvatar
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.ui.ChatHeaderBar
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsContent
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsState
import de.velwebsolutions.oasis.chat.ui.ConversationState
import de.velwebsolutions.oasis.chat.ui.MemberRow
import de.velwebsolutions.oasis.chat.ui.ProfileLink
import de.velwebsolutions.oasis.chat.ui.directProfile
import de.velwebsolutions.oasis.chat.ui.opensProfile
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Avatars and member rows in the chat open the person's profile (the same on the web and iOS); the „?“ opens nothing. */
@RunWith(RobolectricTestRunner::class)
class ProfileLinksTest {
    @get:Rule val compose = createComposeRule()

    private val navigator = Navigator()
    private val top get() = navigator.stack(navigator.selectedTab).lastOrNull()

    private val mira = ConversationMember("1", "mira", "Mira")
    private val jonas = ConversationMember("2", "jonas", "Jonas Berg")
    private val aylin = ConversationMember("3", "aylin", null)

    private fun chat(type: String = "direct", members: List<ConversationMember> = listOf(mira, jonas)) =
        ChatConversation("7", type, null, "1", 7, 1, false, members, "")

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun whoseProfileEachPlaceOpens() {
        assertEquals(ProfileLink("jonas", "Jonas Berg"), ProfileLink.of(jonas))
        assertEquals("without a display name the username", ProfileLink("aylin", "@aylin"), ProfileLink.of(aylin))
        assertEquals("Profil von Jonas Berg öffnen", ProfileLink.of(jonas)?.description)
        assertEquals(NetworkRoute.Profile("jonas"), ProfileLink.of(jonas)?.route)
        assertNull("no username, no profile", ProfileLink.of(ConversationMember("4", "", "Gelöscht")))
        assertNull(ProfileLink.of(null))

        assertEquals(ProfileLink.of(jonas), chat().directProfile("1"))
        assertNull("a group heads with two avatars", chat("group", listOf(mira, jonas, aylin)).directProfile("1"))
        assertNull("nobody else left", chat(members = listOf(mira)).directProfile("1"))
        assertSame(
            "no navigator: nothing to open",
            androidx.compose.ui.Modifier,
            androidx.compose.ui.Modifier.opensProfile(ProfileLink.of(jonas), null)
        )
    }

    @Test
    fun theAvatarsInAChatOpenTheProfileAndTheUnknownOneDoesNot() {
        val envelope = MessageEnvelope(1, "7", 1, "2", "n", "c", "s1", "2100-01-01T00:00:00Z")
        fun item(signature: String, sender: String) = ChatMessageItem(
            signature,
            envelope.copy(signature = signature),
            sender,
            null,
            ChatMessageItem.State.Readable,
            "Hallo",
            MessageContent("Hallo")
        )
        val state = ConversationState(conversation = chat(), timeline = ChatTimeline(listOf(item("a", "2"), item("b", "99"))))
        val members = listOf(mira, jonas)
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    androidx.compose.foundation.layout.Column {
                        ChatHeaderBar(state.conversation, "1", "Jonas", "", onBack = null, onDetails = {})
                        ConversationContent(
                            state = state,
                            myId = "1",
                            senderName = { "Jemand" },
                            onLoadOlder = {},
                            onDraftChange = {},
                            onSend = {},
                            onReport = {},
                            onAccept = {},
                            onDecline = {},
                            onBlockFlagged = {},
                            onDismissFlagged = {},
                            onNoticeSpoken = {},
                            attachments = attachmentActions(mutableListOf()),
                            senderAvatar = { ChatAvatar.Unknown },
                            senderProfile = { id -> ProfileLink.of(members.firstOrNull { it.userId == id }) }
                        )
                    }
                }
            }
        }
        // The header of a 1:1 chat: the other person.
        compose.onNodeWithTag("chat.conversation.avatar").performClick()
        assertEquals(NetworkRoute.Profile("jonas"), top)
        navigator.back()

        // Beside Jonas' message: his profile, read out as a button.
        // The list is laid out newest first (drawn from the bottom): the unknown sender's message comes first.
        val avatars = compose.onAllNodesWithTag("chat.message.avatar")
        avatars[1].assert(button).performClick()
        assertEquals(NetworkRoute.Profile("jonas"), top)
        // Someone no longer in the chat: the „?“ opens nothing.
        avatars[0].assertHasNoClickAction()
        assertEquals(
            "header and bubble, read out alike",
            2,
            compose.onAllNodes(hasContentDescription("Profil von Jonas Berg öffnen")).fetchSemanticsNodes().size
        )
    }

    @Test
    fun everyMemberRowOpensTheProfileButOneWithoutAUsername() {
        val state = ConversationDetailsState(
            conversation = chat("group", listOf(mira, jonas)),
            members = listOf(
                MemberRow("1", "Mira – du", "mira", true, null, null, false, profile = ProfileLink.of(mira)),
                MemberRow("2", "Jonas Berg", "jonas", false, "12345", null, false, profile = ProfileLink.of(jonas)),
                MemberRow("4", "Gelöscht", "", false, null, null, false)
            )
        )
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    ConversationDetailsContent(state, true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("chat.details.profile.jonas").performScrollTo().assert(button).performClick()
        assertEquals(NetworkRoute.Profile("jonas"), top)
        compose.onNodeWithTag("chat.details.profile.mira").performScrollTo().performClick()
        assertEquals("the own row opens the own profile", NetworkRoute.Profile("mira"), top)
        compose.onNodeWithTag("chat.details.profile.").assertHasNoClickAction()
    }
}
