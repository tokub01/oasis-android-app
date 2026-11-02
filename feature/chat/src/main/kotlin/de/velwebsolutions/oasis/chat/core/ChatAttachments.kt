package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chatcrypto.ChatCryptoException
import de.velwebsolutions.oasis.chatcrypto.ChatEncoding
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.chatcrypto.decryptAttachment
import de.velwebsolutions.oasis.chatcrypto.encryptAttachment
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/** The kinds of attachment and their names in the message plaintext; [File] is any other file (PDF, ZIP, …). */
enum class AttachmentKind(val wire: String) {
    Image("image"),
    Audio("audio"),
    Video("video"),
    File("file")
}

/**
 * A photo, voice message, video or file picked, recorded or shared for the next message, still unencrypted and only in
 * memory. [localId] tells drafts apart in the composer; [name] is a file's name; [viewOnce] ("Einmal ansehen") deletes it
 * for good once every recipient viewed it.
 */
class DraftAttachment(
    val localId: Long,
    val kind: AttachmentKind,
    val mime: String,
    val data: ByteArray,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val viewOnce: Boolean = false,
    val name: String? = null
) {
    /** The same draft with "Einmal ansehen" switched on or off. */
    fun withViewOnce(value: Boolean) = DraftAttachment(localId, kind, mime, data, width, height, durationMs, value, name)
}

/** Why a picked or recorded file cannot be sent, in words for the composer. */
class AttachmentProblem(message: String) : Exception(message)

/**
 * Encrypted attachments (docs/oasis-chat.md, "Anhänge"; the web's client/attachments.js): encrypt a draft with a fresh
 * key, upload the ciphertext in parts and finish the upload; download a ciphertext and decrypt it. Key and nonce go into
 * the message only.
 */
object ChatAttachments {
    /** At most this many files per message (`ChatAttachment::PER_MESSAGE`). */
    const val MAX_PER_MESSAGE = MessagePayload.MAX_ATTACHMENTS

    /** Photos after re-encoding (10 MB). */
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

    /** Videos: the server takes 50 MB of ciphertext, which is the file plus the 16-byte tag. */
    const val MAX_VIDEO_BYTES = MessagePayload.MAX_ATTACHMENT_BYTES

    /** Files of any type, as they are. */
    const val MAX_FILE_BYTES = MessagePayload.MAX_ATTACHMENT_BYTES

    /** Videos up to 60 seconds. */
    const val MAX_VIDEO_MS = 60_000L

    /** Voice messages up to 3 minutes. */
    const val MAX_VOICE_MS = 180_000L

    /**
     * Encrypts and uploads one draft; [onProgress] hears the share of parts sent (0 … 1). Returns the entry for the
     * message plaintext.
     */
    suspend fun upload(
        context: ChatContext,
        conversationId: String,
        draft: DraftAttachment,
        onProgress: (Float) -> Unit
    ): MessageAttachment {
        val sealed = withContext(context.cryptoDispatcher) { context.crypto.encryptAttachment(draft.data, conversationId) }
        try {
            val ciphertext = sealed.ciphertext
            val upload = context.repository.startAttachment(conversationId, ciphertext.size, draft.viewOnce)
            for (index in 0 until upload.parts) {
                val from = index * upload.partBytes
                val part = ciphertext.copyOfRange(from, minOf(ciphertext.size, from + upload.partBytes))
                context.repository.putAttachmentPart(conversationId, upload.id, index, part)
                onProgress((index + 1).toFloat() / upload.parts)
            }
            context.repository.completeAttachment(conversationId, upload.id)
            return MessageAttachment(
                id = upload.id,
                kind = draft.kind.wire,
                mime = draft.mime,
                size = draft.data.size.toLong(),
                key = context.crypto.toBase64(sealed.key),
                nonce = context.crypto.toBase64(sealed.nonce),
                name = draft.name,
                width = draft.width,
                height = draft.height,
                durationMs = draft.durationMs
            )
        } finally {
            ChatEncoding.wipe(sealed.key)
        }
    }

    /**
     * Downloads and decrypts one attachment of a message.
     * @throws ChatCryptoException.TamperedAttachment when key, nonce, ciphertext or the size named in the message do not
     *   fit together.
     */
    suspend fun download(context: ChatContext, conversationId: String, attachment: MessageAttachment): ByteArray {
        val ciphertext = context.repository.downloadAttachment(conversationId, attachment.id)
        // The size the signed message names, plus the tag: anything else is refused before any decryption.
        if (ciphertext.size.toLong() != attachment.size + TAG_BYTES) throw ChatCryptoException.TamperedAttachment
        return withContext(context.cryptoDispatcher) {
            // Undecodable key or nonce: empty, which the AEAD refuses like a wrong one.
            val key = decoded(context, attachment.key)
            try {
                // The AEAD only opens a ciphertext of its tag plus the plaintext: the size was checked above.
                context.crypto.decryptAttachment(ciphertext, conversationId, key, decoded(context, attachment.nonce))
            } finally {
                ChatEncoding.wipe(key)
            }
        }
    }

    /** The AEAD tag every attachment ciphertext carries (XChaCha20-Poly1305). */
    private const val TAG_BYTES = 16L

    private fun decoded(context: ChatContext, base64: String): ByteArray =
        runCatching { context.crypto.fromBase64(base64) }.getOrDefault(ByteArray(0))
}

/** The German words for attachments in bubbles, the composer and the consent question. */
object AttachmentTexts {
    /** "Foto", "Sprachnachricht", "Video", "Datei". */
    fun title(kind: AttachmentKind): String = when (kind) {
        AttachmentKind.Image -> "Foto"
        AttachmentKind.Audio -> "Sprachnachricht"
        AttachmentKind.Video -> "Video"
        AttachmentKind.File -> "Datei"
    }

    /** "ein Foto", "eine Sprachnachricht", "ein Video", "eine Datei" (inside a sentence). */
    fun withArticle(kind: AttachmentKind): String = when (kind) {
        AttachmentKind.Audio, AttachmentKind.File -> "eine ${title(kind)}"
        else -> "ein ${title(kind)}"
    }

    /** What a file is called on screen: its name, or "Datei" without one. */
    fun fileName(name: String?): String = name ?: "Datei"

    /** "820 KB", "1,4 MB", "12 MB". */
    fun size(bytes: Long): String {
        val megabytes = bytes / 1_048_576.0
        return when {
            megabytes >= 10 -> "${megabytes.roundToInt()} MB"
            megabytes >= 1 -> "${"%.1f".format(Locale.GERMANY, megabytes)} MB"
            else -> "${ceil(bytes / 1024.0).toLong()} KB"
        }
    }

    /** "0:42", "2:05". */
    fun duration(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    /** The kind of a message's file (decoding only lets known kinds through). */
    fun kind(attachment: MessageAttachment): AttachmentKind = AttachmentKind.entries.first { it.wire == attachment.kind }
}
