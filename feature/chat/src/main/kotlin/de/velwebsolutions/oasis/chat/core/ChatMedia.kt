package de.velwebsolutions.oasis.chat.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import de.velwebsolutions.oasis.chat.R
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.core.share.ShareInbox
import de.velwebsolutions.oasis.core.share.SharedFile
import de.velwebsolutions.oasis.design.media.ImageCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A decrypted attachment of a shown message, as the bubble presents it. */
sealed interface AttachmentView {
    /** Downloading and decrypting. */
    data object Loading : AttachmentView

    /** Could not be shown: [message] says why; [retry] when another try may work (network). */
    data class Failed(val message: String, val retry: Boolean = false) : AttachmentView

    /** A photo, decoded in memory. */
    class Photo(val image: ImageBitmap) : AttachmentView

    /** A voice message or video in the app cache (deleted when the chat closes or locks), with a poster frame for videos. */
    class Playable(val file: File, val poster: ImageBitmap?) : AttachmentView

    /** Any other file in the app cache under its name, for other apps to open or share (never shown inside Oasis). */
    class Document(val file: File, val mime: String) : AttachmentView
}

/**
 * The device side of attachments: check and prepare picked photos and videos, record voice messages, and turn decrypted
 * files into something the screen can show. Decrypted files only live in memory or, for the players, in the app cache
 * until [clear].
 */
interface ChatMedia {
    /**
     * A picked photo (re-encoded as JPEG, longest edge 2048) or video (at most 60 s and 50 MB, as it is).
     * @throws AttachmentProblem with a text for the composer.
     */
    suspend fun prepare(uri: Uri, localId: Long): DraftAttachment

    /**
     * A file picked under "Datei" (any type, at most 50 MB, as it is, with its name).
     * @throws AttachmentProblem with a text for the composer.
     */
    suspend fun prepareFile(uri: Uri, localId: Long): DraftAttachment

    /**
     * A file another app shared: images by the photo rules, videos and voice messages by theirs, anything else as a file.
     * @throws AttachmentProblem with a text for the composer.
     */
    suspend fun prepareShared(shared: SharedFile, localId: Long): DraftAttachment

    /** Starts a voice message (AAC in MPEG-4); [onLimit] runs when 3 minutes are reached. False when it did not start. */
    fun startRecording(onLimit: () -> Unit): Boolean

    /** Ends the recording: the voice message, or null when nothing usable was recorded. */
    suspend fun finishRecording(localId: Long): DraftAttachment?

    /** Stops a running recording and throws it away. */
    fun cancelRecording()

    /**
     * A decrypted file ready to show: photos decoded, voice messages and videos written to the cache for the players,
     * other files to the cache under their [name] for other apps.
     */
    suspend fun present(kind: AttachmentKind, data: ByteArray, mime: String, name: String?): AttachmentView

    /** Deletes the cache file of one shown attachment right away (view once, after closing). */
    fun discard(view: AttachmentView)

    /** Stops a recording and deletes every decrypted file (leaving the chat, locking). */
    fun clear()
}

/**
 * [ChatMedia] on Android: the photo picker's files through the content resolver, [ImageCodec] for photos,
 * MediaMetadataRetriever for video durations and poster frames, MediaRecorder for voice messages. The limits are
 * parameters only so tests can reach them with small files.
 */
class AndroidChatMedia(
    private val context: Context,
    private val maxImageBytes: Long = ChatAttachments.MAX_IMAGE_BYTES,
    private val maxVideoBytes: Long = ChatAttachments.MAX_VIDEO_BYTES,
    private val maxFileBytes: Long = ChatAttachments.MAX_FILE_BYTES,
    // MediaRecorder(Context) needs Android 12; the deprecated constructor works on every version the app supports.
    @Suppress("DEPRECATION") private val newRecorder: () -> MediaRecorder = { MediaRecorder() }
) : ChatMedia {
    private class Recording(val recorder: MediaRecorder, val file: File, val startedAt: Long)

    /** Leftovers of an earlier process are removed when the chat starts (and by [ChatFileProvider] at process start). */
    val folder: File = File(context.cacheDir, FOLDER).apply {
        deleteRecursively()
        mkdirs()
    }

    private var recording: Recording? = null

    /** Numbers the cache files; several bubbles decrypt at once on the IO threads, so every number is handed out once. */
    private val counter = AtomicInteger()

    override suspend fun prepare(uri: Uri, localId: Long): DraftAttachment = withContext(Dispatchers.IO) {
        byKind(uri, context.contentResolver.getType(uri).orEmpty(), null, localId)
    }

    override suspend fun prepareFile(uri: Uri, localId: Long): DraftAttachment = withContext(Dispatchers.IO) {
        file(uri, context.contentResolver.getType(uri).orEmpty(), ShareInbox.displayName(context, uri), localId)
    }

    override suspend fun prepareShared(shared: SharedFile, localId: Long): DraftAttachment = withContext(Dispatchers.IO) {
        byKind(Uri.fromFile(shared.file), shared.mime, shared.name, localId)
    }

    /** Photos, videos and voice messages by their rules; everything else as a file. */
    private fun byKind(uri: Uri, mime: String, name: String?, localId: Long): DraftAttachment = when {
        mime.startsWith("image/") -> photo(read(uri, PHOTO_READ_BYTES, PHOTO_TOO_LARGE), localId)
        mime in MessagePayload.KINDS.getValue(AttachmentKind.Video.wire) -> video(uri, mime, localId)
        mime in MessagePayload.KINDS.getValue(AttachmentKind.Audio.wire) -> audio(uri, mime, name, localId)
        else -> file(uri, mime, name, localId)
    }

    private fun photo(data: ByteArray, localId: Long): DraftAttachment {
        val jpeg = ImageCodec.jpeg(data, PHOTO_EDGE) ?: throw AttachmentProblem("Dieses Foto ließ sich nicht öffnen.")
        if (jpeg.data.size > maxImageBytes) throw AttachmentProblem(PHOTO_TOO_LARGE)
        return DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", jpeg.data, jpeg.width, jpeg.height)
    }

    /** The duration the file names, or null when Android cannot read it. */
    private fun duration(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun video(uri: Uri, mime: String, localId: Long): DraftAttachment {
        val durationMs = duration(uri) ?: throw AttachmentProblem("Dieses Video ließ sich nicht öffnen.")
        if (durationMs > ChatAttachments.MAX_VIDEO_MS) {
            throw AttachmentProblem("Das Video ist länger als 60 Sekunden. Kürz es in deiner Galerie und wähl den kürzeren Ausschnitt.")
        }
        val data = read(uri, maxVideoBytes, "Das Video ist größer als 50 MB. Nimm bitte einen kürzeren Ausschnitt.")
        return DraftAttachment(localId, AttachmentKind.Video, mime, data, durationMs = durationMs)
    }

    /** A shared recording up to 3 minutes is a voice message; a longer or unreadable one goes as a file. */
    private fun audio(uri: Uri, mime: String, name: String?, localId: Long): DraftAttachment {
        val durationMs = duration(uri)?.takeIf { it <= ChatAttachments.MAX_VOICE_MS } ?: return file(uri, mime, name, localId)
        val data = read(uri, maxFileBytes, FILE_TOO_LARGE)
        return DraftAttachment(localId, AttachmentKind.Audio, mime, data, durationMs = durationMs)
    }

    /** Any file as it is: its type when well-formed (else `application/octet-stream`) and its name. */
    private fun file(uri: Uri, mime: String, name: String?, localId: Long): DraftAttachment {
        val type = mime.lowercase().substringBefore(';').trim().takeIf(MessagePayload.FILE_MIME::matches) ?: "application/octet-stream"
        val data = read(uri, maxFileBytes, FILE_TOO_LARGE)
        if (data.isEmpty()) throw AttachmentProblem("Die Datei ist leer.")
        return DraftAttachment(localId, AttachmentKind.File, type, data, name = MessagePayload.fileName(name) ?: "Datei")
    }

    /** The whole file, or [AttachmentProblem] when it is unreadable or larger than [limit]. */
    private fun read(uri: Uri, limit: Long, tooLarge: String): ByteArray {
        // openInputStream only returns null when the provider crashed: that, like a missing file, is unreadable.
        val input = runCatching { context.contentResolver.openInputStream(uri)!! }.getOrNull()
            ?: throw AttachmentProblem("Die Datei ließ sich nicht lesen.")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var count = stream.read(buffer)
            while (count >= 0) {
                output.write(buffer, 0, count)
                if (output.size() > limit) throw AttachmentProblem(tooLarge)
                count = stream.read(buffer)
            }
            output.toByteArray()
        }
    }

    override fun startRecording(onLimit: () -> Unit): Boolean {
        cancelRecording()
        val file = File(folder, "sprachnachricht-${counter.incrementAndGet()}.m4a")
        val recorder = newRecorder()
        return try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setMaxDuration(ChatAttachments.MAX_VOICE_MS.toInt())
            recorder.setOutputFile(file.path)
            recorder.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit() }
            recorder.prepare()
            recorder.start()
            recording = Recording(recorder, file, SystemClock.elapsedRealtime())
            true
        } catch (_: Exception) {
            recorder.release()
            file.delete()
            false
        }
    }

    override suspend fun finishRecording(localId: Long): DraftAttachment? {
        val current = recording ?: return null
        recording = null
        val durationMs = (SystemClock.elapsedRealtime() - current.startedAt).coerceAtMost(ChatAttachments.MAX_VOICE_MS)
        return withContext(Dispatchers.IO) {
            // stop() fails when nothing was recorded; then there is no file to send.
            val data = runCatching {
                current.recorder.stop()
                current.file.readBytes()
            }.getOrNull()
            current.recorder.release()
            current.file.delete()
            data?.takeIf {
                it.isNotEmpty()
            }?.let { DraftAttachment(localId, AttachmentKind.Audio, "audio/mp4", it, durationMs = durationMs) }
        }
    }

    override fun cancelRecording() {
        val current = recording ?: return
        recording = null
        runCatching { current.recorder.stop() }
        current.recorder.release()
        current.file.delete()
    }

    override suspend fun present(kind: AttachmentKind, data: ByteArray, mime: String, name: String?): AttachmentView =
        withContext(Dispatchers.IO) {
            when (kind) {
                AttachmentKind.Image -> ImageCodec.thumbnail(data, edge = SHOWN_EDGE)?.let { AttachmentView.Photo(it.asImageBitmap()) }
                    ?: AttachmentView.Failed(UNVERIFIABLE)

                AttachmentKind.File -> document(data, mime, name)

                else -> playable(kind, data)
            }
        }

    /**
     * Its own folder keeps the original name for the app that opens it, within the bytes a file name may have
     * ([storableName]); "." and ".." are no names.
     */
    private fun document(data: ByteArray, mime: String, name: String?): AttachmentView {
        val safe = AttachmentTexts.fileName(name).takeUnless { it == "." || it == ".." } ?: "Datei"
        val own = File(folder, "datei-${counter.incrementAndGet()}").apply { mkdirs() }
        val file = File(own, storableName(safe)).apply { writeBytes(data) }
        return AttachmentView.Document(file, mime)
    }

    private fun playable(kind: AttachmentKind, data: ByteArray): AttachmentView {
        val extension = if (kind == AttachmentKind.Video) "mp4" else "m4a"
        val file = File(folder, "anhang-${counter.incrementAndGet()}.$extension").apply { writeBytes(data) }
        return AttachmentView.Playable(file, if (kind == AttachmentKind.Video) poster(file) else null)
    }

    private fun poster(file: File): ImageBitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.getFrameAtTime(0)?.asImageBitmap()
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    override fun discard(view: AttachmentView) {
        when (view) {
            is AttachmentView.Playable -> view.file.delete()
            is AttachmentView.Document -> view.file.delete()
            else -> Unit
        }
    }

    override fun clear() {
        cancelRecording()
        folder.deleteRecursively()
        folder.mkdirs()
    }

    companion object {
        /** The longest edge of a sent photo. */
        const val PHOTO_EDGE = 2048

        /** Photos are decoded at about this size for the screen. */
        const val SHOWN_EDGE = 1600

        /** Originals larger than this are not even read (a photo is re-encoded far below it). */
        const val PHOTO_READ_BYTES = 60L * 1024 * 1024

        const val PHOTO_TOO_LARGE = "Das Foto ist zu groß. Höchstens 10 MB gehen."

        const val FILE_TOO_LARGE = "Die Datei ist größer als 50 MB. Größere Dateien gehen leider nicht."

        /** Where the chat's FileProvider hands decrypted files to other apps (`<applicationId>.chat.files`). */
        fun authority(context: Context): String = context.packageName + ".chat.files"

        /** What a bubble says when an attachment cannot be decrypted or read. */
        const val UNVERIFIABLE = "Anhang ließ sich nicht prüfen."

        /** The cache folder of the decrypted files (chat_file_paths.xml). */
        const val FOLDER = "chat-attachments"

        /** A file name may have at most this many UTF-8 bytes on Android's file systems (NAME_MAX of ext4 and f2fs). */
        const val MAX_NAME_BYTES = 255

        /** Up to this many characters after the last dot count as the extension, which a shortened name keeps. */
        private const val MAX_EXTENSION_LENGTH = 16

        /**
         * [name] within [MAX_NAME_BYTES]: a message may name a file with 120 characters of up to 4 bytes each. Characters
         * come off the end of the name before its extension, so the app that opens it still knows the type; the bubble
         * shows the whole name.
         */
        internal fun storableName(name: String): String {
            fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size
            if (bytes(name) <= MAX_NAME_BYTES) return name
            val dot = name.lastIndexOf('.')
            val extension = if (dot > 0 && name.length - dot - 1 <= MAX_EXTENSION_LENGTH) name.substring(dot) else ""
            var base = name.substring(0, name.length - extension.length)
            while (bytes(base + extension) > MAX_NAME_BYTES) {
                base = base.substring(0, base.offsetByCodePoints(base.length, -1))
            }
            return base + extension
        }
    }
}

/**
 * Hands decrypted files to other apps (a class of its own, so it never clashes with other modules' providers). Android
 * creates it when the process starts: decrypted files that a killed process left in the cache are deleted then, before
 * anything could hand them out again – also when Android starts the process only because another app reads a file it
 * was given earlier, which then finds nothing: no decrypted file outlives the process that opened it.
 */
class ChatFileProvider : FileProvider(R.xml.chat_file_paths) {
    override fun onCreate(): Boolean {
        // Android attaches the context before it calls onCreate.
        File(context!!.cacheDir, AndroidChatMedia.FOLDER).deleteRecursively()
        return super.onCreate()
    }
}

/**
 * ACTION_VIEW ("Öffnen") or, wrapped in the system chooser, ACTION_SEND ("Teilen") of a decrypted file through
 * [ChatFileProvider] with its MIME type; the receiving app may only read it.
 */
fun fileIntent(context: Context, document: AttachmentView.Document, share: Boolean): Intent {
    val uri = FileProvider.getUriForFile(context, AndroidChatMedia.authority(context), document.file)
    val intent = if (share) {
        Intent(Intent.ACTION_SEND).setType(document.mime).putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, document.mime)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return if (share) Intent.createChooser(intent, "Datei teilen") else intent
}

/** Starts [intent]; false when no app on the device takes it. */
fun Context.startOrFalse(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}
