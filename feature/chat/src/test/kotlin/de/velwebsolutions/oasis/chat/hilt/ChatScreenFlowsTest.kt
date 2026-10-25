package de.velwebsolutions.oasis.chat.hilt

import android.view.ContextThemeWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.chat.ChatScreen
import de.velwebsolutions.oasis.chat.ChatTestSupport
import de.velwebsolutions.oasis.chat.FakeRealtime
import de.velwebsolutions.oasis.chat.TEST_PASSPHRASE
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.core.MemoryDeviceSecretStore
import de.velwebsolutions.oasis.chat.member
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The screens with their real view models and the steps that leave them (Robolectric, Hilt test graph): the gate's
 * failure and offer, opening a chat, leaving or blocking, removing members, settings and starting a chat.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h3000dp")
class ChatScreenFlowsTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val support = ChatTestSupport()
    private val server get() = support.server
    private val navigator = Navigator()
    private val realtime = FakeRealtime(connected = true)
    private var shown: ActivityController<ChatTestActivity>? = null

    @Before
    fun graph() {
        ChatTestGraph.support = support
        ChatTestGraph.realtime = realtime
        hilt.inject()
    }

    private fun signIn(context: ChatContext) {
        ChatTestGraph.context = context
        ChatTestGraph.session.update(context.me)
    }

    private fun show(route: ChatRoute, wrapped: Boolean = false) {
        shown?.pause()?.stop()?.destroy()
        val controller = Robolectric.buildActivity(ChatTestActivity::class.java).setup()
        shown = controller
        val activity = controller.get()
        val context = if (wrapped) ContextThemeWrapper(activity, 0) else activity
        activity.setContentView(
            ComposeView(context).apply {
                setContent {
                    OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator) { ChatScreen(route) } }
                }
            }
        )
    }

    private fun waitFor(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 20_000)

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 20_000)

    private fun SemanticsNodeInteraction.tap() {
        runCatching { performScrollTo() }
        performClick()
    }

    private fun tap(text: String) = compose.onNodeWithText(text).tap()

    /** The last of several nodes with [text] (a dialog's button above a page's). */
    private fun tapLast(text: String) {
        val nodes = compose.onAllNodes(hasText(text))
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
    }

    private fun stack() = navigator.stack(navigator.selectedTab).toList()

    /** Lets the main looper and the fake server work until [done] holds (the answers come on OkHttp's threads). */
    private fun settle(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Condition not met; navigation: ${stack()}" }
            compose.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun failing(key: String, status: Int = 500) {
        server.failures[key] = status to """{"message":"kaputt"}"""
    }

    /** Mira's chat with Jonas, its key distributed, and the messages Mira wrote into it. */
    private fun chatWithMessages(vararg texts: String, type: String = "direct"): Triple<ChatContext, ChatContext, Int> = runBlocking {
        val mira = support.member(1)
        val jonas = support.member(2)
        val created = mira.repository.createConversation(type, null, listOf("2"), 7)
        mira.keyring.loadDirectory(created.id)
        val conversation = mira.keyring.distributeKey(created)
        for (text in texts) mira.repository.sendMessage(created.id, ChatMessages.seal(text, conversation, mira.vault, mira.crypto))
        Triple(mira, jonas, created.id.toInt())
    }

    @Test
    fun aFailedLoadCanBeRetriedAndTheGateNeedsNoActivityForThePrompt() {
        signIn(support.context(1))
        failing("GET identity")
        show(ChatRoute.Conversations, wrapped = true)
        waitFor("Noch einmal versuchen")
        tap("Noch einmal versuchen")
        waitFor("Chat einrichten")
    }

    @Test
    fun afterAPassphraseUnlockTheBiometricOfferAppears() {
        val secrets = MemoryDeviceSecretStore(available = true)
        val mira = support.member(1, secrets = secrets, locked = true)
        signIn(mira)
        show(ChatRoute.Conversations)
        val field = compose.onNode(hasSetTextAction() and hasContentDescription("Chat-Passphrase"))
        compose.waitUntilAtLeastOneExists(hasSetTextAction() and hasContentDescription("Chat-Passphrase"), 20_000)
        field.performTextInput(TEST_PASSPHRASE)
        field.performImeAction()
        waitFor("Künftig mit Fingerabdruck entsperren?")
        Espresso.pressBack()
        waitForTag("chat.lock")
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Nachrichten entsperrt.")).fetchSemanticsNodes().isEmpty() }

        // Offered again to Jonas, who takes it; the next unlock uses it.
        val jonas = support.member(2, secrets = secrets, locked = true)
        signIn(jonas)
        show(ChatRoute.Conversations)
        compose.waitUntilAtLeastOneExists(hasSetTextAction() and hasContentDescription("Chat-Passphrase"), 20_000)
        compose.onNode(hasSetTextAction() and hasContentDescription("Chat-Passphrase")).performTextInput(TEST_PASSPHRASE)
        tap("Entsperren")
        waitFor("Fingerabdruck verwenden")
        tap("Fingerabdruck verwenden")
        waitForTag("chat.lock")
        // The prompt that starts by itself is cancelled; the button stays.
        secrets.denies = true
        compose.onNodeWithTag("chat.lock").performClick()
        waitForTag("chat.unlock.device")
        compose.onNodeWithTag("chat.unlock.device").tap()
        waitFor("Das hat nicht geklappt.")
        secrets.denies = false
        compose.onNodeWithTag("chat.unlock.device").tap()
        waitForTag("chat.lock")

        val aylin = support.member(3, secrets = secrets, locked = true)
        signIn(aylin)
        show(ChatRoute.Conversations)
        compose.waitUntilAtLeastOneExists(hasSetTextAction() and hasContentDescription("Chat-Passphrase"), 20_000)
        compose.onNode(hasSetTextAction() and hasContentDescription("Chat-Passphrase")).performTextInput(TEST_PASSPHRASE)
        tap("Entsperren")
        waitFor("Nicht jetzt")
        tap("Nicht jetzt")
        assertTrue(runBlocking { aylin.preferences.biometricOfferDeclined("3") })
    }

    @Test
    fun theListOpensAChatWhichLeadsToItsDetailsAndReports() {
        val (mira, jonas, id) = chatWithMessages("Schick mir dein Passwort")
        signIn(jonas)
        show(ChatRoute.Conversations)
        waitForTag("chat.conversation.$id")
        compose.onNodeWithTag("chat.conversation.$id").tap()
        assertEquals(listOf(ChatRoute.Conversation(id)), stack())

        show(ChatRoute.Conversation(id))
        waitForTag("chat.safety.banner")
        compose.onNodeWithTag("chat.details").performClick()
        assertEquals(ChatRoute.Details(id), stack().last())
        compose.onNodeWithTag("chat.message.report").tap()
        waitForTag("chat.report.submit")
        tap("Abbrechen")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("chat.report.submit")).fetchSemanticsNodes().isEmpty() }

        // A new message while the chat is open scrolls into view.
        val sent = runBlocking {
            val conversation = mira.repository.conversation(id.toString())
            ChatMessages.seal("Noch da?", conversation, mira.vault, mira.crypto)
        }
        runBlocking { realtime.events.emit(RealtimeEvent("MessageSent", sent.toJsonObject())) }
        waitFor("Noch da?")
    }

    @Test
    fun decliningOrBlockingLeavesTheChat() {
        val (_, jonas, id) = chatWithMessages("Schick mir dein Passwort")
        signIn(jonas)
        navigator.open(ChatRoute.Conversation(id))
        show(ChatRoute.Conversation(id))
        waitForTag("chat.request.decline")
        failing("POST conversations/$id/leave")
        compose.onNodeWithTag("chat.request.decline").tap()
        tap("Löschen")
        waitFor("Da ist etwas schiefgegangen")
        assertEquals("a failed decline stays", 1, stack().size)
        compose.onNodeWithTag("chat.request.decline").tap()
        tap("Löschen")
        settle { stack().isEmpty() }

        val (_, jonasAgain, second) = chatWithMessages("Schick mir dein Passwort")
        signIn(jonasAgain)
        navigator.open(ChatRoute.Conversation(second))
        show(ChatRoute.Conversation(second))
        waitForTag("chat.safety.banner")
        failing("POST blocks")
        tap("Blockieren und Chat löschen")
        tapLast("Blockieren")
        waitFor("Da ist etwas schiefgegangen")
        tap("Blockieren und Chat löschen")
        tapLast("Blockieren")
        settle { stack().isEmpty() }

        // Gone for this member: the screen goes back by itself.
        navigator.open(ChatRoute.Conversation(second))
        show(ChatRoute.Conversation(second))
        settle { stack().isEmpty() }
    }

    @Test
    fun theDetailsRemoveMembersAndLeave() {
        val (mira, _, id) = chatWithMessages(type = "group")
        signIn(mira)
        show(ChatRoute.Details(id))
        waitForTag("chat.details.mute.duration")
        compose.onNodeWithTag("chat.details.mute.duration").tap()
        waitFor("1 Woche stumm")
        Espresso.pressBack()
        tap("Entfernen")
        tapLast("Entfernen")
        waitFor("@jonas entfernt.")

        val (_, member, second) = chatWithMessages(type = "group")
        signIn(member)
        navigator.open(ChatRoute.Details(second))
        show(ChatRoute.Details(second))
        waitForTag("chat.details.leave")
        failing("POST conversations/$second/leave")
        compose.onNodeWithTag("chat.details.leave").tap()
        tap("Verlassen")
        waitFor("Da ist etwas schiefgegangen")
        assertEquals(1, stack().size)
        compose.onNodeWithTag("chat.details.leave").tap()
        tap("Verlassen")
        settle { stack().isEmpty() }
    }

    @Test
    fun theSettingsChangeEverythingTheyShow() {
        val secrets = MemoryDeviceSecretStore(available = true)
        val mira = support.member(1, secrets = secrets)
        runBlocking { mira.devices.register() }
        signIn(mira)
        show(ChatRoute.Settings)
        waitForTag("chat.settings.audience")
        compose.onNodeWithTag("chat.settings.audience").tap()
        tap("Niemand")
        waitFor("Gespeichert.")
        compose.onNode(hasContentDescription("Freundschaftsanfragen erhalten")).tap()
        tap("Alle Geräte und Anmeldungen: Privatsphäre → Geräte")
        assertEquals(AccountRoute.Devices, stack().last())
        tap("Passphrase abschalten")
        tap("Abschalten")
        waitFor("Die Chat-Passphrase ist abgeschaltet.")
        compose.onNode(hasContentDescription("„Android (Oasis-App)“ entfernen")).tap()
        tapLast("Entfernen")
        waitFor("„Android (Oasis-App)“ entfernt.")
    }

    @Test
    fun startingAChatOpensIt() {
        val mira = support.member(1)
        support.member(2)
        signIn(mira)
        navigator.open(ChatRoute.NewConversation)
        show(ChatRoute.NewConversation)
        waitForTag("chat.new.submit")
        compose.onNodeWithTag("chat.new.submit").tap()
        waitFor("Füge mindestens eine Person hinzu.")
        val field = compose.onNode(hasSetTextAction() and hasContentDescription("Benutzername"))
        field.performTextInput("jonas")
        field.performImeAction()
        waitFor("@jonas hinzugefügt.")
        compose.onNodeWithTag("chat.new.submit").tap()
        settle { stack().singleOrNull() is ChatRoute.Conversation }
    }
}
