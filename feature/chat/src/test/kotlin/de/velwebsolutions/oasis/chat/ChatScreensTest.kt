package de.velwebsolutions.oasis.chat

import android.os.Looper
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.core.ChatKeyring
import de.velwebsolutions.oasis.chat.ui.ChatAccessViewModel
import de.velwebsolutions.oasis.chat.ui.ChatPhase
import de.velwebsolutions.oasis.chat.ui.ChatSetupContent
import de.velwebsolutions.oasis.chat.ui.ChatUnlockContent
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationListContent
import de.velwebsolutions.oasis.chat.ui.ConversationListState
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.core.realtime.NoRealtime
import de.velwebsolutions.oasis.design.theme.OasisTheme
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The core flows on screen (Robolectric): setting up and unlocking the chat, the conversation list, and writing a
 * message – with the real view models, crypto and the in-memory server where the flow needs them (iOS: ChatUITests).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatScreensTest {
    @get:Rule val compose = createComposeRule()

    private val support = ChatTestSupport()
    private val passphrase = "Palme Regen Kompass Tee"

    /**
     * Waits for work on other threads (the fake server, Argon2id) while running the main looper, where the view models
     * continue.
     */
    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Condition not met within $timeoutMillis ms" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasContentDescription(label))

    /** Sets up a member's chat away from the main thread (Argon2id), as if done on another day. */
    private fun setUpMember(userId: Int) = support.context(userId).also { context ->
        runBlocking(Dispatchers.Default) { ChatKeyring(context).setUp(passphrase) }
    }

    @Test
    fun settingUpWithAPassphraseOpensTheChat() {
        val model = ChatAccessViewModel(support.context(1))
        compose.setContent {
            OasisTheme {
                val state by model.state.collectAsState()
                LaunchedEffect(Unit) { model.load() }
                ChatSetupContent(
                    state,
                    model::setPassphrase,
                    model::setConfirmation,
                    model::setUp,
                    model::setUpWithDevice,
                    model::cancelReset
                )
            }
        }
        waitFor(10_000) { model.state.value.phase == ChatPhase.Setup }
        compose.onNodeWithText("Chat einrichten").assertIsDisplayed()

        field("Passphrase").performTextInput("zu kurz")
        field("Passphrase wiederholen").performTextInput("zu kurz")
        compose.onNodeWithTag("chat.setup.submit").performScrollTo().performClick()
        waitFor(5_000) { model.state.value.formError != null }
        compose.onNodeWithText("Die Passphrase braucht mindestens 12 Zeichen.").performScrollTo().assertIsDisplayed()

        model.setPassphrase("")
        model.setConfirmation("")
        field("Passphrase").performTextInput(passphrase)
        field("Passphrase wiederholen").performTextInput(passphrase)
        compose.onNodeWithTag("chat.setup.submit").performScrollTo().performClick()
        waitFor(30_000) { model.state.value.phase == ChatPhase.Open }
        assertEquals("Deine Chat-Schlüssel sind eingerichtet.", model.context.vault.announcement.value)
    }

    @Test
    fun unlockingNeedsTheRightPassphrase() {
        val context = setUpMember(1)
        context.vault.lock()
        val model = ChatAccessViewModel(context)
        compose.setContent {
            OasisTheme {
                val state by model.state.collectAsState()
                LaunchedEffect(Unit) { model.load() }
                ChatUnlockContent(
                    state,
                    model::setPassphrase,
                    model::unlock,
                    { model.unlockWithDevice() },
                    model::startReset,
                    model::requestLink,
                    model::cancelLink
                )
            }
        }
        waitFor(10_000) { model.state.value.phase == ChatPhase.Unlock }
        compose.onNodeWithText("Nachrichten entsperren").assertIsDisplayed()
        compose.onNodeWithTag("chat.link.request").assertIsDisplayed()

        field("Chat-Passphrase").performTextInput("falsch falsch falsch")
        compose.onNodeWithTag("chat.unlock.submit").performScrollTo().performClick()
        waitFor(30_000) { model.state.value.formError != null }
        compose.onNodeWithText("Die Passphrase stimmt nicht.").performScrollTo().assertIsDisplayed()

        field("Chat-Passphrase").performTextInput(passphrase)
        compose.onNodeWithTag("chat.unlock.submit").performScrollTo().performClick()
        waitFor(30_000) { model.state.value.phase == ChatPhase.Open }
    }

    @Test
    fun theListShowsChatsRequestsAndOpensAChat() {
        fun chat(id: String, title: String?, request: Boolean = false, unread: Boolean = false) = ChatConversation(
            id = id,
            type = if (title == null) "direct" else "group",
            title = title,
            createdBy = "2",
            lifetimeDays = 7,
            keyVersion = 1,
            rotationRequired = false,
            members = listOf(
                ConversationMember("1", "mira", "Mira Sol", "member"),
                ConversationMember("2", "jonas", "Jonas Weber", "owner")
            ),
            serverTime = "2026-10-22T12:00:00Z",
            unread = unread,
            request = request
        )
        var opened: ChatConversation? = null
        var newChats = 0
        val state = ConversationListState(
            conversations = listOf(chat("3", null, unread = true), chat("4", "Wandergruppe")),
            requests = listOf(chat("5", "Unbekannte Gruppe", request = true)),
            loaded = true
        )
        compose.setContent {
            OasisTheme {
                ConversationListContent(
                    state = state,
                    canParticipate = true,
                    title = { it.displayTitle("1") },
                    meta = {
                        if (it.isGroup) "Gruppe · ${it.members.size} Menschen · löscht nach 7 Tagen" else "Zu zweit · löscht nach 7 Tagen"
                    },
                    onRefresh = {},
                    onLoadMore = {},
                    onOpen = { opened = it },
                    onNew = { newChats++ },
                    onLock = {},
                    onSettings = {},
                    onApprove = {},
                    onReject = {}
                )
            }
        }
        compose.onNodeWithText("Jonas Weber").assertIsDisplayed()
        compose.onNodeWithText("Neu").assertIsDisplayed()
        compose.onNodeWithTag("chat.requests").assertIsDisplayed()
        compose.onNodeWithText("Unbekannte Gruppe").assertIsDisplayed()
        compose.onNodeWithTag("chat.new").performClick()
        assertEquals(1, newChats)
        compose.onNodeWithTag("chat.list").performScrollToNode(hasText("Wandergruppe"))
        compose.onNodeWithText("Wandergruppe").performClick()
        assertEquals("4", opened?.id)
    }

    @Test
    fun writingAMessageSendsItEncryptedAndShowsIt() {
        val mira = setUpMember(1)
        setUpMember(2)
        val id = runBlocking(Dispatchers.Default) { mira.repository.createConversation("direct", null, listOf("2"), 7).id }
        val model = ConversationViewModel(id, mira, NoRealtime()).apply {
            pollInterval = Duration.INFINITE
            sweepInterval = Duration.INFINITE
        }
        compose.setContent {
            OasisTheme {
                val state by model.state.collectAsState()
                LaunchedEffect(Unit) { model.open() }
                ConversationContent(
                    state = state,
                    myId = "1",
                    senderName = model::senderName,
                    onLoadOlder = model::loadOlder,
                    onDraftChange = model::setDraft,
                    onSend = model::send,
                    onReport = {},
                    onAccept = model::acceptRequest,
                    onDecline = {},
                    onBlockFlagged = {},
                    onDismissFlagged = model::dismissFlagged,
                    onNoticeSpoken = model::noticeSpoken
                )
            }
        }
        waitFor(20_000) { model.state.value.canSend }
        compose.onNodeWithTag("chat.messages.empty").assertIsDisplayed()
        compose.onNodeWithTag("chat.composer.send").assertIsNotEnabled()

        field("Nachricht").performTextInput("Hallo Jonas 🌴")
        compose.onNodeWithTag("chat.composer.send").assertIsEnabled().performClick()
        waitFor(20_000) { model.state.value.timeline.items.isNotEmpty() }
        compose.onNodeWithText("Hallo Jonas 🌴").assertIsDisplayed()
        compose.onNodeWithText("Du").assertIsDisplayed()
        assertEquals("", model.state.value.draft)
        val stored = support.server.messages.getValue(id.toInt()).single().second
        assertEquals(false, stored.toString().contains("Hallo"))
        model.close()
    }
}
