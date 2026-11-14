package de.velwebsolutions.oasis.design.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decoding, scaling and JPEG encoding of photos, shared by the uploads of Posts, profiles and crowds (the network's
 * `ImageConverter`) and the photos of the encrypted chat. Re-encoding applies the EXIF orientation, drops the camera
 * data and lays transparent images onto white (JPEG has no transparency: it would turn them black); previews stand
 * upright like the photo as well.
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
                decoder.isMutableRequired = true
            }
        }.getOrNull() ?: return null
        // White behind the picture, in the same bitmap: a transparent pixel would be black in the JPEG.
        if (bitmap.hasAlpha()) Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
        // A decoded software bitmap always encodes; the result is not checked.
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            val jpeg = Jpeg(output.toByteArray(), bitmap.width, bitmap.height)
            bitmap.recycle()
            jpeg
        }
    }

    /**
     * A small preview of an image (decoded at about [edge] pixels), or null when Android cannot read it. Decoded like
     * [jpeg], so a camera photo that is turned only by its EXIF orientation (a small JPEG is sent as it is) stands
     * upright, as in the gallery.
     */
    fun thumbnail(data: ByteArray, edge: Int = 192): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
            var sample = 1
            while (max(info.size.width, info.size.height) / (sample * 2) >= edge) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()
}
