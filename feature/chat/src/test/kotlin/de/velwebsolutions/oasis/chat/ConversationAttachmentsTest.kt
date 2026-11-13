package de.velwebsolutions.oasis.chat

import android.net.Uri
import de.velwebsolutions.oasis.chat.api.AttachmentState
import de.velwebsolutions.oasis.chat.core.AndroidChatMedia
import de.velwebsolutions.oasis.chat.core.AttachmentKind
import de.velwebsolutions.oasis.chat.core.AttachmentProblem
import de.velwebsolutions.oasis.chat.core.AttachmentView
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.core.DraftAttachment
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.core.share.PendingShare
import de.velwebsolutions.oasis.core.share.Share
import de.velwebsolutions.oasis.core.share.SharedFile
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Photos, voice messages and videos in a conversation (docs/oasis-chat.md, "Anhänge"): encrypted upload in parts with
 * progress, consent before any download, view once, and every failure the composer and the bubbles explain.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationAttachmentsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport().apply { server.partBytes = 1_000 }
    private val server get() = support.server
    private val miraMedia = FakeChatMedia()
    private val jonasMedia = FakeChatMedia()
    private val picked = Uri.parse("content://oasis.test/1")

    private val photo = ByteArray(2_500) { it.toByte() }
    private val clip = ByteArray(1_200) { (it * 3).toByte() }

    private fun model(context: ChatContext, id: String, realtime: FakeRealtime = FakeRealtime()) =
        ConversationViewModel(id, context, realtime).apply {
            pollInterval = Duration.INFINITE
            sweepInterval = Duration.INFINITE
            recordingTick = Duration.INFINITE
        }

    private suspend fun chat(): Triple<ChatContext, ChatContext, String> {
        val mira = support.member(1, media = miraMedia)
        val jonas = support.member(2, media = jonasMedia)
        val id = mira.repository.createConversation("direct", null, listOf("2"), 7).id
        model(mira, id).open()
        return Triple(mira, jonas, id)
    }

    /** Mira sends "Schau mal" with a photo, a voice message and a view-once video. */
    private suspend fun sendThree(writer: ConversationViewModel) {
        miraMedia.prepared = { _, id -> DraftAttachment(id, AttachmentKind.Image, "image/jpeg", photo, 4, 3) }
        writer.addPicked(picked)
        writer.startRecording()
        assertEquals(0, writer.state.value.recordingSeconds)
        assertFalse("no second file while recording", writer.state.value.canAttach)
        writer.finishRecording()
        miraMedia.prepared = { _, id -> DraftAttachment(id, AttachmentKind.Video, "video/mp4", clip, durationMs = 12_000) }
        writer.addPicked(picked)
        val video = writer.state.value.drafts.last()
        writer.setViewOnce(video.localId, true)
        writer.setDraft("Schau mal")
        writer.send()
    }

    private suspend fun waitFor(done: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            while (!done()) delay(10)
        }
    }

    private fun message(model: ConversationViewModel): ChatMessageItem = model.state.value.timeline.items.last()

    private fun files(model: ConversationViewModel): List<MessageAttachment> = message(model).content.attachments

    @Test
    fun filesGoEncryptedInPartsAndOpenOnlyAfterConsent() = runTest {
        val (mira, jonas, id) = chat()
        val writer = model(mira, id)
        writer.open()
        assertTrue(writer.state.value.canAttach)
        assertFalse(writer.state.value.canSubmit)
        sendThree(writer)

        val sent = writer.state.value
        assertTrue("the composer is empty again", sent.drafts.isEmpty())
        assertNull(sent.uploadProgress)
        assertEquals("", sent.draft)
        assertEquals(3, server.uploads.size)
        assertEquals("2516 bytes of ciphertext in parts of 1000", listOf(3, 1, 2), server.uploads.values.map { it.parts })
        assertEquals(listOf(false, false, true), server.uploads.values.map { it.viewOnce })
        val own = message(writer)
        assertEquals("Schau mal", own.content.text)
        assertEquals(listOf("image", "audio", "video"), own.content.attachments.map { it.kind })
        assertEquals(AttachmentState.SENT, own.state(own.content.attachments[0].id, mine = true).status)
        assertEquals("own files stay on screen, except view once", 2, sent.attachmentViews.size)
        assertEquals(listOf(AttachmentKind.Image, AttachmentKind.Audio), miraMedia.presented.map { it.first })
        assertFalse("the server never sees the plaintext", server.messages.values.flatten().any { it.second.toString().contains("Schau") })

        val reader = model(jonas, id)
        reader.open()
        val item = message(reader)
        val (image, voice, video) = files(reader)
        assertEquals(AttachmentState.PENDING, item.state(image.id, mine = false).status)
        assertEquals(12_000L, video.durationMs)

        reader.loadAttachment(image)
        assertEquals(
            "nothing comes down before consent",
            AttachmentView.Failed("Diesen Anhang gibt es nicht mehr."),
            reader.state.value.attachmentViews[image.id]
        )
        reader.answer(item, image, accept = true)
        assertEquals(AttachmentState.ACCEPTED, message(reader).state(image.id, false).status)
        reader.loadAttachment(image, retry = true)
        assertTrue(reader.state.value.attachmentViews[image.id] is AttachmentView.Playable)
        assertArrayEquals(photo, jonasMedia.presented.single().second)
        reader.loadAttachment(image)
        reader.loadAttachment(image, retry = true)
        assertEquals("a shown file is not loaded again", 1, jonasMedia.presented.size)

        reader.answer(message(reader), voice, accept = false)
        assertEquals(AttachmentState.DECLINED, message(reader).state(voice.id, false).status)
        reader.answer(message(reader), voice, accept = true)
        assertEquals("Diesen Anhang gibt es nicht mehr.", reader.state.value.actionError)
        reader.dismissActionError()

        reader.answer(message(reader), video, accept = true)
        val accepted = message(reader).state(video.id, false)
        assertTrue(accepted.viewOnce)
        reader.openViewOnce(message(reader), video)
        val viewer = checkNotNull(reader.state.value.viewOnce)
        assertEquals(video, viewer.attachment)
        assertArrayEquals(clip, jonasMedia.presented.last().second)
        reader.openViewOnce(message(reader), video)
        reader.closeViewOnce()
        assertNull(reader.state.value.viewOnce)
        assertEquals(listOf(viewer.view), jonasMedia.discarded)
        assertEquals(AttachmentState.VIEWED, message(reader).state(video.id, false).status)
        reader.closeViewOnce()
        assertTrue("viewed by everyone: deleted from the server", server.uploads.values.last().deleted)

        writer.poll()
        assertFalse(message(writer).state(video.id, true).available)
        writer.poll()

        // Reopened, the sender downloads their own files again, but never a view-once one.
        writer.open()
        writer.loadAttachment(image)
        assertTrue(writer.state.value.attachmentViews[image.id] is AttachmentView.Playable)
        assertArrayEquals(photo, miraMedia.presented.last().second)
        writer.loadAttachment(video)
        assertEquals(AttachmentView.Failed("Diesen Anhang gibt es nicht mehr."), writer.state.value.attachmentViews[video.id])
        clear(reader)
        clear(writer)
    }

    @Test
    fun downloadsThatFailSayWhy() = runTest {
        val (mira, jonas, id) = chat()
        val writer = model(mira, id)
        writer.open()
        sendThree(writer)
        val reader = model(jonas, id)
        reader.open()
        val (image, voice, video) = files(reader)
        val item = message(reader)
        reader.answer(item, image, true)
        reader.answer(message(reader), voice, true)

        server.tampered[image.id.toInt()] = ByteArray(2_516)
        reader.loadAttachment(image)
        assertEquals(AttachmentView.Failed(AndroidChatMedia.UNVERIFIABLE), reader.state.value.attachmentViews[image.id])
        server.failures["GET conversations/$id/attachments/${voice.id}"] = 500 to """{"message":"kaputt"}"""
        reader.loadAttachment(voice)
        assertEquals(AttachmentView.Failed("Anhang ließ sich nicht laden.", retry = true), reader.state.value.attachmentViews[voice.id])

        // Leaving the chat while a file comes down: it is thrown away.
        server.onRequest = { if (it == "GET conversations/$id/attachments/${voice.id}") reader.close() }
        reader.loadAttachment(voice, retry = true)
        assertTrue(jonasMedia.discarded.isNotEmpty())
        assertTrue(reader.state.value.attachmentViews.isEmpty())
        server.onRequest = {}

        reader.open()
        reader.openViewOnce(message(reader), video)
        assertEquals("not accepted yet", "Diesen Anhang gibt es nicht mehr.", reader.state.value.actionError)
        reader.answer(message(reader), video, true)
        server.onRequest = { if (it == "GET conversations/$id/attachments/${video.id}") reader.close() }
        reader.openViewOnce(message(reader), video)
        assertNull(reader.state.value.viewOnce)
        server.onRequest = {}

        reader.open()
        reader.openViewOnce(message(reader), video)
        server.failures["POST conversations/$id/attachments/${video.id}/viewed"] = 500 to """{"message":"kaputt"}"""
        reader.closeViewOnce()
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", reader.state.value.actionError)
        server.failures["POST conversations/$id/attachments/${image.id}/decline"] = 500 to """{"message":"kaputt"}"""
        reader.answer(message(reader), image, false)
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", reader.state.value.actionError)

        // Closing the chat with the viewer open counts as viewed.
        reader.openViewOnce(message(reader), video)
        assertNotNull(reader.state.value.viewOnce)
        reader.close()
        waitFor { server.uploads.getValue(video.id.toInt()).answers[2] == "viewed" }
        // An answer that arrives after the chat closed changes nothing on screen.
        reader.answer(item, voice, accept = false)
        assertTrue(reader.state.value.timeline.items.isEmpty())
        clear(reader)
        clear(writer)
    }

    @Test
    fun theComposerExplainsWhatCannotBeAttached() = runTest {
        val (mira, _, id) = chat()
        val writer = model(mira, id)
        writer.open()
        miraMedia.prepared = { _, _ -> throw AttachmentProblem("Das Foto ist zu groß. Höchstens 10 MB gehen.") }
        writer.addPicked(picked)
        assertEquals("Das Foto ist zu groß. Höchstens 10 MB gehen.", writer.state.value.composerError)
        assertFalse(writer.state.value.preparing)

        miraMedia.recordingStarts = false
        writer.startRecording()
        assertEquals("Die Aufnahme ließ sich nicht starten. Versuch es noch einmal.", writer.state.value.composerError)
        miraMedia.recordingStarts = true
        miraMedia.recorded = null
        writer.finishRecording()
        writer.startRecording()
        writer.startRecording()
        writer.finishRecording()
        assertEquals("Die Aufnahme ist leer. Versuch es noch einmal.", writer.state.value.composerError)

        miraMedia.recorded = byteArrayOf(1)
        writer.recordingTick = 10.milliseconds
        writer.startRecording()
        mainRule.dispatcher.scheduler.advanceTimeBy(25)
        mainRule.dispatcher.scheduler.runCurrent()
        assertEquals(2, writer.state.value.recordingSeconds)
        writer.cancelRecording()
        assertNull(writer.state.value.recordingSeconds)
        assertEquals(1, miraMedia.cancels)
        writer.recordingTick = Duration.INFINITE
        writer.startRecording()
        checkNotNull(miraMedia.onLimit).invoke()
        waitFor { writer.state.value.recordingSeconds == null }
        assertEquals("the 3-minute limit ends the recording", 1, writer.state.value.drafts.size)

        miraMedia.prepared = { _, id -> DraftAttachment(id, AttachmentKind.Image, "image/jpeg", photo) }
        repeat(3) { writer.addPicked(picked) }
        writer.setViewOnce(writer.state.value.drafts.first().localId, true)
        assertFalse(writer.state.value.canAttach)
        writer.addPicked(picked)
        assertEquals("Höchstens 4 Anhänge pro Nachricht.", writer.state.value.composerError)
        writer.startRecording()
        assertNull(writer.state.value.recordingSeconds)
        writer.removeDraft(writer.state.value.drafts.first().localId)
        assertEquals(3, writer.state.value.drafts.size)

        writer.setDraft("a".repeat(3_990))
        assertTrue("the files' metadata shortens the text", writer.state.value.tooLong)
        writer.send()
        assertTrue(server.uploads.isEmpty())
        writer.setDraft("Mit Fotos")
        server.failures["POST conversations/$id/attachments"] =
            422 to """{"message":"x","errors":{"size":["Du hast heute schon sehr viele Anhänge geschickt. Morgen geht es weiter."]}}"""
        writer.send()
        assertEquals("Du hast heute schon sehr viele Anhänge geschickt. Morgen geht es weiter.", writer.state.value.composerError)
        assertNull(writer.state.value.uploadProgress)
        assertEquals("the files stay for another try", 3, writer.state.value.drafts.size)
        writer.close()
        assertTrue(writer.state.value.drafts.isEmpty())
        assertTrue(miraMedia.clears > 0)
        clear(writer)
    }

    @Test
    fun realtimeMessagesWithFilesFetchTheirStates() = runTest {
        val (mira, jonas, id) = chat()
        val live = FakeRealtime(connected = true)
        val reader = model(jonas, id, live)
        reader.open()
        val writer = model(mira, id)
        writer.open()
        miraMedia.prepared = { _, localId -> DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", photo) }
        writer.addPicked(picked)
        writer.send()
        val envelope = Json.decodeFromString(MessageEnvelope.serializer(), server.messages.getValue(id.toInt()).last().second.toString())
        live.events.emit(RealtimeEvent(".MessageSent", envelope.toJsonObject()))
        waitFor { message(reader).attachmentStates.isNotEmpty() }
        assertEquals(AttachmentState.PENDING, message(reader).attachmentStates.values.single().status)

        writer.addPicked(picked)
        writer.send()
        val second = Json.decodeFromString(MessageEnvelope.serializer(), server.messages.getValue(id.toInt()).last().second.toString())
        server.failures["GET conversations/$id/messages"] = 500 to """{"message":"kaputt"}"""
        live.events.emit(RealtimeEvent(".MessageSent", second.toJsonObject()))
        waitFor { reader.state.value.timeline.items.size == 2 && server.failures.isEmpty() }
        assertTrue("states stay unknown after a failed reload", message(reader).attachmentStates.isEmpty())
        assertEquals(1, MessagePayload.decode(message(reader).plaintext).attachments.size)
        clear(reader)
        clear(writer)
    }

    @Test
    fun filesAndSharesBecomeDraftsAndViewOnceFilesNeedAnApp() = runTest {
        val (mira, jonas, id) = chat()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        fun shared(name: String) =
            SharedFile(java.io.File(context.cacheDir, name).apply { writeBytes(byteArrayOf(1, 2)) }, name, "application/pdf")
        val copies = listOf("a.pdf", "kaputt.pdf", "b.pdf", "c.pdf", "d.pdf", "e.pdf").map(::shared)
        PendingShare.offer(Share(copies, "Geteilt aus einer anderen App"))
        PendingShare.target(id)
        val writer = model(mira, id)
        writer.open()
        assertEquals("Geteilt aus einer anderen App", writer.state.value.draft)
        assertEquals(listOf("a.pdf", "b.pdf", "c.pdf", "d.pdf"), writer.state.value.drafts.map { it.name })
        // The file beyond the four keeps waiting for the next "Teilen mit …" (iOS takeShares); the others are gone.
        assertEquals(
            "Du kannst höchstens 4 Anhänge an eine Nachricht hängen. Den übrigen bietet dir Mildcrowd wieder unter „Teilen mit …“ an, " +
                "wenn du die App das nächste Mal öffnest.",
            writer.state.value.composerError
        )
        assertEquals(listOf("e.pdf"), PendingShare.share.value?.files?.map { it.name })
        assertTrue("the copies taken are gone", copies.dropLast(1).none { it.file.exists() })
        assertTrue(copies.last().file.exists())
        PendingShare.discard()
        assertFalse(copies.last().file.exists())

        // Two left over while a newer share came in: the newer one wins, the rest is deleted.
        val many = listOf("g.pdf", "h.pdf", "i.pdf", "j.pdf", "k.pdf", "l.pdf").map(::shared)
        val newer = Share(listOf(shared("n.pdf")), null)
        PendingShare.offer(Share(many, null))
        PendingShare.target(id)
        jonasMedia.beforeShared = {}
        miraMedia.beforeShared = { if (PendingShare.share.value == null) PendingShare.offer(newer) }
        writer.open()
        miraMedia.beforeShared = {}
        assertTrue(writer.state.value.composerError.orEmpty().contains("Die übrigen 2 bietet dir Mildcrowd wieder"))
        assertTrue(many.none { it.file.exists() })
        assertSame(newer, PendingShare.share.value)
        PendingShare.discard()
        writer.open()

        // A share without text, for another chat, waits.
        PendingShare.offer(Share(listOf(shared("f.pdf")), null))
        PendingShare.target("999")
        writer.open()
        assertTrue(writer.state.value.drafts.isEmpty())
        PendingShare.target(id)
        writer.open()
        assertEquals(listOf("f.pdf"), writer.state.value.drafts.map { it.name })
        assertEquals("", writer.state.value.draft)
        assertNull(writer.state.value.composerError)

        writer.addPicked(picked, asFile = true)
        val file = writer.state.value.drafts.last()
        assertEquals("Rechnung.pdf", file.name)
        writer.setViewOnce(file.localId, true)
        writer.removeDraft(writer.state.value.drafts.first().localId)
        writer.send()
        val entry = message(writer).content.attachments.single()
        assertEquals(listOf("file", "Rechnung.pdf"), listOf(entry.kind, entry.name))

        val reader = model(jonas, id)
        reader.open()
        reader.cancelViewOnce()
        reader.answer(message(reader), entry, true)
        reader.openViewOnce(message(reader), entry)
        val viewer = checkNotNull(reader.state.value.viewOnce)
        reader.cancelViewOnce()
        assertNull(reader.state.value.viewOnce)
        assertEquals(ConversationViewModel.NO_APP, reader.state.value.actionError)
        assertEquals(listOf(viewer.view), jonasMedia.discarded)
        assertEquals("not viewed: it can be opened again", "accepted", server.uploads.values.last().answers[2])
        clear(reader)
        clear(writer)
    }

    @Test
    fun aMessageThatWentOutIsSentEvenWhenItsPreviewCannotBeWritten() = runTest {
        val (mira, _, id) = chat()
        val writer = model(mira, id)
        writer.open()
        miraMedia.prepared = { _, localId -> DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", photo) }
        writer.addPicked(picked)
        writer.setDraft("Mit Foto")
        miraMedia.presentFailure = java.io.IOException("Kein Speicherplatz mehr")
        writer.send()
        miraMedia.presentFailure = null
        val sent = writer.state.value
        assertNull("no failure for a message that went out", sent.composerError)
        assertTrue(sent.drafts.isEmpty())
        assertEquals("", sent.draft)
        assertEquals("Mit Foto", message(writer).content.text)
        assertEquals(1, server.messages.getValue(id.toInt()).size)
        assertTrue("the photo comes down later like a recipient's", sent.attachmentViews.isEmpty())
        clear(writer)
    }

    @Test
    fun aShareWaitsWhenTheChatClosesWhileItOpens() = runTest {
        val (mira, _, id) = chat()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        fun shared(name: String) =
            SharedFile(java.io.File(context.cacheDir, name).apply { writeBytes(byteArrayOf(1, 2)) }, name, "application/pdf")
        val writer = model(mira, id)

        // Left while the chat is marked as read: the share is not taken yet.
        val share = Share(listOf(shared("a.pdf")), "Aus einer anderen App")
        PendingShare.offer(share)
        PendingShare.target(id)
        server.onRequest = { if (it == "POST conversations/$id/read") writer.close() }
        writer.open()
        server.onRequest = {}
        assertSame(share, PendingShare.share.value)
        assertTrue(writer.state.value.drafts.isEmpty())

        // Left while its files are read: the share waits again, with its copies, and the closed chat keeps nothing.
        miraMedia.beforeShared = { writer.close() }
        writer.open()
        miraMedia.beforeShared = {}
        assertSame(share, PendingShare.share.value)
        assertTrue(share.files.single().file.exists())
        assertTrue(writer.state.value.drafts.isEmpty())
        assertEquals("", writer.state.value.draft)

        // The next opening takes it.
        writer.open()
        assertEquals(listOf("a.pdf"), writer.state.value.drafts.map { it.name })
        assertEquals("Aus einer anderen App", writer.state.value.draft)
        assertNull(PendingShare.share.value)
        assertFalse(share.files.single().file.exists())

        // A newer share that came in meanwhile wins over the one the closed chat was reading.
        val older = Share(listOf(shared("b.pdf")), null)
        val newer = Share(listOf(shared("c.pdf")), null)
        PendingShare.offer(older)
        PendingShare.target(id)
        miraMedia.beforeShared = {
            PendingShare.offer(newer)
            writer.close()
        }
        writer.open()
        miraMedia.beforeShared = {}
        assertSame(newer, PendingShare.share.value)
        assertFalse(older.files.single().file.exists())
        PendingShare.discard()
        clear(writer)
    }

    @Test
    fun aLockDeletesTheDecryptedFilesAndEndsAnOpenViewOnceFile() = runTest {
        val (mira, jonas, id) = chat()
        val writer = model(mira, id)
        writer.open()
        sendThree(writer)
        val reader = model(jonas, id)
        reader.open()
        val video = files(reader)[2]
        reader.answer(message(reader), video, true)
        reader.openViewOnce(message(reader), video)
        assertNotNull(reader.state.value.viewOnce)
        val cleared = jonasMedia.clears
        // The app goes to the background with the viewer open.
        jonas.vault.lockForBackground()
        mainRule.dispatcher.scheduler.runCurrent()
        waitFor { server.uploads.getValue(video.id.toInt()).answers[2] == "viewed" }
        assertNull(reader.state.value.viewOnce)
        assertTrue(jonasMedia.clears > cleared)
        clear(reader)
        clear(writer)
    }

    @Test
    fun aViewOnceFileShownOnceIsNeverOfferedAgainEvenWhenViewedFails() = runTest {
        val (mira, jonas, id) = chat()
        val writer = model(mira, id)
        writer.open()
        miraMedia.prepared = { _, localId -> DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", photo, 4, 3) }
        writer.addPicked(picked)
        writer.setViewOnce(writer.state.value.drafts.single().localId, true)
        writer.send()
        val entry = message(writer).content.attachments.single()

        val reader = model(jonas, id)
        reader.open()
        reader.answer(message(reader), entry, true)
        reader.openViewOnce(message(reader), entry)
        assertNotNull(reader.state.value.viewOnce)
        server.failures["POST conversations/$id/attachments/${entry.id}/viewed"] = 500 to """{"message":"kaputt"}"""
        reader.closeViewOnce()
        assertEquals("shown is shown", AttachmentState.VIEWED, message(reader).state(entry.id, mine = false).status)
        assertEquals("accepted", server.uploads.getValue(entry.id.toInt()).answers[2])

        // Opened again: the server still says accepted, the app does not offer it and repeats the answer.
        reader.open()
        assertEquals(AttachmentState.VIEWED, message(reader).state(entry.id, mine = false).status)
        waitFor { server.uploads.getValue(entry.id.toInt()).answers[2] == "viewed" }
        clear(reader)
        clear(writer)
    }

    @Test
    fun aSendThatFinishesAfterALockWritesNothingToTheDevice() = runTest {
        val (mira, _, id) = chat()
        val writer = model(mira, id)
        writer.open()
        miraMedia.prepared = { _, localId -> DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", photo, 4, 3) }
        writer.addPicked(picked)
        server.onRequest = { if (it == "POST conversations/$id/messages") mira.vault.lock(announce = false) }
        writer.send()
        server.onRequest = {}
        assertEquals("sent", 1, server.messages.getValue(id.toInt()).size)
        assertTrue("no decrypted copy written", miraMedia.presented.isEmpty())

        // A send that fails after the lock reports nothing to the closed chat either.
        assertTrue(mira.keyring.unlock(checkNotNull(mira.vault.storedIdentity), TEST_PASSPHRASE))
        writer.open()
        writer.setDraft("Noch eins")
        server.failures["POST conversations/$id/messages"] = 500 to """{"message":"kaputt"}"""
        server.onRequest = { if (it == "POST conversations/$id/messages") mira.vault.lock(announce = false) }
        writer.send()
        server.onRequest = {}
        assertNull(writer.state.value.composerError)
        clear(writer)
    }

    @Test
    fun aViewOnceFileOpenWhenTheChatLocksCountsAsViewed() = runTest {
        val (mira, jonas, id) = chat()
        val writer = model(mira, id)
        writer.open()
        miraMedia.prepared = { _, localId -> DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", photo, 4, 3) }
        writer.addPicked(picked)
        writer.setViewOnce(writer.state.value.drafts.single().localId, true)
        writer.send()
        val entry = message(writer).content.attachments.single()
        val reader = model(jonas, id)
        reader.open()
        reader.answer(message(reader), entry, true)
        reader.openViewOnce(message(reader), entry)
        server.failures["POST conversations/$id/attachments/${entry.id}/viewed"] = 500 to """{"message":"kaputt"}"""
        reader.close()
        assertTrue("$id|${entry.id}" in jonas.vault.viewedOnce)
        clear(reader)
        clear(writer)
    }

    @Test
    fun anAttachmentTheServerDoesNotListForTheMessageIsNeverOffered() = runTest {
        val (mira, jonas, id) = chat()
        val writer = model(mira, id)
        writer.open()
        miraMedia.prepared = { _, localId -> DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", photo, 4, 3) }
        writer.addPicked(picked)
        writer.setViewOnce(writer.state.value.drafts.single().localId, true)
        writer.send()
        val entry = message(writer).content.attachments.single()

        // A second message names the same file without the server linking it (an id copied from another message).
        val copied = ChatMessages.seal(
            MessagePayload.encode(de.velwebsolutions.oasis.chatcrypto.MessageContent("", listOf(entry))),
            mira.repository.conversation(id),
            mira.vault,
            testCrypto
        )
        mira.repository.sendMessage(id, copied)

        val reader = model(jonas, id)
        reader.open()
        val (real, forged) = reader.state.value.timeline.items
        assertTrue(real.listed(entry.id, mine = false))
        assertFalse(forged.listed(entry.id, mine = false))
        assertTrue("own files are always the sender's", forged.listed(entry.id, mine = true))
        reader.answer(forged, entry, true)
        reader.openViewOnce(forged, entry)
        assertNull(reader.state.value.viewOnce)
        assertTrue("nothing was answered for the copy", server.uploads.getValue(entry.id.toInt()).answers.isEmpty())
        clear(reader)
        clear(writer)
    }

    @Test
    fun theShareTargetListsEveryChatRequestsToo() = runTest {
        val (mira, jonas, id) = chat()
        val target = de.velwebsolutions.oasis.chat.ui.ShareTargetViewModel(jonas)
        target.load()
        // Like iOS: a message request is offered too; nothing is sent before "Senden" there.
        assertEquals(listOf(true), target.state.value.conversations.map { it.request })
        val own = de.velwebsolutions.oasis.chat.ui.ShareTargetViewModel(mira)
        own.load()
        assertEquals(listOf(id), own.state.value.conversations.map { it.id })
        assertEquals("1", own.myId)
        own.pick(own.state.value.conversations.single())
        PendingShare.offer(Share(emptyList(), "x"))
        own.cancel()
        assertNull(PendingShare.share.value)
        server.failures["GET conversations"] = 500 to """{"message":"kaputt"}"""
        own.load()
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", own.state.value.error)
        clear(target)
        clear(own)
    }
}
