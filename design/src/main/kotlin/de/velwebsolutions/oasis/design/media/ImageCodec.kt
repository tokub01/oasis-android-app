package de.velwebsolutions.oasis.design.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decoding, scaling and JPEG encoding of photos, shared by the uploads of Tropfen, profiles and oases (the network's
 * `ImageConverter`) and the photos of the encrypted chat. Re-encoding applies the EXIF orientation and drops the camera
 * data.
 */
object ImageCodec {
    private const val JPEG_QUALITY = 85

    /** Width and height in pixels without decoding the image, or null when Android cannot read it. */
    fun pixelSize(data: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        // Android sets both sides of a readable image, and -1 for both otherwise.
        return if (options.outWidth > 0) options.outWidth to options.outHeight else null
    }

    /** A re-encoded photo with its size in pixels. */
    class Jpeg(val data: ByteArray, val width: Int, val height: Int)

    /** Decodes (HEIC too), scales the longest edge down to [maxEdge] and encodes as JPEG at 85 %. */
    fun jpegData(data: ByteArray, maxEdge: Int): ByteArray? = jpeg(data, maxEdge)?.data

    /** [jpegData] with the size of the result; null when Android cannot read the image. */
    fun jpeg(data: ByteArray, maxEdge: Int): Jpeg? {
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
            val jpeg = Jpeg(output.toByteArray(), bitmap.width, bitmap.height)
            bitmap.recycle()
            jpeg
        }
    }

    /** A small preview of an image (decoded at about [edge] pixels), or null when Android cannot read it. */
    fun thumbnail(data: ByteArray, edge: Int = 192): Bitmap? {
        val size = pixelSize(data) ?: return null
        var sample = 1
        while (max(size.first, size.second) / (sample * 2) >= edge) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
