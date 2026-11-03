package de.velwebsolutions.oasis.chatcrypto

import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.MAX_MESSAGE_BYTES
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto.Companion.MAX_MESSAGE_LENGTH
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
 * and key and nonce (Base64 URL-safe without padding) to decrypt the downloaded ciphertext. [name] is the file name of a
 * `file` (without path, at most 120 characters); width and height (photos) and the duration (voice messages, videos) are
 * hints for the layout.
 */
@Serializable
data class MessageAttachment(
    val id: String,
    /** `image`, `audio`, `video` or `file`. */
    val kind: String,
    val mime: String,
    /** Plaintext bytes. */
    val size: Long,
    val key: String,
    val nonce: String,
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null
)

/**
 * What a reply quotes (docs/oasis-chat-client.md, "Reactions and replies"): the original's envelope signature [to], its
 * sender's id, [kind] (`text`, `image`, `audio`, `video`, `file` or `once`), the [excerpt] of a text and the original's
 * expiry (`YYYY-MM-DDTHH:MM:SSZ`; the quote shows no longer than the original lives).
 */
data class MessageReply(val to: String, val sender: String, val kind: String, val excerpt: String, val expiresAt: String)

/** A reaction to the message with the envelope signature [to]: one of [MessagePayload.REACTIONS], or "" to remove it. */
data class MessageReaction(val to: String, val emoji: String)

/**
 * What a message says: its text, its files (none for a plain text message), the optional send effect (one of
 * [MessagePayload.EFFECTS]) and the quote of a reply; [reaction] when the message is a reaction (then it is never shown
 * as a message). All of it travels only inside the encrypted payload.
 */
data class MessageContent(
    val text: String,
    val attachments: List<MessageAttachment> = emptyList(),
    val effect: String? = null,
    val reply: MessageReply? = null,
    val reaction: MessageReaction? = null
)

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

    /** The media kinds and the MIME types each may have (`MessagePayload::KINDS`); a `file` may be any type ([FILE_MIME]). */
    val KINDS: Map<String, List<String>> = mapOf(
        "image" to listOf("image/jpeg", "image/png", "image/webp", "image/heic"),
        "audio" to listOf("audio/mp4", "audio/aac", "audio/mpeg", "audio/webm", "audio/ogg"),
        "video" to listOf("video/mp4", "video/quicktime", "video/webm")
    )

    /** The send effects (docs/oasis-chat-client.md, "Backgrounds and effects"); any other value is ignored when read. */
    val EFFECTS = listOf("konfetti", "herzen", "ballons", "feuerwerk")

    /** The reactions, in the order the bar and the chips show them. */
    val REACTIONS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F")

    /** What a reply quotes: text, the first file's kind, or `once` (a view-once original: never its content). */
    val REPLY_KINDS = listOf("text", "image", "audio", "video", "file", "once")

    /** The longest quote: 100 code points of text, and „…“ when it was cut. */
    const val REPLY_EXCERPT_LENGTH = 100

    /** The kind of any other file (PDF, ZIP, documents …). */
    const val FILE = "file"

    /** Any well-formed MIME type, for files (`MessagePayload::FILE_MIME`). */
    val FILE_MIME = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$")

    /** A file name is shown and stored with at most this many characters. */
    const val MAX_NAME_LENGTH = 120

    private val NAME_UNSAFE = Regex("[\\u0000-\\u001F\\u007F/\\\\]+")

    private val ID = Regex("^[1-9][0-9]{0,18}$")
    private val REPLY_FIELDS = listOf("to", "sender", "kind", "excerpt", "expires_at")
    private val SIGNATURE = Regex("^[A-Za-z0-9_-]{86}$")
    private val EXPIRY = Regex("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$")
    private val WHITESPACE = Regex("(?U)[\\s\\uFEFF]+")
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
    fun plaintext(content: MessageContent): String {
        val effect = content.effect
        val reply = content.reply
        if (effect != null && effect !in EFFECTS) throw ChatCryptoException.InvalidInput("Unknown effect.")
        if (reply != null && !wellFormed(reply)) throw ChatCryptoException.InvalidInput("Malformed reply.")
        return if (content.attachments.isEmpty() && effect == null && reply == null && parse(content.text) == null) {
            content.text
        } else {
            buildJsonObject {
                put("t", TYPE)
                put("v", VERSION)
                put("text", content.text)
                put("attachments", JsonArray(content.attachments.map { json.encodeToJsonElement(MessageAttachment.serializer(), it) }))
                if (effect != null) put("effect", effect)
                if (reply != null) {
                    put(
                        "reply",
                        buildJsonObject {
                            put("to", reply.to)
                            put("sender", reply.sender)
                            put("kind", reply.kind)
                            put("excerpt", reply.excerpt)
                            put("expires_at", reply.expiresAt)
                        }
                    )
                }
            }.toString()
        }
    }

    /**
     * The plaintext of a reaction to the message with the envelope signature [to] (`buildReactionPayload`); an empty
     * [emoji] removes the own reaction.
     * @throws ChatCryptoException.InvalidInput for a malformed target or an emoji outside [REACTIONS].
     */
    fun reaction(to: String, emoji: String): String {
        if (!wellFormed(MessageReaction(to, emoji))) throw ChatCryptoException.InvalidInput("Malformed reaction.")
        return buildJsonObject {
            put("t", TYPE)
            put("v", VERSION)
            put("text", "")
            put("attachments", JsonArray(emptyList()))
            put(
                "reaction",
                buildJsonObject {
                    put("to", to)
                    put("emoji", emoji)
                }
            )
        }.toString()
    }

    /**
     * What a reply quotes of a message (`replyTo`): `once` for a view-once original (never its content), else `text`
     * with the excerpt when it has text, else the first file's kind; the excerpt is the text with its whitespace runs
     * collapsed, trimmed, at most [REPLY_EXCERPT_LENGTH] code points and „…“ when cut.
     */
    fun replyTo(
        signature: String,
        senderId: String,
        expiresAt: Instant,
        text: String,
        attachments: List<MessageAttachment>,
        viewOnce: Boolean
    ): MessageReply {
        val collapsed = text.replace(WHITESPACE, " ").trim()
        val kind = when {
            viewOnce -> "once"
            collapsed.isEmpty() -> attachments.firstOrNull()?.kind ?: "text"
            else -> "text"
        }
        val points = collapsed.codePointCount(0, collapsed.length)
        val excerpt = when {
            kind != "text" -> ""
            points > REPLY_EXCERPT_LENGTH -> collapsed.substring(0, collapsed.offsetByCodePoints(0, REPLY_EXCERPT_LENGTH)) + "…"
            else -> collapsed
        }
        val expiry = DateTimeFormatter.ISO_INSTANT.format(expiresAt.truncatedTo(ChronoUnit.SECONDS))
        return MessageReply(signature, senderId, kind, excerpt, expiry)
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
        val effect = (payload["effect"] as? JsonPrimitive)?.takeIf { it.isString && it.content in EFFECTS }?.content
        return MessageContent(
            text,
            listed.take(MAX_ATTACHMENTS).mapNotNull(::checked),
            effect,
            reply(payload["reply"]),
            reaction(payload["reaction"])
        )
    }

    /** A well-formed reply of a payload, or null (`checkReply`). */
    private fun reply(element: JsonElement?): MessageReply? {
        val fields = element as? JsonObject ?: return null
        val values = REPLY_FIELDS.mapNotNull { (fields[it] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
        if (values.size != REPLY_FIELDS.size) return null
        return MessageReply(values[0], values[1], values[2], values[3], values[4]).takeIf(::wellFormed)
    }

    /** A well-formed reaction of a payload, or null (`checkReaction`). */
    private fun reaction(element: JsonElement?): MessageReaction? {
        val fields = element as? JsonObject ?: return null
        val to = (fields["to"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val emoji = (fields["emoji"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return MessageReaction(to, emoji).takeIf(::wellFormed)
    }

    private fun wellFormed(reply: MessageReply): Boolean = SIGNATURE.matches(reply.to) && ID.matches(reply.sender) &&
        reply.kind in REPLY_KINDS && reply.excerpt.codePointCount(0, reply.excerpt.length) <= REPLY_EXCERPT_LENGTH + 1 &&
        (reply.kind == "text" || reply.excerpt.isEmpty()) && EXPIRY.matches(reply.expiresAt)

    private fun wellFormed(reaction: MessageReaction): Boolean =
        SIGNATURE.matches(reaction.to) && (reaction.emoji.isEmpty() || reaction.emoji in REACTIONS)

    /** The payload object of a plaintext, or null for plain text. */
    private fun parse(plaintext: String): JsonObject? {
        if (!plaintext.startsWith("{")) return null
        val payload = runCatching { json.parseToJsonElement(plaintext) }.getOrNull() as? JsonObject
        val type = payload?.get("t") as? JsonPrimitive
        return payload?.takeIf { type?.isString == true && type.content == TYPE }
    }

    /** Whether a kind may carry this MIME type: the listed ones for media, any well-formed type for files. */
    fun allowedMime(kind: String, mime: String): Boolean = if (kind ==
        FILE
    ) {
        FILE_MIME.matches(mime)
    } else {
        KINDS[kind]?.contains(mime) == true
    }

    /**
     * A safe file name for showing and saving (`MessagePayload::fileName`): the last path segment without control
     * characters or slashes, trimmed, at most 120 characters; null when nothing is left.
     */
    fun fileName(raw: String?): String? {
        if (raw == null) return null
        val base = raw.replace('\\', '/').trimEnd('/').substringAfterLast('/')
        val clean = base.replace(NAME_UNSAFE, "").trim()
        if (clean.isEmpty()) return null
        val end = clean.offsetByCodePoints(0, minOf(MAX_NAME_LENGTH, clean.codePointCount(0, clean.length)))
        return clean.substring(0, end)
    }

    /** A well-formed entry (`checkAttachment`): id, known kind and MIME type, size, key and nonce, optional sizes ≥ 0. */
    private fun checked(entry: JsonElement): MessageAttachment? {
        val fields = entry as? JsonObject ?: return null
        val attachment = runCatching { json.decodeFromJsonElement(MessageAttachment.serializer(), fields) }.getOrNull() ?: return null
        val optionals = listOf("width", "height", "duration_ms").mapNotNull { fields[it] }
        val valid = ID.matches(attachment.id) && allowedMime(attachment.kind, attachment.mime) &&
            (fields["size"].integer() ?: 0) in 1..MAX_ATTACHMENT_BYTES && KEY.matches(attachment.key) &&
            NONCE.matches(attachment.nonce) && optionals.all { (it.integer() ?: -1) >= 0 }
        return attachment.takeIf { valid }?.copy(name = fileName(attachment.name))
    }

    /** A JSON integer (not a string of digits), or null. */
    private fun JsonElement?.integer(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
