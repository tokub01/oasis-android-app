package de.velwebsolutions.oasis.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.chat.api.ChatAvatar
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ChatSettings
import de.velwebsolutions.oasis.chat.api.ChatSuggestion
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.chat.api.DeviceUnlockRow
import de.velwebsolutions.oasis.chat.core.ChatKeyring
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.ui.BackupRowActions
import de.velwebsolutions.oasis.chat.ui.ChatAccessState
import de.velwebsolutions.oasis.chat.ui.ChatHeaderBar
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
import de.velwebsolutions.oasis.chat.ui.QuietNotice
import de.velwebsolutions.oasis.chat.ui.ReportContent
import de.velwebsolutions.oasis.chat.ui.ReportModel
import de.velwebsolutions.oasis.chat.ui.ReportSheet
import de.velwebsolutions.oasis.chat.ui.ReportState
import de.velwebsolutions.oasis.chat.ui.SafetyFlag
import de.velwebsolutions.oasis.chat.ui.SwipeHintActions
import de.velwebsolutions.oasis.chatcrypto.KdfLimits
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.design.theme.Appearance
import de.velwebsolutions.oasis.design.theme.AppearanceChoice
import de.velwebsolutions.oasis.design.theme.OasisColors
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

    private val navigator = de.velwebsolutions.oasis.design.navigation.Navigator()

    private fun show(content: @Composable () -> Unit) {
        screen = content
        compose.setContent {
            OasisTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    de.velwebsolutions.oasis.design.navigation.LocalNavigator provides navigator
                ) { screen() }
            }
        }
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
        compose.onNodeWithText("Chats neu einrichten").assertExists()
        compose.onNodeWithText("Ältere Nachrichten auf diesem Gerät kannst du danach nicht mehr lesen.").assertExists()
        compose.onNodeWithText("Deine Chatpartner sehen einen Hinweis, dass du die Chats neu eingerichtet hast.").assertExists()
        tapTag("chat.setup.device")
        compose.onNodeWithTag("chat.setup.submit").assertDoesNotExist()
        tapTag("chat.setup.passphraseToggle")
        tapTag("chat.setup.submit")
        tap("Zurück zum Entsperren")
        compose.onNodeWithTag("chat.setup.passphraseToggle").assertDoesNotExist()
        switchTo { Setup(ChatAccessState(deviceAvailable = true)) }
        compose.onNodeWithText("Deine Chats sind privat").assertExists()
        compose.onNodeWithText("Oasis speichert sie nur verschlüsselt und hat keinen Schlüssel dazu.", substring = true)
            .assertExists()
        compose.onNodeWithText("Mit Fingerabdruck einrichten").assertExists()
        switchTo { Setup(ChatAccessState(busy = true, formError = "Die Passphrase braucht mindestens 12 Zeichen.")) }
        capture("setup-passphrase-busy")
        compose.onNodeWithText("Wird eingerichtet. Das dauert ein paar Sekunden …").assertExists()
        compose.onNodeWithText("Chats einrichten").assertExists()
        assertEquals(listOf("device", "setUp", "cancelReset"), calls)
    }

    @Test
    fun unlockWithBiometricsThePassphraseOrAnotherDevice() {
        show {
            Unlock(ChatAccessState(canUnlockWithDevice = true, hasPassphrase = true, deviceUnlockError = "Das hat nicht geklappt."))
        }
        capture("unlock-device")
        compose.onNodeWithText("Chats entsperren").assertExists()
        tapTag("chat.unlock.device")
        tapTag("chat.unlock.usePassphrase")
        compose.onNodeWithText("Mit Passphrase entsperren").assertExists()
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
        onReject = { calls += "reject ${it.id}" },
        myId = "1"
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

    @Test
    fun theBackupRowAndPeopleToWriteFirst() {
        var written = ""
        val people = listOf(
            ChatSuggestion("2", "jonas", "Jonas", null, "Ihr seid befreundet"),
            ChatSuggestion("3", "aylin", "Aylin", null, "Ihr seid beide in Oase Lindenhof")
        )
        var canWrite by mutableStateOf(true)
        show {
            ConversationListContent(
                state = ConversationListState(loaded = true, backupRow = true, suggestions = people),
                canParticipate = canWrite,
                title = { it.displayTitle("1") },
                meta = { "" },
                onRefresh = {},
                onLoadMore = {},
                onOpen = {},
                onNew = { calls += "new" },
                onApprove = {},
                onReject = {},
                backup = BackupRowActions(onOpen = { calls += "backup" }, onSnooze = { calls += "later" }),
                onWrite = { written = it.username }
            )
        }
        capture("list-empty-suggestions")
        compose.onNodeWithText("Noch keine Chats").assertExists()
        compose.onNodeWithText("Schreib jemandem aus deinen Oasen.").assertExists()
        compose.onNodeWithText("Ihr seid beide in Oase Lindenhof").assertExists()
        compose.onNodeWithText("@aylin").assertExists()
        compose.onNodeWithTag("chat.new").assertDoesNotExist()
        compose.onNode(hasContentDescription("Aylin schreiben")).performClick()
        assertEquals("aylin", written)
        tapTag("chat.list.empty.new")
        tap("Sichere deine Chats, falls du dein Gerät verlierst")
        tapTag("chat.backupRow.later")
        compose.waitForIdle()
        canWrite = false
        compose.onNodeWithTag("chat.list.empty.new").assertDoesNotExist()
        compose.onNode(hasContentDescription("Jonas schreiben")).assertDoesNotExist()
        assertEquals(listOf("new", "backup", "later"), calls)
    }

    // endregion

    // region Conversation

    @Test
    fun quietNoticesTheSwipeHintAndItsNudge() {
        var details = 0
        var nudged = 0
        var conversation by mutableStateOf(
            ConversationState(
                conversation = chat("7"),
                title = "Jonas",
                canSend = true,
                quietNotices = listOf(QuietNotice("Jonas nutzt jetzt ein neues Gerät oder hat die Chats neu eingerichtet.", null)),
                swipeHint = true,
                nudge = "a",
                timeline = ChatTimeline(listOf(item("a", ChatMessageItem.State.Readable, text = "Hallo")))
            )
        )
        show {
            ConversationContent(
                state = conversation,
                myId = "1",
                senderName = { "Jonas" },
                onLoadOlder = {},
                onDraftChange = {},
                onSend = {},
                onReport = {},
                onAccept = {},
                onDecline = {},
                onBlockFlagged = {},
                onDismissFlagged = {},
                onNoticeSpoken = {},
                attachments = attachmentActions(calls),
                hint = SwipeHintActions(onDismiss = { calls += "understood" }, onNudged = { nudged += 1 }),
                onOpenDetails = { details += 1 }
            )
        }
        capture("conversation-quiet-hint")
        compose.waitUntil(5_000) { nudged == 1 }
        compose.onNodeWithText("Tipp: Nachricht nach rechts wischen zum Antworten").assertExists()
        compose.onNodeWithTag("chat.notice").performClick()
        assertEquals(1, details)
        compose.onNodeWithTag("chat.swipeHint.dismiss").performClick()
        compose.waitUntil(5_000) { "understood" in calls }
        conversation = conversation.copy(swipeHint = false, nudge = null)
        compose.onNodeWithTag("chat.swipeHint").assertDoesNotExist()
        compose.onNodeWithTag("chat.message.a").performTouchInput { swipeRight(startX = centerX - 40f, endX = centerX + 160f) }
        compose.waitForIdle()
        assertEquals(1, nudged)
    }

    @Test
    fun withoutMotionTheNudgeOnlyCountsAsPlayed() {
        val resolver = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            var nudged = 0
            show {
                de.velwebsolutions.oasis.chat.ui.SwipeToReply(true, {
                }, nudge = { nudged += 1 }) { androidx.compose.material3.Text("Hallo") }
            }
            compose.waitUntil(5_000) { nudged == 1 }
        } finally {
            android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    private val envelope = MessageEnvelope(1, "7", 1, "2", "n", "c", "s1", "2100-01-01T00:00:00Z")

    private fun item(signature: String, state: ChatMessageItem.State, sender: String = "2", text: String = "") =
        ChatMessageItem(signature, envelope.copy(signature = signature), sender, null, state, text, MessageContent(text))

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
        onNoticeSpoken = { calls += "spoken" },
        attachments = attachmentActions(calls),
        senderAvatar = { if (it == "2") jonasAvatar else ChatAvatar.Unknown }
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
                    notices = listOf("Du kannst hier noch nicht schreiben."),
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
        compose.onNodeWithTag("chat.message.a").performScrollTo().performTouchInput {
            longClick(bottomRight - androidx.compose.ui.geometry.Offset(6f, 6f))
        }
        compose.onNodeWithTag("chat.reactionBar.report").performClick()
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

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun theListAndAConversationInDarkMode() {
        Appearance.show(AppearanceChoice.Dark)
        try {
            show {
                // On the page's sand, as OasisScreen shows it in the app.
                Box(Modifier.background(OasisColors.Sand)) {
                    List(
                        ConversationListState(
                            conversations = listOf(chat("3", muted = true, unread = true), chat("4")),
                            requests = listOf(chat("5")),
                            loaded = true
                        )
                    )
                }
            }
            compose.waitForIdle()
            capture("dark-list")
            switchTo {
                Conversation(
                    ConversationState(
                        conversation = chat("7"),
                        title = "Jonas",
                        timeline = ChatTimeline(
                            listOf(
                                item("a", ChatMessageItem.State.Readable, text = "Kommst du heute zum Gießen?"),
                                item("b", ChatMessageItem.State.NoKey),
                                item("d", ChatMessageItem.State.Readable, sender = "1", text = "Ja, ab sechs.")
                            )
                        )
                    )
                )
            }
            capture("dark-conversation")
        } finally {
            Appearance.show(AppearanceChoice.Automatic)
        }
    }

    @Test
    fun anUntrustedOwnerReplacesTheComposer() {
        show { Conversation(ConversationState(conversation = chat("7"), title = "Jonas", untrustedOwner = true)) }
        capture("conversation-untrusted-owner")
        compose.onNodeWithTag("chat.composer.untrustedOwner").assertExists()
        compose.onNodeWithText(ChatKeyring.UNTRUSTED_OWNER).assertExists()
        compose.onNodeWithTag("chat.composer.field").assertDoesNotExist()
        switchTo { Conversation(ConversationState(conversation = chat("7"), title = "Jonas")) }
        compose.onNodeWithTag("chat.composer.untrustedOwner").assertDoesNotExist()
        compose.onNodeWithTag("chat.composer.field").assertExists()
    }

    private val jonasAvatar = ChatAvatar("J", "night", "/api/v1/network/profiles/jonas/photo/a.jpg")

    /** One send time for a run of messages. */
    private val sameTime = java.time.Instant.parse("2100-01-01T00:00:00Z")

    @Test
    fun avatarsBesideChatsAndTheStartOfEachRun() {
        val group = chat("9").copy(
            type = "group",
            members = members.map { it.copy(avatar = ChatAvatar(it.username.take(1).uppercase(), "spring")) } +
                ConversationMember("3", "aylin", "Aylin", avatar = jonasAvatar.copy(initials = "A"))
        )
        val alone = chat("11").copy(members = members.take(1))
        val away = chat("12").copy(members = members.map { if (it.userId == "2") it.copy(status = "Im Urlaub bis Sonntag") else it })
        show {
            ChatHeaderBar(away, "1", "Jonas", away.directStatus("1").orEmpty(), onBack = { calls += "back" }, onDetails = {
                calls +=
                    "details"
            })
        }
        compose.onNodeWithTag("chat.conversation.status").assertExists()
        capture("header")
        compose.onNodeWithTag("chat.header.back").performClick()
        compose.onNodeWithTag("chat.details").performClick()
        assertTrue(calls.containsAll(listOf("back", "details")))
        switchTo { List(ConversationListState(conversations = listOf(chat("3"), group, alone), loaded = true)) }
        capture("list-avatars")
        switchTo {
            Conversation(
                ConversationState(
                    conversation = group,
                    title = "Garten",
                    timeline = ChatTimeline(
                        listOf(
                            item("a", ChatMessageItem.State.Readable, text = "Hallo").copy(expiresAt = sameTime),
                            item("b", ChatMessageItem.State.Readable, text = "Bist du da?").copy(expiresAt = sameTime),
                            item("c", ChatMessageItem.State.Readable, sender = "1", text = "Ja.").copy(expiresAt = sameTime),
                            item("d", ChatMessageItem.State.Readable, sender = "3", text = "Ich auch.").copy(expiresAt = sameTime)
                        )
                    )
                )
            )
        }
        capture("conversation-avatars")
        // Jonas' run of two and Aylin's message: one avatar each, none beside my own.
        compose.onAllNodesWithTag("chat.message.avatar", useUnmergedTree = true).assertCountEquals(2)
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
            MemberRow(
                "2",
                "Jonas",
                "jonas",
                false,
                "12345 67890",
                "Neues Gerät oder neu eingerichtet – Nummer erneut vergleichen.",
                owner,
                jonasAvatar,
                status = "Im Urlaub bis Sonntag"
            )
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
        compose.onNodeWithTag("chat.details.avatar.jonas", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("chat.details.status.jonas").assertExists()
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
            }, {}, { calls += "close" }, onPrivacy = { calls += "privacy" })
        }
        capture("report-form")
        tap(ReportModel.categories.first())
        tapTag("chat.report.submit")
        tapTag("chat.report.privacy")
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
        assertEquals(listOf("category", "send", "privacy", "close", "block", "close"), calls)
    }

    @Test
    fun theReportSheetShowsTheModel() {
        val support = ChatTestSupport()
        val mira = support.member(1)
        val item = ChatMessageItem("s", envelope, "2", null, ChatMessageItem.State.Readable, "Hallo", MessageContent("Hallo"))
        show { ReportSheet(ReportModel(mira, "7", item, "Jonas"), onDismiss = { calls += "dismiss" }) }
        compose.waitUntilAtLeastOneExists(hasText("Jonas: Hallo"), 5_000)
        capture("report-sheet")
        // Without an API client in the test there is no page to open; the link does nothing.
        tapTag("chat.report.privacy")
        tap("Abbrechen")
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun theReportSheetOpensThePrivacyPageOfItsServer() {
        val web = mutableListOf<String>()
        navigator.openWeb = { web += it }
        val support = ChatTestSupport()
        val mira = support.member(1)
        val item = ChatMessageItem("s", envelope, "2", null, ChatMessageItem.State.Readable, "Hallo", MessageContent("Hallo"))
        show {
            androidx.compose.runtime.CompositionLocalProvider(
                de.velwebsolutions.oasis.design.components.LocalApiClient provides de.velwebsolutions.oasis.testing.StubTransport().client
            ) { ReportSheet(ReportModel(mira, "7", item, "Jonas"), onDismiss = { calls += "dismiss" }) }
        }
        compose.waitUntilAtLeastOneExists(hasText("Jonas: Hallo"), 5_000)
        // The new category sits between „Sexuelle Belästigung“ and „Hass“, as the server lists them.
        compose.onNodeWithText("Anbieten oder Suchen sexueller oder bezahlter Dienste").assertExists()
        tapTag("chat.report.privacy")
        assertEquals(listOf("http://localhost/datenschutz#meldungen"), web)
    }

    @Test
    fun aConfirmationWithoutTitleHandlesBothButtons() {
        show { ConfirmDialog("Wirklich?", "Ja", { calls += "yes" }) { calls += "no" } }
        compose.onNodeWithText("Ja").performClick()
        assertEquals(listOf("no", "yes"), calls)
    }

    // endregion
}
