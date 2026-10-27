package de.velwebsolutions.oasis.chatcrypto

import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.MAX_MESSAGE_BYTES
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.MAX_MESSAGE_LENGTH
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One file of a message (`Attachment` in docs/oasis-chat.md, "Anhänge"): the upload's id, what it is, the plaintext size,
 * and key and nonce (Base64 URL-safe without padding) to decrypt the downloaded ciphertext. Width and height (photos)
 * and the duration (voice messages, videos) are hints for the layout.
 */
@Serializable
data class MessageAttachment(
    val id: String,
    /** `image`, `audio` or `video`. */
    val kind: String,
    val mime: String,
    /** Plaintext bytes. */
    val size: Long,
    val key: String,
    val nonce: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null
)

/** What a message says: its text and its files (none for a plain text message). */
data class MessageContent(val text: String, val attachments: List<MessageAttachment> = emptyList())

/**
 * The plaintext of a message, the same on every platform (resources/js/chat/attachments.js,
 * app/Support/Chat/MessagePayload.php): a message without files is just its text, one with files the JSON
 * `{"t":"oasis-message","v":2,"text":"…","attachments":[Attachment]}`. A plaintext that is not such an object is plain
 * text (older messages, and every message without files).
 */
object MessagePayload {
    const val TYPE = "oasis-message"
    const val VERSION = 2

    /** At most this many files per message (`ChatAttachment::PER_MESSAGE`). */
    const val MAX_ATTACHMENTS = 4

    /** The largest file: the server takes 50 MB of ciphertext, which includes the 16-byte tag. */
    const val MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024 - 16

    /** Kinds of attachment and the MIME types each may have (`MessagePayload::KINDS`). */
    val KINDS: Map<String, List<String>> = mapOf(
        "image" to listOf("image/jpeg", "image/png", "image/webp", "image/heic"),
        "audio" to listOf("audio/mp4", "audio/aac", "audio/mpeg", "audio/webm", "audio/ogg"),
        "video" to listOf("video/mp4", "video/quicktime", "video/webm")
    )

    private val ID = Regex("^[1-9][0-9]{0,18}$")
    private val KEY = Regex("^[A-Za-z0-9_-]{43}$")
    private val NONCE = Regex("^[A-Za-z0-9_-]{32}$")

    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * The plaintext of [content] without checking the limits: the text alone without files, the JSON object with them
     * – and also without files when the text itself looks like such an object, so a typed JSON text never turns into
     * files on the other side (`buildMessagePayload`).
     */
    fun plaintext(content: MessageContent): String = if (content.attachments.isEmpty() && parse(content.text) == null) {
        content.text
    } else {
        buildJsonObject {
            put("t", TYPE)
            put("v", VERSION)
            put("text", content.text)
            put("attachments", JsonArray(content.attachments.map { json.encodeToJsonElement(MessageAttachment.serializer(), it) }))
        }.toString()
    }

    /** Whether a plaintext stays within the message limits (UTF-8 bytes and code points). */
    fun fits(plaintext: String): Boolean = plaintext.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES &&
        plaintext.codePointCount(0, plaintext.length) <= MAX_MESSAGE_LENGTH

    /**
     * The plaintext to encrypt ([plaintext]).
     * @throws ChatCryptoException.MessageTooLong when it exceeds the message limits.
     */
    fun encode(content: MessageContent): String {
        val plaintext = plaintext(content)
        if (!fits(plaintext)) throw ChatCryptoException.MessageTooLong(MAX_MESSAGE_LENGTH)
        return plaintext
    }

    /**
     * Text and files of a decrypted plaintext (`parseMessagePayload`): plain text unless it is a JSON object with
     * `"t":"oasis-message"`; of a v2 object the first 4 entries count, and malformed ones among them are left out.
     */
    fun decode(plaintext: String): MessageContent {
        val payload = parse(plaintext) ?: return MessageContent(plaintext)
        val text = (payload["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val listed = (payload["attachments"] as? JsonArray)?.takeIf { payload["v"].integer() == VERSION.toLong() }.orEmpty()
        return MessageContent(text, listed.take(MAX_ATTACHMENTS).mapNotNull(::checked))
    }

    /** The payload object of a plaintext, or null for plain text. */
    private fun parse(plaintext: String): JsonObject? {
        if (!plaintext.startsWith("{")) return null
        val payload = runCatching { json.parseToJsonElement(plaintext) }.getOrNull() as? JsonObject
        val type = payload?.get("t") as? JsonPrimitive
        return payload?.takeIf { type?.isString == true && type.content == TYPE }
    }

    /** A well-formed entry (`checkAttachment`): id, known kind and MIME type, size, key and nonce, optional sizes ≥ 0. */
    private fun checked(entry: JsonElement): MessageAttachment? {
        val fields = entry as? JsonObject ?: return null
        val attachment = runCatching { json.decodeFromJsonElement(MessageAttachment.serializer(), fields) }.getOrNull() ?: return null
        val optionals = listOf("width", "height", "duration_ms").mapNotNull { fields[it] }
        val valid = ID.matches(attachment.id) && KINDS[attachment.kind]?.contains(attachment.mime) == true &&
            (fields["size"].integer() ?: 0) in 1..MAX_ATTACHMENT_BYTES && KEY.matches(attachment.key) &&
            NONCE.matches(attachment.nonce) && optionals.all { (it.integer() ?: -1) >= 0 }
        return attachment.takeIf { valid }
    }

    /** A JSON integer (not a string of digits), or null. */
    private fun JsonElement?.integer(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
