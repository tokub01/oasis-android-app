package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chat.ChatTestSupport
import de.velwebsolutions.oasis.chat.api.AttachmentState
import de.velwebsolutions.oasis.chat.member
import de.velwebsolutions.oasis.chatcrypto.ChatCryptoException
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Upload in parts and download of encrypted files against the fake server, and the words around them. */
class ChatAttachmentsTest {
    private val support = ChatTestSupport().apply { server.partBytes = 100 }

    @Test
    fun uploadsInPartsAndDownloadsOnlyWhatFitsTheMessage() = runTest {
        val mira = support.member(1)
        val id = mira.repository.createConversation("direct", null, listOf("2"), 7).id
        val file = ByteArray(250) { it.toByte() }
        val progress = mutableListOf<Float>()
        val draft = DraftAttachment(1, AttachmentKind.Video, "video/mp4", file, durationMs = 3_000).withViewOnce(true)
        val entry = ChatAttachments.upload(mira, id, draft) { progress += it }
        assertEquals(listOf(1f / 3, 2f / 3, 1f), progress)
        assertEquals(listOf("video", "video/mp4"), listOf(entry.kind, entry.mime))
        assertEquals(250L, entry.size)
        assertEquals(3_000L, entry.durationMs)
        assertTrue(support.server.uploads.values.single().viewOnce)
        assertTrue(draft.withViewOnce(false).let { !it.viewOnce && it.data === file })

        // The uploader may fetch an unsent upload: it decrypts to the same bytes.
        assertArrayEquals(file, ChatAttachments.download(mira, id, entry))
        val failures = listOf(entry.copy(size = 249), entry.copy(key = "!!"), entry.copy(nonce = entry.key), entry.copy(key = entry.nonce))
        failures.forEach { broken ->
            assertThrows(ChatCryptoException.TamperedAttachment::class.java) {
                kotlinx.coroutines.runBlocking { ChatAttachments.download(mira, id, broken) }
            }
        }
    }

    @Test
    fun theWordsForFiles() {
        assertEquals(listOf("Foto", "Sprachnachricht", "Video"), AttachmentKind.entries.map(AttachmentTexts::title))
        assertEquals(listOf("ein Foto", "eine Sprachnachricht", "ein Video"), AttachmentKind.entries.map(AttachmentTexts::withArticle))
        assertEquals("1 KB", AttachmentTexts.size(3))
        assertEquals("820 KB", AttachmentTexts.size(820 * 1024))
        assertEquals("1,4 MB", AttachmentTexts.size(1_468_006))
        assertEquals("12 MB", AttachmentTexts.size(12 * 1_048_576L))
        assertEquals("0:42", AttachmentTexts.duration(42_900))
        assertEquals("3:00", AttachmentTexts.duration(180_000))
        val entry = MessageAttachment("1", "audio", "audio/mp4", 1, "k", "n")
        assertEquals(AttachmentKind.Audio, AttachmentTexts.kind(entry))
    }

    @Test
    fun filesShortenTheTextAndHaveAStateBeforeTheServerNamesIt() {
        assertFalse(ChatMessages.measure("a".repeat(3_000), attachments = 4).tooLong)
        assertTrue(ChatMessages.measure("a".repeat(3_200), attachments = 4).tooLong)
        assertEquals(10, ChatMessages.measure("Mit Anhang", attachments = 1).characters)
        val item =
            ChatMessageItem(
                "s",
                de.velwebsolutions.oasis.chatcrypto.MessageEnvelope(1, "1", 1, "2", "n", "c", "s"),
                "2",
                null,
                ChatMessageItem.State.Readable
            )
        assertEquals(AttachmentState.SENT, item.state("7", mine = true).status)
        assertEquals(AttachmentState.PENDING, item.state("7", mine = false).status)
        val known = item.copy(
            attachmentStates = mapOf("7" to AttachmentState("7", viewOnce = true, status = AttachmentState.VIEWED, available = false))
        )
        assertEquals(AttachmentState.VIEWED, known.state("7", mine = false).status)
        assertEquals(AttachmentState.DECLINED, AttachmentState("8", status = AttachmentState.DECLINED).status)
        val upload = de.velwebsolutions.oasis.chat.api.AttachmentUpload("7", 2, 5_242_880)
        assertEquals(upload, upload.copy())
        assertEquals(3, de.velwebsolutions.oasis.chat.api.ReceivedParts(3).receivedParts)
        assertEquals("7", de.velwebsolutions.oasis.chat.api.CompletedAttachment("7").id)
    }
}
