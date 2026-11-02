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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.chat.api.AttachmentState
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.core.AttachmentKind
import de.velwebsolutions.oasis.chat.core.AttachmentView
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.core.DraftAttachment
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationState
import de.velwebsolutions.oasis.chat.ui.ReportContent
import de.velwebsolutions.oasis.chat.ui.ReportState
import de.velwebsolutions.oasis.chat.ui.ViewOnceViewer
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.design.theme.OasisTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
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
        tapTag("chat.composer.viewOnce", 0)
        tapTag("chat.composer.removeDraft", 2)
        compose.onNode(hasContentDescription("Video entfernen")).assertExists()
        state = state.copy(preparing = false, uploadProgress = 0.4f)
        compose.onNode(hasContentDescription("Anhänge werden hochgeladen, 40 Prozent")).assertExists()
        compose.onNodeWithText("Wird verschlüsselt und hochgeladen … 40 %").assertExists()
        capture("composer-progress")
        state = ConversationState(conversation = conversation, canSend = true, recordingSeconds = 75, draft = "Hör mal")
        assertTrue("no sending while recording", !state.canSubmit)
        compose.onNodeWithText("Aufnahme 1:15 / 3:00").assertExists()
        capture("composer-recording")
        tapTag("chat.composer.cancelRecording")
        tapTag("chat.composer.finishRecording")
        compose.waitForIdle()
        assertEquals(listOf("viewOnce 1 true", "remove 3", "cancel", "finish"), calls)
    }

    @Test
    fun picksAFileAndAsksForTheMicrophoneFirst() {
        state = ConversationState(conversation = conversation, canSend = true)
        show()
        answer = null
        tapTag("chat.composer.attach")
        answer = Uri.parse("content://oasis.test/foto")
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
        compose.onNode(hasContentDescription("Sprachnachricht abspielen")).performScrollTo().performClick()
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
        answer = null
        tapTag("chat.composer.file")
        answer = Uri.parse("content://oasis.test/rechnung")
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
        val share = de.velwebsolutions.oasis.core.share.Share(emptyList(), "Ein geteilter Text")
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
    }
}
