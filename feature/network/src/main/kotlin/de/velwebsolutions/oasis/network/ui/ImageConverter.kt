package de.velwebsolutions.oasis.network.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import de.velwebsolutions.oasis.network.api.ImageUpload
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a picked photo (often HEIC) into a JPEG the server accepts (JPEG, PNG, WebP or GIF), scaled down to at most
 * `maxEdge` pixels – the server re-encodes as WebP anyway (longest edge 2048 for Tropfen), so a smaller upload loses
 * nothing. GIFs, PNGs, WebPs and JPEGs that are already small enough are sent as they are. Re-encoding applies the EXIF
 * orientation and drops the camera data; the server strips it from passed-through files.
 */
object ImageConverter {
    /** The server's own limit is higher; larger files are re-encoded. */
    private const val PASSTHROUGH_BYTES = 7_000_000
    private const val JPEG_QUALITY = 85

    /** A file type with its extension and MIME type. */
    data class FileType(val ext: String, val mime: String)

    fun upload(data: ByteArray, name: String, maxEdge: Int = 2048): ImageUpload? {
        val passthrough = passthroughType(data)
        val size = pixelSize(data)
        if (passthrough != null && data.size < PASSTHROUGH_BYTES && size != null && max(size.first, size.second) <= maxEdge) {
            return ImageUpload(data, "$name.${passthrough.ext}", passthrough.mime)
        }
        val jpeg = jpegData(data, maxEdge) ?: return null
        return ImageUpload(jpeg, "$name.jpg", "image/jpeg")
    }

    /** Reads a picked or photographed image off the main thread and converts it. */
    suspend fun upload(context: Context, uri: Uri, name: String, maxEdge: Int = 2048): ImageUpload? = withContext(Dispatchers.IO) {
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

    /** Width and height in pixels without decoding the image, or null when Android cannot read it. */
    fun pixelSize(data: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        // Android sets both sides of a readable image, and -1 for both otherwise.
        return if (options.outWidth > 0) options.outWidth to options.outHeight else null
    }

    /** Decodes (HEIC too), scales the longest edge down to [maxEdge] and encodes as JPEG at 85 %. */
    fun jpegData(data: ByteArray, maxEdge: Int): ByteArray? {
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val factor = min(1.0, maxEdge.toDouble() / max(max(width, height), 1))
                decoder.setTargetSize(max(1, (width * factor).roundToInt()), max(1, (height * factor).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null
        // A decoded software bitmap always encodes; the result is not checked.
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    /** A small preview of a picked image for the composer (decoded at about [edge] pixels). */
    fun thumbnail(data: ByteArray, edge: Int = 192): Bitmap? {
        val size = pixelSize(data) ?: return null
        var sample = 1
        while (max(size.first, size.second) / (sample * 2) >= edge) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
