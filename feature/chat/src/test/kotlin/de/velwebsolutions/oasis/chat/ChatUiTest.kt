package de.velwebsolutions.oasis.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ChatSettings
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.chat.api.DeviceUnlockRow
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.ui.ChatAccessState
import de.velwebsolutions.oasis.chat.ui.ChatSettingsContent
import de.velwebsolutions.oasis.chat.ui.ChatSettingsState
import de.velwebsolutions.oasis.chat.ui.ChatSetupContent
import de.velwebsolutions.oasis.chat.ui.ChatUnlockContent
import de.velwebsolutions.oasis.chat.ui.ConfirmDialog
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsContent
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsState
import de.velwebsolutions.oasis.chat.ui.ConversationListContent
import de.velwebsolutions.oasis.chat.ui.ConversationListState
import de.velwebsolutions.oasis.chat.ui.ConversationState
import de.velwebsolutions.oasis.chat.ui.LinkRequestEntry
import de.velwebsolutions.oasis.chat.ui.MemberRow
import de.velwebsolutions.oasis.chat.ui.NewConversationContent
import de.velwebsolutions.oasis.chat.ui.NewConversationState
import de.velwebsolutions.oasis.chat.ui.ReportContent
import de.velwebsolutions.oasis.chat.ui.ReportModel
import de.velwebsolutions.oasis.chat.ui.ReportSheet
import de.velwebsolutions.oasis.chat.ui.ReportState
import de.velwebsolutions.oasis.chat.ui.SafetyFlag
import de.velwebsolutions.oasis.chatcrypto.KdfLimits
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.design.theme.OasisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every chat screen in each of its states (Robolectric) with a screenshot per state (Roborazzi, under
 * build/outputs/roborazzi, not committed), and the taps that change a state or leave the screen.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h3000dp")
class ChatUiTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var screen by mutableStateOf<@Composable () -> Unit>({})

    private fun show(content: @Composable () -> Unit) {
        screen = content
        compose.setContent { OasisTheme { screen() } }
    }

    private fun switchTo(content: @Composable () -> Unit) {
        screen = content
        compose.waitForIdle()
    }

    /** The screen, or the dialog above it, as a screenshot. */
    private fun capture(name: String) {
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/chat-$name.png")
    }

    /** Taps [text], scrolling to it first where it sits in a scrolling page (dialog buttons do not). */
    private fun tap(text: String) {
        val node = compose.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun tapTag(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    // region Gate

    @Composable
    private fun Setup(state: ChatAccessState) = ChatSetupContent(
        state,
        { calls += "passphrase" },
        { calls += "confirmation" },
        { calls += "setUp" },
        { calls += "device" },
        { calls += "cancelReset" }
    )

    @Composable
    private fun Unlock(state: ChatAccessState) = ChatUnlockContent(
        state,
        { calls += "passphrase" },
        { calls += "unlock" },
        { calls += "device" },
        { calls += "reset" },
        { calls += "link" },
        { calls += "cancelLink" }
    )

    @Test
    fun setupWithBiometricsOrAPassphrase() {
        show { Setup(ChatAccessState(deviceAvailable = true, deviceSetupStatus = "Bitte bestätige …", canCancelReset = true)) }
        capture("setup-device")
        tapTag("chat.setup.device")
        tapTag("chat.setup.passphraseToggle")
        tapTag("chat.setup.submit")
        tap("Zurück zum Entsperren")
        tap("Lieber mit eigener Chat-Passphrase")
        compose.onNodeWithTag("chat.setup.submit").assertDoesNotExist()
        switchTo { Setup(ChatAccessState(busy = true, formError = "Die Passphrase braucht mindestens 12 Zeichen.")) }
        capture("setup-passphrase-busy")
        compose.onNodeWithText("Schlüssel werden erstellt. Das dauert ein paar Sekunden …").assertExists()
        assertEquals(listOf("device", "setUp", "cancelReset"), calls)
    }

    @Test
    fun unlockWithBiometricsThePassphraseOrAnotherDevice() {
        show {
            Unlock(ChatAccessState(canUnlockWithDevice = true, hasPassphrase = true, deviceUnlockError = "Das hat nicht geklappt."))
        }
        capture("unlock-device")
        tapTag("chat.unlock.device")
        tapTag("chat.unlock.usePassphrase")
        tapTag("chat.unlock.submit")
        tapTag("chat.link.request")
        tap("Passphrase vergessen?")
        capture("unlock-forgotten")
        tapTag("chat.unlock.reset")
        tap("Passphrase vergessen?")
        switchTo { Unlock(ChatAccessState(hasPassphrase = true, busy = true, linkCheck = "4821 9930", linkStatus = "Abgelehnt.")) }
        capture("unlock-link-waiting")
        compose.onNodeWithText("Wird entsperrt …").assertExists()
        tap("Abbrechen")
        switchTo { Unlock(ChatAccessState()) }
        capture("unlock-device-only-elsewhere")
        compose.waitForIdle()
        assertEquals(listOf("device", "unlock", "link", "reset", "cancelLink"), calls)
    }

    // endregion

    // region List

    private val members = listOf(ConversationMember("1", "mira", "Mira"), ConversationMember("2", "jonas", "Jonas"))

    private fun chat(id: String, muted: Boolean? = null, unread: Boolean? = null) =
        ChatConversation(id, "direct", null, "1", 7, 1, false, members, "", unread = unread, muted = muted)

    @Composable
    private fun List(state: ConversationListState, canParticipate: Boolean = true) = ConversationListContent(
        state = state,
        canParticipate = canParticipate,
        title = { it.displayTitle("1") },
        meta = { "Zu zweit" },
        onRefresh = { calls += "refresh" },
        onLoadMore = { calls += "more" },
        onOpen = { calls += "open ${it.id}" },
        onNew = { calls += "new" },
        onApprove = { calls += "approve ${it.id}" },
        onReject = { calls += "reject ${it.id}" }
    )

    @Test
    fun theListInEveryState() {
        show { List(ConversationListState()) }
        capture("list-loading")
        switchTo { List(ConversationListState(error = "Da ist etwas schiefgegangen.")) }
        capture("list-error")
        val request = LinkRequestEntry(DeviceLinkRequest("8", "cGs", "Laptop"), "4821 9930")
        switchTo {
            List(
                ConversationListState(
                    conversations = listOf(chat("3", muted = true, unread = true)),
                    requests = listOf(chat("5")),
                    linkRequests = listOf(request),
                    loaded = true,
                    nextPage = 2
                )
            )
        }
        capture("list-content")
        compose.onNodeWithTag("chat.list.muted", useUnmergedTree = true).assertExists()
        tapTag("chat.link.approve")
        tapTag("chat.link.reject")
        tap("Ältere laden")
        compose.onNodeWithTag("chat.list").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { "refresh" in calls }
        assertTrue(calls.containsAll(listOf("approve 8", "reject 8", "more", "refresh")))
    }

    // endregion

    // region Conversation

    private val envelope = MessageEnvelope(1, "7", 1, "2", "n", "c", "s1", "2100-01-01T00:00:00Z")

    private fun item(signature: String, state: ChatMessageItem.State, sender: String = "2", text: String = "") =
        ChatMessageItem(signature, envelope.copy(signature = signature), sender, null, state, text)

    @Composable
    private fun Conversation(state: ConversationState) = ConversationContent(
        state = state,
        myId = "1",
        senderName = { if (it == "2") "Jonas" else "Unbekannt" },
        onLoadOlder = { calls += "older" },
        onDraftChange = { calls += "draft" },
        onSend = { calls += "send" },
        onReport = { calls += "report ${it.signature}" },
        onAccept = { calls += "accept" },
        onDecline = { calls += "decline $it" },
        onBlockFlagged = { calls += "block $it" },
        onDismissFlagged = { calls += "dismiss" },
        onNoticeSpoken = { calls += "spoken" }
    )

    @Test
    fun theConversationInEveryState() {
        show { Conversation(ConversationState()) }
        capture("conversation-loading")
        switchTo { Conversation(ConversationState(loadFailed = "Da ist etwas schiefgegangen.")) }
        capture("conversation-failed")
        val readable = item("a", ChatMessageItem.State.Readable, text = "Schick mir dein Passwort")
        val flagged =
            SafetyFlag(
                readable,
                "Achtung: Hier fragt jemand nach sensiblen Daten. Jonas blockieren und Chat löschen?",
                "Gib so etwas nie heraus."
            )
        switchTo {
            Conversation(
                ConversationState(
                    conversation = chat("7"),
                    title = "Jonas",
                    notices = listOf("Für diesen Chat liegt dir noch kein Schlüssel vor."),
                    requestTitle = "Nachrichtenanfrage von Jonas",
                    flagged = flagged,
                    nextBeforeId = "4",
                    hint = "Kurzer Hinweis: „Digga“ wirkt schnell herablassend.",
                    spokenNotice = "Neue Nachricht von Jonas.",
                    timeline = ChatTimeline(
                        listOf(
                            readable,
                            item("b", ChatMessageItem.State.NoKey),
                            item("c", ChatMessageItem.State.Unverifiable),
                            item("d", ChatMessageItem.State.Readable, sender = "1", text = "Nein.")
                        )
                    )
                )
            )
        }
        capture("conversation-content")
        compose.waitUntil(5_000) { "spoken" in calls }
        tapTag("chat.request.accept")
        tapTag("chat.request.decline")
        capture("conversation-decline-dialog")
        tap("Löschen")
        tap("Blockieren")
        compose.onNodeWithText("Diese Person blockieren und die Anfrage löschen?").assertExists()
        compose.onAllNodes(hasText("Blockieren"))[compose.onAllNodes(hasText("Blockieren")).fetchSemanticsNodes().size - 1].performClick()
        tap("Blockieren und Chat löschen")
        compose.onNodeWithText("Jonas blockieren und diesen Chat löschen?").assertExists()
        compose.onAllNodes(hasText("Blockieren"))[compose.onAllNodes(hasText("Blockieren")).fetchSemanticsNodes().size - 1].performClick()
        tap("Nachricht melden")
        tap("Ist in Ordnung")
        tap("Ältere Nachrichten laden")
        compose.onNode(hasContentDescription("Nachricht von Jonas melden")).performScrollTo().performClick()
        // Dialogs that are left without confirming.
        tapTag("chat.request.decline")
        tap("Abbrechen")
        tap("Blockieren")
        tap("Abbrechen")
        tap("Blockieren und Chat löschen")
        tap("Abbrechen")
        switchTo { Conversation(ConversationState(conversation = chat("7"), title = "Jonas")) }
        capture("conversation-empty")
        compose.onNodeWithTag("chat.messages.empty").assertExists()
        assertTrue(
            calls.containsAll(
                listOf("accept", "decline false", "decline true", "block 2", "report a", "dismiss", "older", "spoken")
            )
        )
    }

    // endregion

    // region Details

    private fun detailsState(owner: Boolean, muted: Boolean) = ConversationDetailsState(
        conversation = chat("7", muted = muted).copy(type = "group", mutedUntil = null),
        isOwner = owner,
        title = "Garten",
        addError = if (owner) "Gibt es nicht." else null,
        members = listOf(
            MemberRow("1", "Mira – du", "mira", true, null, null, false),
            MemberRow("2", "Jonas", "jonas", false, "12345 67890", "Neue Schlüssel – Nummer erneut vergleichen.", owner)
        )
    )

    @Composable
    private fun Details(state: ConversationDetailsState) = ConversationDetailsContent(
        state = state,
        canParticipate = true,
        onMuteDuration = { calls += "duration $it" },
        onMute = { calls += "mute" },
        onUnmute = { calls += "unmute" },
        onLifetime = { calls += "lifetime $it" },
        onSaveLifetime = { calls += "saveLifetime" },
        onTitle = { calls += "title" },
        onSaveTitle = { calls += "saveTitle" },
        onUsername = { calls += "username" },
        onAddMember = { calls += "add" },
        onRemove = { calls += "remove ${it.username}" },
        onLeave = { calls += "leave" }
    )

    @Test
    fun theDetailsForMembersAndOwners() {
        show { Details(ConversationDetailsState()) }
        capture("details-loading")
        switchTo { Details(ConversationDetailsState(loadFailed = "Diesen Chat gibt es nicht mehr.")) }
        switchTo { Details(detailsState(owner = true, muted = true)) }
        capture("details-owner-muted")
        tapTag("chat.details.unmute")
        tapTag("chat.details.mute")
        tapTag("chat.details.mute.duration")
        compose.onNodeWithText("1 Woche stumm").performClick()
        tapTag("chat.details.title.save")
        tap("Hinzufügen")
        tap("Entfernen")
        capture("details-remove-dialog")
        compose.onAllNodes(hasText("Entfernen"))[compose.onAllNodes(hasText("Entfernen")).fetchSemanticsNodes().size - 1].performClick()
        tap("Entfernen")
        tap("Abbrechen")
        switchTo { Details(detailsState(owner = false, muted = false)) }
        capture("details-member")
        tapTag("chat.details.mute")
        tapTag("chat.details.leave")
        compose.onNodeWithText("Verlassen").performClick()
        tapTag("chat.details.leave")
        tap("Abbrechen")
        assertTrue(calls.containsAll(listOf("unmute", "mute", "duration 1w", "saveTitle", "add", "remove jonas", "leave")))
    }

    // endregion

    // region Settings and new chat

    private val row = DeviceUnlockRow(
        "1",
        "app-x",
        "Pixel",
        testCrypto.lockIdentity(testCrypto.createIdentity(), "Palme Regen Kompass Tee", KdfLimits.Minimum),
        "2026-10-22T12:00:00Z"
    )

    @Composable
    private fun Settings(state: ChatSettingsState) = ChatSettingsContent(
        state = state,
        onAudience = { calls += "audience $it" },
        onFriendRequests = { calls += "friends $it" },
        onHints = { calls += "hints" },
        onAccountDevices = { calls += "devices" },
        onAddDevice = { calls += "addDevice" },
        onRemoveDevice = { calls += "removeDevice ${it.id}" },
        onPassphrase = { calls += "passphrase" },
        onConfirmation = { calls += "confirmation" },
        onSaveBackup = { calls += "backup" },
        onRemovePassphrase = { calls += "removePassphrase" }
    )

    @Test
    fun theSettingsInEveryState() {
        val settings = ChatSettings("members", true, mapOf("members" to "Alle Mitglieder", "nobody" to "Niemand"))
        show { Settings(ChatSettingsState(settings = settings, deviceAvailable = true, busy = true)) }
        capture("settings-new-device")
        tapTag("chat.devices.add")
        tapTag("chat.settings.hints")
        tap("Alle Geräte und Anmeldungen: Privatsphäre → Geräte")
        tapTag("chat.settings.audience")
        compose.onNodeWithText("Niemand").performClick()
        switchTo {
            Settings(
                ChatSettingsState(
                    devices = listOf(row, row.copy(id = "2")),
                    ownDeviceId = "1",
                    hasPassphrase = true,
                    deviceAvailable = true
                )
            )
        }
        capture("settings-devices")
        compose.onAllNodes(hasContentDescription("„Pixel“ entfernen"))[0].performScrollTo().performClick()
        tap("Passphrase abschalten")
        capture("settings-remove-passphrase-dialog")
        tap("Abschalten")
        tap("Passphrase abschalten")
        tap("Abbrechen")
        switchTo { Settings(ChatSettingsState(devices = listOf(row), ownDeviceId = "1")) }
        capture("settings-last-device")
        compose.onNodeWithText("Auf diesem Gerät ist kein Fingerabdruck und keine Gesichtserkennung verfügbar.").assertExists()
        compose.onNode(hasContentDescription("„Pixel“ entfernen")).performScrollTo().performClick()
        compose.onAllNodes(hasText("Entfernen"))[compose.onAllNodes(hasText("Entfernen")).fetchSemanticsNodes().size - 1].performClick()
        compose.onNode(hasContentDescription("„Pixel“ entfernen")).performScrollTo().performClick()
        tap("Abbrechen")
        assertTrue(
            calls.containsAll(listOf("addDevice", "hints", "devices", "audience nobody", "removeDevice 1", "removePassphrase"))
        )
    }

    @Test
    fun aNewChatForReadOnlyAccountsAndGroups() {
        show {
            NewConversationContent(
                state = NewConversationState(
                    kind = de.velwebsolutions.oasis.chat.ui.ConversationKind.Group,
                    error = "Füge mindestens eine Person hinzu."
                ),
                canParticipate = false,
                onKind = { calls += "kind $it" },
                onUsername = {},
                onTitle = { calls += "title" },
                onLifetime = { calls += "lifetime $it" },
                onAdd = {},
                onRemove = {},
                onCreate = {}
            )
        }
        capture("new-group-read-only")
        compose.onNodeWithTag("chat.new.title").assertExists()
        tap("Zu zweit")
        tap("30 Tage")
        assertTrue(calls.containsAll(listOf("kind Direct", "lifetime 30")))
    }

    // endregion

    // region Report

    @Test
    fun theReportDialogFromFormToThanks() {
        show {
            ReportContent(ReportState(quote = "Jonas: Hallo", error = "Wähl bitte aus, was nicht stimmt."), { calls += "category" }, {}, {
                calls +=
                    "send"
            }, {}, { calls += "close" })
        }
        capture("report-form")
        tap(ReportModel.categories.first())
        tapTag("chat.report.submit")
        tap("Abbrechen")
        switchTo {
            ReportContent(ReportState(category = ReportModel.categories.first(), sent = true, error = "Da ist etwas schiefgegangen."), {
            }, {}, {}, {
                calls +=
                    "block"
            }, { calls += "close" })
        }
        capture("report-thanks")
        tap("Person blockieren")
        switchTo { ReportContent(ReportState(sent = true, blocked = true), {}, {}, {}, {}, { calls += "close" }) }
        capture("report-blocked")
        compose.onAllNodes(hasText("Schließen"))[1].performScrollTo().performClick()
        assertEquals(listOf("category", "send", "close", "block", "close"), calls)
    }

    @Test
    fun theReportSheetShowsTheModel() {
        val support = ChatTestSupport()
        val mira = support.member(1)
        val item = ChatMessageItem("s", envelope, "2", null, ChatMessageItem.State.Readable, "Hallo")
        show { ReportSheet(ReportModel(mira, "7", item, "Jonas"), onDismiss = { calls += "dismiss" }) }
        compose.waitUntilAtLeastOneExists(hasText("Jonas: Hallo"), 5_000)
        capture("report-sheet")
        tap("Abbrechen")
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun aConfirmationWithoutTitleHandlesBothButtons() {
        show { ConfirmDialog("Wirklich?", "Ja", { calls += "yes" }) { calls += "no" } }
        compose.onNodeWithText("Ja").performClick()
        assertEquals(listOf("no", "yes"), calls)
    }

    // endregion
}
