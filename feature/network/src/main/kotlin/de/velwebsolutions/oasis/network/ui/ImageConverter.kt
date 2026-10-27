package de.velwebsolutions.oasis.network.ui

import android.content.Context
import android.net.Uri
import de.velwebsolutions.oasis.design.media.ImageCodec
import de.velwebsolutions.oasis.network.api.ImageEdge
import de.velwebsolutions.oasis.network.api.ImageUpload
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a picked photo (often HEIC) into a JPEG the server accepts (JPEG, PNG, WebP or GIF), scaled down to at most
 * `maxEdge` pixels – the server re-encodes as WebP anyway (longest edge for Tropfen: the composer's `image_edge`, 2048
 * or 4096 by tier), so a smaller upload loses nothing. GIFs, PNGs, WebPs and JPEGs that are already small enough are
 * sent as they are. Re-encoding ([ImageCodec], shared with the chat) applies the EXIF orientation and drops the camera
 * data; the server strips it from passed-through files.
 */
object ImageConverter {
    /** The server's own limit is higher; larger files are re-encoded. */
    private const val PASSTHROUGH_BYTES = 7_000_000

    /** A file type with its extension and MIME type. */
    data class FileType(val ext: String, val mime: String)

    fun upload(data: ByteArray, name: String, maxEdge: Int = ImageEdge.DEFAULT): ImageUpload? {
        val passthrough = passthroughType(data)
        val size = ImageCodec.pixelSize(data)
        if (passthrough != null && data.size < PASSTHROUGH_BYTES && size != null && max(size.first, size.second) <= maxEdge) {
            return ImageUpload(data, "$name.${passthrough.ext}", passthrough.mime)
        }
        val jpeg = ImageCodec.jpegData(data, maxEdge) ?: return null
        return ImageUpload(jpeg, "$name.jpg", "image/jpeg")
    }

    /** Reads a picked or photographed image off the main thread and converts it. */
    suspend fun upload(context: Context, uri: Uri, name: String, maxEdge: Int = ImageEdge.DEFAULT): ImageUpload? =
        withContext(Dispatchers.IO) {
            val data =
                // openInputStream only returns null when the provider crashed: that, like a missing file, ends in null.
                runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull() ?: return@withContext null
            withContext(Dispatchers.Default) { upload(data, name, maxEdge) }
        }

    /** Formats the server accepts as they are (detected by their magic bytes). */
    fun passthroughType(data: ByteArray): FileType? {
        val bytes = data.take(12).map { it.toInt() and 0xFF }
        fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
        return when {
            startsWith(0xFF, 0xD8, 0xFF) -> FileType("jpg", "image/jpeg")

            startsWith(0x89, 0x50, 0x4E, 0x47) -> FileType("png", "image/png")

            startsWith(0x47, 0x49, 0x46, 0x38) -> FileType("gif", "image/gif")

            bytes.size >= 12 && bytes.subList(0, 4) == listOf(0x52, 0x49, 0x46, 0x46) &&
                bytes.subList(8, 12) == listOf(0x57, 0x45, 0x42, 0x50) ->
                FileType("webp", "image/webp")

            else -> null
        }
    }
}
