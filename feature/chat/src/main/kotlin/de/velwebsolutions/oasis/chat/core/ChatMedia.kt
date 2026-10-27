package de.velwebsolutions.oasis.chat.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.design.media.ImageCodec
import java.io.ByteArrayOutputStream
import java.io.File
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

    /** A voice message or video in the app cache (deleted when the chat closes), with a poster frame for videos. */
    class Playable(val file: File, val poster: ImageBitmap?) : AttachmentView
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

    /** Starts a voice message (AAC in MPEG-4); [onLimit] runs when 3 minutes are reached. False when it did not start. */
    fun startRecording(onLimit: () -> Unit): Boolean

    /** Ends the recording: the voice message, or null when nothing usable was recorded. */
    suspend fun finishRecording(localId: Long): DraftAttachment?

    /** Stops a running recording and throws it away. */
    fun cancelRecording()

    /** A decrypted file ready to show: photos decoded, voice messages and videos written to the cache for the players. */
    suspend fun present(kind: AttachmentKind, data: ByteArray): AttachmentView

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
    // MediaRecorder(Context) needs Android 12; the deprecated constructor works on every version the app supports.
    @Suppress("DEPRECATION") private val newRecorder: () -> MediaRecorder = { MediaRecorder() }
) : ChatMedia {
    private class Recording(val recorder: MediaRecorder, val file: File, val startedAt: Long)

    /** Leftovers of an earlier process are removed when the chat starts. */
    val folder: File = File(context.cacheDir, "chat-attachments").apply {
        deleteRecursively()
        mkdirs()
    }

    private var recording: Recording? = null
    private var counter = 0

    override suspend fun prepare(uri: Uri, localId: Long): DraftAttachment = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        when {
            mime.startsWith("image/") -> photo(read(uri, PHOTO_READ_BYTES, PHOTO_TOO_LARGE), localId)
            mime in MessagePayload.KINDS.getValue(AttachmentKind.Video.wire) -> video(uri, mime, localId)
            else -> throw AttachmentProblem("Das kann Oasis nicht verschicken. Wähl ein Foto oder ein Video.")
        }
    }

    private fun photo(data: ByteArray, localId: Long): DraftAttachment {
        val jpeg = ImageCodec.jpeg(data, PHOTO_EDGE) ?: throw AttachmentProblem("Dieses Foto ließ sich nicht öffnen.")
        if (jpeg.data.size > maxImageBytes) throw AttachmentProblem(PHOTO_TOO_LARGE)
        return DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", jpeg.data, jpeg.width, jpeg.height)
    }

    private fun video(uri: Uri, mime: String, localId: Long): DraftAttachment {
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
        if (durationMs == null) throw AttachmentProblem("Dieses Video ließ sich nicht öffnen.")
        if (durationMs > ChatAttachments.MAX_VIDEO_MS) {
            throw AttachmentProblem("Das Video ist länger als 60 Sekunden. Kürz es in deiner Galerie und wähl den kürzeren Ausschnitt.")
        }
        val data = read(uri, maxVideoBytes, "Das Video ist größer als 50 MB. Nimm bitte einen kürzeren Ausschnitt.")
        return DraftAttachment(localId, AttachmentKind.Video, mime, data, durationMs = durationMs)
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
        val file = File(folder, "sprachnachricht-${++counter}.m4a")
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

    override suspend fun present(kind: AttachmentKind, data: ByteArray): AttachmentView = withContext(Dispatchers.IO) {
        if (kind == AttachmentKind.Image) {
            ImageCodec.thumbnail(data, edge = SHOWN_EDGE)?.let { AttachmentView.Photo(it.asImageBitmap()) }
                ?: AttachmentView.Failed(UNVERIFIABLE)
        } else {
            val file = File(folder, "anhang-${++counter}.${if (kind == AttachmentKind.Video) "mp4" else "m4a"}").apply { writeBytes(data) }
            AttachmentView.Playable(file, if (kind == AttachmentKind.Video) poster(file) else null)
        }
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
        (view as? AttachmentView.Playable)?.file?.delete()
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

        /** What a bubble says when an attachment cannot be decrypted or read. */
        const val UNVERIFIABLE = "Anhang ließ sich nicht prüfen."
    }
}
