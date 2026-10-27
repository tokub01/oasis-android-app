package de.velwebsolutions.oasis.chatcrypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Attachment encryption against vectors made by the browser's libsodium (libsodium-wrappers-sumo, the binding of
 * resources/js/chat; fixtures/attachment-vectors.json), and the v2 message plaintext both ways.
 */
class AttachmentsTest {
    private val crypto = testCrypto

    @Serializable
    data class Vectors(val vectors: List<Vector>) {
        @Serializable
        data class Vector(
            @SerialName("conversation_id") val conversationId: String,
            val key: String,
            val nonce: String,
            val plaintext: String,
            val ciphertext: String
        )
    }

    private val vectors: List<Vectors.Vector> by lazy {
        val text = checkNotNull(Vectors::class.java.getResource("/fixtures/attachment-vectors.json")).readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(Vectors.serializer(), text).vectors
    }

    private fun bytes(base64: String) = if (base64.isEmpty()) ByteArray(0) else crypto.fromBase64(base64)

    @Test
    fun aFixedKeyAndNonceGiveTheBrowsersCiphertext() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(24) { (0x40 + it).toByte() }
        val sealed = crypto.encryptAttachment("Oasis-Anhang: Foto 🌴 ✓".toByteArray(), "4242", key, nonce)
        assertEquals("m1h2GaPNOHjnlenZlbwj_ebVjTSM1ee6iK1uzIiOpFmA1HXqq0DKbtnLUA", crypto.toBase64(sealed.ciphertext))
        assertArrayEquals(key, sealed.key)
        assertArrayEquals(nonce, sealed.nonce)
        assertEquals("oasis-chat/attachment/v1|4242", attachmentAssociatedData("4242").toString(Charsets.UTF_8))
    }

    @Test
    fun everyBrowserVectorEncryptsAndDecryptsByteForByte() {
        assertEquals(3, vectors.size)
        for (vector in vectors) {
            val key = bytes(vector.key)
            val nonce = bytes(vector.nonce)
            val plaintext = bytes(vector.plaintext)
            val sealed = crypto.encryptAttachment(plaintext, vector.conversationId, key, nonce)
            assertEquals(vector.ciphertext, crypto.toBase64(sealed.ciphertext))
            assertArrayEquals(plaintext, crypto.decryptAttachment(bytes(vector.ciphertext), vector.conversationId, key, nonce))
        }
    }

    @Test
    fun freshKeysOpenOnlyInTheirChatAndUntouched() {
        val file = ByteArray(70_000) { (it % 251).toByte() }
        val first = crypto.encryptAttachment(file, "12")
        val second = crypto.encryptAttachment(file, "12")
        assertEquals(32, first.key.size)
        assertEquals(24, first.nonce.size)
        assertEquals(file.size + 16, first.ciphertext.size)
        assertFalse("every file gets its own key", first.key.contentEquals(second.key))
        assertArrayEquals(file, crypto.decryptAttachment(first.ciphertext, "12", first.key, first.nonce))

        val tampered = first.ciphertext.copyOf().also { it[5] = (it[5] + 1).toByte() }
        val failures = listOf(
            { crypto.decryptAttachment(tampered, "12", first.key, first.nonce) },
            { crypto.decryptAttachment(first.ciphertext, "13", first.key, first.nonce) },
            { crypto.decryptAttachment(first.ciphertext, "12", second.key, first.nonce) },
            { crypto.decryptAttachment(first.ciphertext, "12", first.key.copyOf(31), first.nonce) },
            { crypto.decryptAttachment(ByteArray(3), "12", first.key, first.nonce) }
        )
        failures.forEach { assertThrows(ChatCryptoException.TamperedAttachment::class.java) { it() } }
    }

    @Test
    fun keysAndNoncesOfTheWrongLengthAndEmptyChatsAreRefused() {
        assertThrows(ChatCryptoException.InvalidInput::class.java) {
            crypto.encryptAttachment(ByteArray(1), "1", ByteArray(31), ByteArray(24))
        }
        assertThrows(ChatCryptoException.InvalidInput::class.java) {
            crypto.encryptAttachment(ByteArray(1), "1", ByteArray(32), ByteArray(12))
        }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { crypto.encryptAttachment(ByteArray(1), "") }
    }

    private val key = "A".repeat(43)
    private val nonce = "B".repeat(32)
    private val photo = MessageAttachment("17", "image", "image/jpeg", 1234, key, nonce, width = 2048, height = 1536)
    private val voice = MessageAttachment("18", "audio", "audio/mp4", 99, key, nonce, durationMs = 4200)

    @Test
    fun aMessageWithFilesIsTheV2ObjectAndWithoutFilesPlainText() {
        assertEquals("Nur Text", MessagePayload.encode(MessageContent("Nur Text")))
        val plaintext = MessagePayload.encode(MessageContent("Schau mal 🌴", listOf(photo, voice)))
        assertEquals(
            """{"t":"oasis-message","v":2,"text":"Schau mal 🌴","attachments":[""" +
                """{"id":"17","kind":"image","mime":"image/jpeg","size":1234,"key":"$key","nonce":"$nonce","width":2048,"height":1536},""" +
                """{"id":"18","kind":"audio","mime":"audio/mp4","size":99,"key":"$key","nonce":"$nonce","duration_ms":4200}]}""",
            plaintext
        )
        assertEquals(MessageContent("Schau mal 🌴", listOf(photo, voice)), MessagePayload.decode(plaintext))
        // A typed text that looks like a payload goes as a payload without files, so it never turns into files.
        val lookalike = """{"t":"oasis-message","v":2,"text":"","attachments":[]}"""
        val wrapped = MessagePayload.encode(MessageContent(lookalike))
        assertEquals(MessageContent(lookalike), MessagePayload.decode(wrapped))
        assertEquals("{nur Klammern}", MessagePayload.encode(MessageContent("{nur Klammern}")))
    }

    @Test
    fun theFilesCountAgainstTheMessageLimits() {
        val longest = "a".repeat(ChatCrypto.MAX_MESSAGE_LENGTH)
        assertEquals(longest, MessagePayload.encode(MessageContent(longest)))
        assertThrows(ChatCryptoException.MessageTooLong::class.java) { MessagePayload.encode(MessageContent(longest, listOf(photo))) }
        // 2 bytes per "ä": far below 4000 characters, but above the byte limit once the metadata is added.
        val wide = "ä".repeat(2_950)
        assertThrows(ChatCryptoException.MessageTooLong::class.java) { MessagePayload.encode(MessageContent(wide, listOf(photo))) }
    }

    private fun entry(fields: String) = """{"id":"1","kind":"video","mime":"video/mp4","size":3,"key":"$key","nonce":"$nonce"$fields}"""

    @Test
    fun everythingElseIsPlainTextAndBadEntriesAreSkipped() {
        val plain = listOf(
            "Hallo",
            "{ nicht fertig",
            "{\"text\":\"x\",\"attachments\":[]}",
            "{\"t\":{},\"attachments\":[]}",
            "{\"t\":5}",
            "{\"t\":\"anders\",\"attachments\":[]}"
        )
        plain.forEach { assertEquals(MessageContent(it), MessagePayload.decode(it)) }
        assertEquals(MessageContent("x"), MessagePayload.decode("""{"t":"oasis-message","v":3,"text":"x","attachments":[${entry("")}]}"""))
        assertEquals(MessageContent(""), MessagePayload.decode("""{"t":"oasis-message","v":2,"text":5,"attachments":{}}"""))
        assertEquals(MessageContent("x"), MessagePayload.decode("""{"t":"oasis-message","text":"x","attachments":[${entry("")}]}"""))
        assertEquals(MessageContent("x"), MessagePayload.decode("""{"t":"oasis-message","v":{},"text":"x","attachments":[${entry("")}]}"""))
        val bad = listOf(
            "\"kaputt\"",
            """{"id":"1","kind":"image"}""",
            entry("").replace("\"id\":\"1\"", "\"id\":\"01\""),
            entry("").replace("video/mp4", "image/jpeg"),
            entry("").replace("\"video\"", "\"datei\""),
            entry("").replace("\"size\":3", "\"size\":0"),
            entry("").replace("\"size\":3", "\"size\":52428785"),
            entry("").replace("\"size\":3", "\"size\":\"3\""),
            entry("").replace(key, "kurz"),
            entry("").replace(nonce, "kurz"),
            entry(",\"width\":-1"),
            entry(",\"height\":null")
        )
        val good = listOf(entry(",\"duration_ms\":59000"), entry(",\"width\":0"))
        val decoded = MessagePayload.decode("""{"t":"oasis-message","v":2,"text":"Hi","attachments":[${(bad + good).joinToString(",")}]}""")
        assertEquals("only the first 4 entries count", 0, decoded.attachments.size)
        val counted = MessagePayload.decode(
            """{"t":"oasis-message","v":2,"attachments":[${(good + bad.take(1) + good + good).joinToString(",")}]}"""
        )
        assertEquals("", counted.text)
        assertEquals(listOf(59_000L, null, 59_000L), counted.attachments.map { it.durationMs })
        bad.drop(2).forEach { entry ->
            assertEquals(entry, 0, MessagePayload.decode("""{"t":"oasis-message","v":2,"attachments":[$entry]}""").attachments.size)
        }
    }

    @Test
    fun filesOfAnyWellFormedTypeKeepASafeName() {
        val pdf = MessageAttachment("9", "file", "application/pdf", 240_000, key, nonce, name = "Rechnung.pdf")
        val plaintext = MessagePayload.encode(MessageContent("", listOf(pdf)))
        assertEquals(true, plaintext.contains(""""nonce":"$nonce","name":"Rechnung.pdf""""))
        assertEquals(listOf(pdf), MessagePayload.decode(plaintext).attachments)

        fun decoded(mime: String, name: String?): MessageAttachment? {
            val named = if (name == null) "" else ""","name":${kotlinx.serialization.json.JsonPrimitive(name)}"""
            val entry = """{"id":"9","kind":"file","mime":"$mime","size":5,"key":"$key","nonce":"$nonce"$named}"""
            return MessagePayload.decode("""{"t":"oasis-message","v":2,"attachments":[$entry]}""").attachments.singleOrNull()
        }
        val word = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals("Bericht.docx", decoded(word, "C:\\Users\\mira\\Bericht.docx")?.name)
        assertEquals("passwd", decoded("text/plain", "../../etc/passwd")?.name)
        assertEquals("ab", decoded("text/plain", " a\u0007b\n ")?.name)
        assertEquals("ordner", decoded("application/zip", "ordner/")?.name)
        assertEquals(null, decoded("application/zip", "/// ")?.name)
        assertEquals(null, decoded("application/octet-stream", null)?.name)
        assertEquals(120, decoded("text/plain", "ä".repeat(130))?.name?.length)
        assertEquals("🌴".repeat(120), decoded("text/plain", "🌴".repeat(121))?.name)
        assertEquals(null, decoded("Application/PDF", "a.pdf"))
        assertEquals(null, decoded("kein-typ", "a.pdf"))
        assertEquals(null, MessagePayload.fileName(null))
        assertEquals(true, MessagePayload.allowedMime("image", "image/png"))
        assertEquals(false, MessagePayload.allowedMime("image", "application/pdf"))
    }
}
