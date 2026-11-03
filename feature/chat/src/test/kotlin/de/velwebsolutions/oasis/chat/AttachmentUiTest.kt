package de.velwebsolutions.oasis.chat

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.chat.api.AttachmentState
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.core.AttachmentKind
import de.velwebsolutions.oasis.chat.core.AttachmentView
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.core.DraftAttachment
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationState
import de.velwebsolutions.oasis.chat.ui.ReportContent
import de.velwebsolutions.oasis.chat.ui.ReportState
import de.velwebsolutions.oasis.chat.ui.Transcript
import de.velwebsolutions.oasis.chat.ui.ViewOnceViewer
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.design.theme.OasisTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The attachments on the conversation screen (Robolectric, a screenshot per state): composer with files, view once,
 * progress, recording and the microphone question; files in messages in every state; full screen and the view-once viewer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h3000dp")
class AttachmentUiTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = attachmentActions(calls)
    private var state by mutableStateOf(ConversationState())
    private val application = ApplicationProvider.getApplicationContext<Application>()

    /** What the system answers: the picked file, or whether the microphone was allowed. */
    private var answer: Any? = null
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            calls += "launch ${contract.javaClass.simpleName}"
            dispatchResult(requestCode, answer)
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    private val conversation = ChatConversation(
        id = "7",
        type = "direct",
        createdBy = "1",
        lifetimeDays = 7,
        keyVersion = 1,
        rotationRequired = false,
        members = emptyList(),
        serverTime = "2026-10-22T12:00:00Z"
    )

    private fun show(content: @Composable () -> Unit = { Screen() }) {
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { content() } } }
    }

    @Composable
    private fun Screen() = ConversationContent(
        state = state,
        myId = "1",
        senderName = { "Jonas" },
        onLoadOlder = {},
        onDraftChange = {},
        onSend = { calls += "send" },
        onReport = { calls += "report ${it.signature}" },
        onAccept = {},
        onDecline = {},
        onBlockFlagged = {},
        onDismissFlagged = {},
        onNoticeSpoken = {},
        attachments = actions
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/chat-attachments-$name.png")
    }

    /** Taps the [index]th node with [tag], scrolling to it first where it sits in the message list. */
    private fun tapTag(tag: String, index: Int = 0) {
        val node = compose.onAllNodes(hasTestTag(tag))[index]
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
    }

    private val image = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.asImageBitmap()
    private val envelope = MessageEnvelope(1, "7", 1, "2", "n", "c", "s")

    private fun entry(id: String, kind: String, mime: String, durationMs: Long? = null) =
        MessageAttachment(id, kind, mime, 1_468_006, "k", "n", durationMs = durationMs)

    private fun item(
        signature: String,
        sender: String,
        text: String,
        files: List<MessageAttachment>,
        states: List<AttachmentState> = emptyList(),
        /** Files the server's row of the message does not list (an id copied from another message). */
        unlisted: Set<String> = emptySet()
    ) = ChatMessageItem(
        signature,
        envelope.copy(signature = signature),
        sender,
        null,
        ChatMessageItem.State.Readable,
        "json",
        MessageContent(text, files),
        // The server lists every file of others' messages unless the test says otherwise: pending where no state is given.
        ((if (sender == "1") emptyList() else files.filter { it.id !in unlisted }.map { AttachmentState(it.id) }) + states)
            .associateBy { it.id }
    )

    @Test
    fun theComposerWithFilesProgressAndRecording() {
        val drafts = listOf(
            DraftAttachment(1, AttachmentKind.Image, "image/jpeg", png(), 60, 40),
            DraftAttachment(2, AttachmentKind.Audio, "audio/mp4", byteArrayOf(1), durationMs = 4_000, viewOnce = true),
            DraftAttachment(3, AttachmentKind.Video, "video/mp4", byteArrayOf(2), durationMs = 12_000)
        )
        state = ConversationState(conversation = conversation, canSend = true, drafts = drafts, preparing = true)
        show()
        compose.onNodeWithTag("chat.composer.viewOnceNote").assertExists()
        compose.onNodeWithText("Anhang wird vorbereitet …").assertExists()
        capture("composer-drafts")
        compose.onNodeWithTag("chat.composer.viewOnce").assertDoesNotExist()
        state = state.copy(viewOnceNext = true)
        compose.onNodeWithTag("chat.composer.viewOnceChip").assertExists()
        tapTag("chat.composer.viewOnceClear")
        tapTag("chat.composer.removeDraft", 2)
        compose.onNode(hasContentDescription("Video entfernen")).assertExists()
        state = state.copy(preparing = false, uploadProgress = 0.4f)
        compose.onNode(hasContentDescription("Anhänge werden hochgeladen, 40 Prozent")).assertExists()
        compose.onNodeWithText("Wird verschlüsselt und hochgeladen … 40 %").assertExists()
        capture("composer-progress")
        state = ConversationState(conversation = conversation, canSend = true, recordingSeconds = 75, draft = "Hör mal")
        assertTrue("no sending while recording", !state.canSubmit)
        compose.onNodeWithText("1:15").assertExists()
        compose.onNodeWithText("noch 1:45").assertExists()
        compose.onNodeWithText("Verwerfen").assertExists()
        capture("composer-recording")
        tapTag("chat.composer.cancelRecording")
        tapTag("chat.composer.finishRecording")
        compose.waitForIdle()
        assertEquals(listOf("viewOnce false", "remove 3", "cancel", "finish"), calls)
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun onANarrowPhoneTheFieldTakesTheWidthAndOneButtonSendsOrRecords() {
        state = ConversationState(conversation = conversation, canSend = true)
        show()
        capture("composer-360dp-empty")
        compose.onNodeWithTag("chat.composer.record").assertIsEnabled()
        // 360 dp minus the floating bar's margins (2 × 12) and padding (2 × 6), „+“ and the round button (2 × 44) and the
        // gaps (2 × 8): 220 dp for the text.
        val field = compose.onNodeWithTag("chat.composer.field").fetchSemanticsNode().size.width
        val density = compose.onRoot().fetchSemanticsNode().layoutInfo.density.density
        assertTrue("the field is ${field / density} dp wide", field / density >= 218f)

        // The view model measures every draft (ConversationViewModel.render); this state is set by hand, so it does too.
        val text = "Hallo Jonas, wie war dein Tag? Ich wollte dir noch etwas Längeres schreiben."
        state = state.copy(draft = text, counter = "${ChatMessages.measure(text).characters} / 4.000")
        compose.onNodeWithText("76 / 4.000").assertExists()
        capture("composer-360dp-text")
        compose.onNodeWithTag("chat.composer.send").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(calls.contains("send"))
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun aVoiceMessagePlaysSeeksAndChangesItsSpeed() {
        de.velwebsolutions.oasis.design.media.VoiceSpeed.reset()
        val voiceFile = File(application.cacheDir, "sprachnachricht.m4a").apply { writeBytes(byteArrayOf(1)) }
        org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
            org.robolectric.shadows.util.DataSource.toDataSource(voiceFile.path),
            org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(45_000, 0)
        )
        val voice = entry("2", "audio", "audio/mp4", durationMs = 45_000)
        state = ConversationState(
            conversation = conversation,
            timeline = ChatTimeline(
                listOf(item("a", "2", "", listOf(voice), listOf(AttachmentState("2", status = AttachmentState.ACCEPTED))))
            ),
            attachmentViews = mapOf("2" to AttachmentView.Playable(voiceFile, null))
        )
        show()
        // Before the first play only the length.
        compose.onNodeWithTag("chat.voice.time").assertTextEquals("0:45")
        capture("voice-message-360dp")

        compose.onNodeWithContentDescription("Abspielen").performClick()
        compose.onNodeWithContentDescription("Pausieren").assertExists()
        compose.onNodeWithTag("chat.voice.time").assertTextEquals("0:00 / 0:45")
        compose.onNodeWithContentDescription("Pausieren").performClick()
        compose.onNodeWithContentDescription("Abspielen").assertExists()

        // TalkBack: the slider „Position“ moves in steps of 5 s.
        val position = compose.onNodeWithContentDescription("Position")
        position.performSemanticsAction(SemanticsActions.SetProgress) { it(1_000f) }
        compose.onNodeWithTag("chat.voice.time").assertTextEquals("0:05 / 0:45")
        position.performSemanticsAction(SemanticsActions.SetProgress) { it(30_000f) }
        compose.onNodeWithTag("chat.voice.time").assertTextEquals("0:10 / 0:45")
        position.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("chat.voice.time").assertTextEquals("0:05 / 0:45")
        // A tap or drag on the bar moves there (the end of a message is VoicePlayer's: back to the start).
        compose.onNodeWithTag("chat.voice.position").performTouchInput { click(center) }
        val moved = compose.onNodeWithTag("chat.voice.time").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertTrue(moved, moved.matches(Regex("0:2[0-5] / 0:45")))

        // The speed goes round for every voice message.
        compose.onNodeWithTag("chat.voice.speed").assertTextEquals("1×").performClick()
        compose.onNodeWithTag("chat.voice.speed").assertTextEquals("1,5×").performClick()
        compose.onNodeWithTag("chat.voice.speed").assertTextEquals("2×")
        capture("voice-message-360dp-played")
        de.velwebsolutions.oasis.design.media.VoiceSpeed.reset()
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun aVoiceMessageAsTextInEveryState() {
        val voiceFile = File(application.cacheDir, "text.m4a").apply { writeBytes(byteArrayOf(1)) }
        val voice = entry("2", "audio", "audio/mp4", durationMs = 45_000)
        val base = ConversationState(
            conversation = conversation,
            timeline = ChatTimeline(
                listOf(item("a", "2", "", listOf(voice), listOf(AttachmentState("2", status = AttachmentState.ACCEPTED))))
            ),
            attachmentViews = mapOf("2" to AttachmentView.Playable(voiceFile, null))
        )
        // Without German on-device recognition: no button.
        state = base
        show()
        compose.onNodeWithText("Als Text anzeigen").assertDoesNotExist()

        state = base.copy(canTranscribe = true)
        compose.onNodeWithText("Als Text anzeigen").performClick()
        compose.waitForIdle()
        assertTrue(calls.contains("transcribe 2"))
        state = state.copy(transcripts = mapOf("2" to Transcript.Working))
        compose.onNodeWithText("Wird umgewandelt …").assertExists()
        state = state.copy(transcripts = mapOf("2" to Transcript.Shown("Bin in zehn Minuten da, bring Brot mit.")))
        compose.onNodeWithTag("chat.voice.transcript").assertTextEquals("Bin in zehn Minuten da, bring Brot mit.")
        capture("voice-message-text-360dp")
        compose.onNodeWithText("Text ausblenden").performClick()
        assertTrue(calls.contains("hideText 2"))
        state = state.copy(transcripts = mapOf("2" to Transcript.Hidden("Bin in zehn Minuten da, bring Brot mit.")))
        compose.onNodeWithTag("chat.voice.transcript").assertDoesNotExist()
        compose.onNodeWithText("Als Text anzeigen").assertExists()
        state = state.copy(transcripts = mapOf("2" to Transcript.Failed("Das ließ sich nicht in Text umwandeln.")))
        compose.onNodeWithText("Das ließ sich nicht in Text umwandeln.").assertExists()
        compose.onNodeWithText("Als Text anzeigen").assertExists()

        // Not in the tray.
        state = ConversationState(
            conversation = conversation,
            canSend = true,
            canTranscribe = true,
            drafts = listOf(DraftAttachment(1, AttachmentKind.Audio, "audio/mp4", byteArrayOf(9), durationMs = 2_000))
        )
        compose.onNodeWithText("Als Text anzeigen").assertDoesNotExist()
    }

    @Test
    fun aRecordingInTheTrayPlaysWithTheSamePlayer() {
        state = ConversationState(
            conversation = conversation,
            canSend = true,
            drafts = listOf(DraftAttachment(1, AttachmentKind.Audio, "audio/mp4", byteArrayOf(9, 9), durationMs = 12_000))
        )
        show()
        compose.onNodeWithTag("chat.voice.time").assertTextEquals("0:12")
        compose.onNodeWithTag("chat.composer.viewOnce").assertDoesNotExist()
        capture("composer-voice-draft")
        compose.onNodeWithContentDescription("Sprachnachricht entfernen").performClick()
        // A recording the player cannot read shows the error text instead of controls.
        compose.onNodeWithContentDescription("Abspielen").performClick()
        compose.onNodeWithText("Die Sprachnachricht lässt sich nicht abspielen.").assertExists()
        compose.onNodeWithTag("chat.voice.position").assertDoesNotExist()
        assertTrue(calls.contains("remove 1"))
    }

    @Test
    fun picksAFileAndAsksForTheMicrophoneFirst() {
        state = ConversationState(conversation = conversation, canSend = true)
        show()
        assertFalse("spaces alone are nothing to send", ConversationState(draft = "  ").showsSend)
        assertTrue(ConversationState(draft = "Hallo").showsSend)
        assertTrue(ConversationState(drafts = listOf(DraftAttachment(1, AttachmentKind.Image, "image/jpeg", byteArrayOf(1)))).showsSend)
        // One „+“ holds the attachment actions; the round button records while nothing is written or attached.
        compose.onNodeWithContentDescription("Mehr").assertExists()
        compose.onNodeWithTag("chat.composer.send").assertDoesNotExist()
        tapTag("chat.composer.add")
        capture("composer-attach-menu")
        listOf("Foto oder Video", "Datei", "Umfrage", "Effekt", "Einmal ansehen").forEach { compose.onNodeWithText(it).assertExists() }
        tapTag("chat.composer.viewOnce")
        assertTrue(calls.contains("viewOnce true"))
        calls.clear()
        answer = null
        tapTag("chat.composer.attach")
        answer = Uri.parse("content://oasis.test/foto")
        tapTag("chat.composer.add")
        tapTag("chat.composer.attach")
        compose.waitForIdle()
        assertTrue(calls.contains("picked content://oasis.test/foto"))

        calls.clear()
        tapTag("chat.composer.record")
        compose.onNodeWithText("Mikrofon für Sprachnachrichten").assertExists()
        capture("microphone-question")
        compose.onNodeWithText("Nicht jetzt").performClick()
        tapTag("chat.composer.record")
        answer = false
        compose.onNodeWithTag("chat.microphone.continue").performClick()
        compose.onNodeWithTag("chat.composer.microphoneDenied").assertExists()
        tapTag("chat.composer.record")
        answer = true
        compose.onNodeWithTag("chat.microphone.continue").performClick()
        compose.waitForIdle()
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapTag("chat.composer.record")
        compose.waitForIdle()
        assertEquals(listOf("launch RequestPermission", "launch RequestPermission", "record", "record"), calls)
    }

    @Test
    fun filesInMessagesInEveryState() {
        val photo = entry("1", "image", "image/jpeg")
        val voice = entry("2", "audio", "audio/mp4", durationMs = 42_000)
        val video = entry("3", "video", "video/mp4", durationMs = 12_000)
        val pending = item("a", "2", "", listOf(photo, video), listOf(AttachmentState("3", viewOnce = true)))
        val decided = item(
            "b",
            "2",
            "Anhänge",
            listOf(photo, voice, video, entry("4", "image", "image/png")),
            listOf(
                AttachmentState("1", status = AttachmentState.DECLINED),
                AttachmentState("2", status = AttachmentState.VIEWED, viewOnce = true),
                AttachmentState("3", viewOnce = true, status = AttachmentState.ACCEPTED),
                AttachmentState("4", status = AttachmentState.ACCEPTED, available = false)
            )
        )
        val own = item(
            "c",
            "1",
            "Von mir",
            listOf(entry("5", "image", "image/jpeg"), entry("6", "video", "video/mp4"), entry("7", "audio", "audio/mp4")),
            listOf(
                AttachmentState("5", viewOnce = true, status = AttachmentState.SENT),
                AttachmentState("6", viewOnce = true, status = AttachmentState.SENT, available = false),
                AttachmentState("7", status = AttachmentState.SENT)
            )
        )
        val copied = item("d", "2", "", listOf(entry("8", "image", "image/jpeg")), unlisted = setOf("8"))
        state = ConversationState(conversation = conversation, timeline = ChatTimeline(listOf(pending, decided, own, copied)))
        show()
        compose.onNodeWithText("Jonas möchte dir ein Foto schicken (1,4 MB).").assertExists()
        // A file the server does not list for its message: no consent, no loading.
        compose.onAllNodesWithText("Anhang ließ sich nicht prüfen.").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onNodeWithText("Jonas möchte dir ein Video schicken (1,4 MB).").assertExists()
        compose.onNodeWithText("Einmal ansehen: Du kannst es nur einmal ansehen, danach ist es weg.").assertExists()
        compose.onNodeWithText("Foto · 1,4 MB · Abgelehnt").assertExists()
        compose.onNodeWithText("Sprachnachricht · 0:42 · Angesehen").assertExists()
        compose.onNodeWithText("Foto · 1,4 MB · Gelöscht").assertExists()
        compose.onNodeWithText("Foto · 1,4 MB · Einmal ansehen").assertExists()
        compose.onNodeWithText("Video · 1,4 MB · Für alle gelöscht").assertExists()
        capture("messages-states")
        tapTag("chat.attachment.accept")
        tapTag("chat.attachment.decline", 1)
        tapTag("chat.attachment.openViewOnce")
        compose.waitForIdle()
        assertTrue(calls.containsAll(listOf("accept 1", "decline 3", "open 3", "load 7")))
    }

    @Test
    fun acceptedFilesAreShownAndOpenFullScreen() {
        val files = listOf(
            entry("1", "image", "image/jpeg"),
            entry("2", "audio", "audio/mp4", durationMs = 42_000),
            entry("3", "video", "video/mp4"),
            entry("4", "video", "video/webm"),
            entry("5", "image", "image/jpeg"),
            entry("6", "image", "image/jpeg"),
            entry("7", "image", "image/jpeg")
        )
        val accepted = files.map { AttachmentState(it.id, status = AttachmentState.ACCEPTED) }
        val voiceFile = File(application.cacheDir, "stimme.m4a").apply { writeBytes(byteArrayOf(1)) }
        val videoFile = File(application.cacheDir, "video.mp4").apply { writeBytes(byteArrayOf(1)) }
        val views = mapOf(
            "1" to AttachmentView.Photo(image),
            "2" to AttachmentView.Playable(voiceFile, null),
            "3" to AttachmentView.Playable(videoFile, image),
            "4" to AttachmentView.Playable(videoFile, null),
            "5" to AttachmentView.Failed("Anhang ließ sich nicht laden.", retry = true),
            "6" to AttachmentView.Failed("Anhang ließ sich nicht prüfen."),
            "7" to AttachmentView.Loading
        )
        state = ConversationState(
            conversation = conversation,
            timeline = ChatTimeline(listOf(item("a", "2", "Alles da", files, accepted))),
            attachmentViews = views
        )
        show()
        compose.onNodeWithText("Foto · 1,4 MB · wird entschlüsselt …").assertExists()
        compose.onNodeWithText("Anhang ließ sich nicht prüfen.").assertExists()
        capture("messages-shown")
        compose.onNodeWithText("Noch einmal laden").performScrollTo().performClick()
        compose.onAllNodes(hasContentDescription("Abspielen")).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Die Sprachnachricht lässt sich nicht abspielen.").assertExists()

        tapTag("chat.attachment.photo")
        compose.onNodeWithTag("chat.attachment.fullScreen").assertExists()
        capture("photo-full-screen")
        compose.onNodeWithTag("chat.attachment.close").performClick()
        tapTag("chat.attachment.video")
        compose.onNodeWithTag("chat.attachment.player").assertExists()
        failVideo()
        compose.onNodeWithText("Das Video lässt sich nicht abspielen.").assertExists()
        compose.onNodeWithTag("chat.attachment.close").performClick()
        tapTag("chat.attachment.video", 1)
        compose.onNodeWithTag("chat.attachment.close").performClick()
        compose.waitForIdle()
        assertTrue(calls.containsAll(listOf("load 5 retry", "load 1")))
    }

    /** The platform video view in the open window reports a file it cannot play. */
    private fun failVideo() {
        compose.waitForIdle()
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val roots = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
        fun find(view: View): VideoView? = view as? VideoView ?: (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
        }
        val player = roots.filterIsInstance<View>().firstNotNullOf { find(it) }
        shadowOf(player).onErrorListener.onError(MediaPlayer(), 1, 0)
        compose.waitForIdle()
    }

    @Test
    fun theViewOnceViewerOffersReportingAndClosing() {
        val photo = entry("1", "image", "image/jpeg")
        val message = item("a", "2", "", listOf(photo))
        var viewer by mutableStateOf(ViewOnceViewer(message, photo, AttachmentView.Photo(image)))
        show { ViewOnceViewer(viewer, actions) { calls += "report ${it.signature}" } }
        compose.onNodeWithTag("chat.viewOnce.note").assertExists()
        capture("view-once")
        compose.onNodeWithTag("chat.viewOnce.report").performClick()
        compose.onNodeWithTag("chat.attachment.close").performClick()
        val voice = entry("2", "audio", "audio/mp4")
        viewer = ViewOnceViewer(message, voice, AttachmentView.Playable(File(application.cacheDir, "x.m4a"), null))
        compose.onNodeWithText("Sprachnachricht").assertExists()
        compose.waitForIdle()
        assertEquals(listOf("report a", "close"), calls)
    }

    @Test
    fun theReportSaysThatTheFilesGoAlong() {
        val only = de.velwebsolutions.oasis.chat.ui.ReportModel(
            ChatTestSupport().member(1),
            "7",
            item("a", "2", "", listOf(entry("1", "image", "image/jpeg"))),
            "Jonas"
        )
        assertEquals(ReportState(quote = "Jonas: (nur Anhang)", attachments = 1), only.state.value)
        show { ReportContent(ReportState(quote = "Jonas: (nur Anhang)", attachments = 2), {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("chat.report.attachments").assertExists()
        compose.onNode(hasText("Jonas: (nur Anhang)")).assertExists()
        compose.onNode(
            hasText("Die 2 Anhänge dieser Nachricht (Fotos, Sprachnachrichten, Videos oder Dateien) gehen mit an das Moderationsteam.")
        ).assertExists()
        assertNull(de.velwebsolutions.oasis.chat.ui.ReportModel.attachmentNote(0))
        assertEquals(
            "Der Anhang dieser Nachricht (Foto, Sprachnachricht, Video oder Datei) geht mit an das Moderationsteam.",
            de.velwebsolutions.oasis.chat.ui.ReportModel.attachmentNote(1)
        )
        capture("report")
    }

    private fun clearFileProvider() = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {
        isAccessible = true
    }.let {
        (it.get(null) as MutableMap<*, *>).clear()
    }

    @Test
    fun filesArePickedOfferedAndHandedToOtherApps() {
        clearFileProvider()
        val pdf = MessageAttachment("8", "file", "application/pdf", 245_760, "k", "n", name = "Rechnung.pdf")
        val document = File(application.cacheDir, "chat-attachments/datei-1/Rechnung.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val pending = item("a", "2", "", listOf(pdf))
        val accepted = item("b", "2", "Hier", listOf(pdf.copy(id = "9")), listOf(AttachmentState("9", status = AttachmentState.ACCEPTED)))
        state = ConversationState(
            conversation = conversation,
            canSend = true,
            timeline = ChatTimeline(listOf(pending, accepted)),
            attachmentViews = mapOf("9" to AttachmentView.Document(document, "application/pdf")),
            drafts = listOf(DraftAttachment(1, AttachmentKind.File, "application/zip", byteArrayOf(1), name = "Fotos.zip"))
        )
        show()
        compose.onNodeWithText("Jonas möchte dir eine Datei schicken: Rechnung.pdf (240 KB).").assertExists()
        compose.onNodeWithText("Öffne Dateien nur von Menschen, denen du vertraust.").assertExists()
        compose.onNodeWithText("Fotos.zip · 1 KB").assertExists()
        compose.onNodeWithText("Rechnung.pdf · 240 KB").assertExists()
        capture("files")
        // A file is attached: the round button sends.
        compose.onNodeWithTag("chat.composer.record").assertDoesNotExist()
        compose.onNodeWithTag("chat.composer.send").assertIsEnabled()
        answer = null
        tapTag("chat.composer.add")
        tapTag("chat.composer.file")
        answer = Uri.parse("content://oasis.test/rechnung")
        tapTag("chat.composer.add")
        tapTag("chat.composer.file")
        compose.onNodeWithText("Öffnen").performScrollTo().performClick()
        compose.onNodeWithText("Teilen").performScrollTo().performClick()
        compose.onNodeWithText("Auf deinem Gerät ist keine App, die diese Datei öffnen kann.").assertDoesNotExist()
        shadowOf(application).checkActivities(true)
        compose.onNodeWithText("Teilen").performScrollTo().performClick()
        compose.onNodeWithText("Auf deinem Gerät ist keine App, die diese Datei öffnen kann.").assertExists()
        compose.onNodeWithText("Öffnen").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(calls.containsAll(listOf("launch OpenDocument", "file content://oasis.test/rechnung", "load 9")))
    }

    /** A lifecycle the test moves, like leaving Oasis for another app and coming back. */
    private class TestLifecycle : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @Test
    fun aViewOnceFileOpensOnceInAnotherAppAndCountsAsViewedOnReturn() {
        clearFileProvider()
        val pdf = MessageAttachment("8", "file", "application/pdf", 10, "k", "n", name = "Geheim.pdf")
        val document = File(application.cacheDir, "chat-attachments/datei-2/Geheim.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val lifecycle = TestLifecycle()
        var viewer by mutableStateOf<ViewOnceViewer?>(
            ViewOnceViewer(item("a", "2", "", listOf(pdf)), pdf, AttachmentView.Document(document, "application/pdf"))
        )
        show {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycle) {
                viewer?.let { ViewOnceViewer(it, actions) { calls += "report" } }
            }
        }
        compose.waitForIdle()
        assertEquals("opening it in another app does not close it yet", emptyList<String>(), calls)
        compose.runOnUiThread {
            lifecycle.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
            lifecycle.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            lifecycle.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
            lifecycle.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals("closed once on the return", listOf("close"), calls)

        shadowOf(application).checkActivities(true)
        viewer = ViewOnceViewer(item("b", "2", "", listOf(pdf)), pdf, AttachmentView.Document(document, "application/pdf"))
        compose.waitForIdle()
        assertEquals(listOf("close", "cancelViewOnce"), calls)
    }

    @Test
    fun shareTargetStates() {
        var share by mutableStateOf(de.velwebsolutions.oasis.core.share.Share(emptyList(), "Ein geteilter Text"))
        var shown by mutableStateOf(de.velwebsolutions.oasis.chat.ui.ShareTargetState())
        show { de.velwebsolutions.oasis.chat.ui.ShareTargetContent(shown, share, "1", { calls += "pick" }, { calls += "cancel" }) }
        compose.onNodeWithText("„Ein geteilter Text“").assertExists()
        capture("share-loading")
        shown = de.velwebsolutions.oasis.chat.ui.ShareTargetState(error = "Da ist etwas schiefgegangen.")
        compose.onNodeWithText("Da ist etwas schiefgegangen.").assertExists()
        shown = de.velwebsolutions.oasis.chat.ui.ShareTargetState(loaded = true)
        compose.onNodeWithText("Du hast noch keine Chats.").assertExists()
        shown = de.velwebsolutions.oasis.chat.ui.ShareTargetState(listOf(conversation.copy(title = "Garten")), loaded = true)
        tapTag("chat.share.conversation")
        tapTag("chat.share.cancel")
        assertEquals(listOf("pick", "cancel"), calls)
        share = de.velwebsolutions.oasis.core.share.Share(emptyList(), null)
        compose.onNodeWithText("„Ein geteilter Text“").assertDoesNotExist()
        compose.onNodeWithTag("chat.share.summary").assertExists()
    }
}
