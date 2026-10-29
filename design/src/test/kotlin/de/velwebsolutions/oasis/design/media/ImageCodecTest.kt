package de.velwebsolutions.oasis.design.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Sizes, JPEG re-encoding and previews of photos (uploads and chat photos). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCodecTest {
    private fun png(width: Int, height: Int, color: Int = Color.GREEN): ByteArray = encoded(width, height, color, Bitmap.CompressFormat.PNG)

    private fun encoded(width: Int, height: Int, color: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use {
            bitmap.compress(format, 100, it)
            it.toByteArray()
        }
    }

    /** A JPEG of [width] × [height] whose EXIF orientation says "turn by 90°", as phone cameras write it. */
    private fun turnedJpeg(width: Int, height: Int): ByteArray {
        val jpeg = encoded(width, height, Color.GREEN, Bitmap.CompressFormat.JPEG)
        // APP1 "Exif": a big-endian TIFF header and one entry in the first IFD, Orientation (0x0112) = 6.
        val exif = intArrayOf(
            0xFF, 0xE1, 0x00, 0x22, 0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        ).map { it.toByte() }.toByteArray()
        return jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun `reads the size, scales the longest edge down and encodes as JPEG`() {
        assertEquals(400 to 200, ImageCodec.pixelSize(png(400, 200)))
        val jpeg = ImageCodec.jpegData(png(400, 200), maxEdge = 100)!!
        assertEquals(listOf(0xFF, 0xD8), jpeg.take(2).map { it.toInt() and 0xFF })
        assertEquals(100 to 50, ImageCodec.pixelSize(jpeg))
        val sized = ImageCodec.jpeg(png(300, 600), maxEdge = 200)!!
        assertEquals(100 to 200, sized.width to sized.height)
        assertEquals(sized.width to sized.height, ImageCodec.pixelSize(sized.data))
        assertEquals("small images keep their size", 40 to 20, ImageCodec.pixelSize(ImageCodec.jpegData(png(40, 20), maxEdge = 100)!!))
    }

    @Test
    fun `previews are decoded small and unreadable data gives nothing`() {
        val thumbnail = ImageCodec.thumbnail(png(800, 400), edge = 100)
        assertNotNull(thumbnail)
        assertTrue(thumbnail!!.width <= 400)
        assertNull(ImageCodec.pixelSize("kein bild".toByteArray()))
        assertNull(ImageCodec.jpegData("kein bild".toByteArray(), 100))
        assertNull(ImageCodec.thumbnail("kein bild".toByteArray()))
    }

    /** Logos and stickers are often transparent PNGs; JPEG has no transparency. */
    @Test
    fun `transparent pixels turn white in the JPEG, not black`() {
        val jpeg = ImageCodec.jpeg(png(20, 20, Color.TRANSPARENT), maxEdge = 100)!!
        val pixel = BitmapFactory.decodeByteArray(jpeg.data, 0, jpeg.data.size).getPixel(10, 10)
        val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        assertTrue("white, not black: ${Integer.toHexString(pixel)}", channels.all { it > 240 })
        val again = ImageCodec.jpeg(jpeg.data, maxEdge = 100)!!
        assertEquals("an opaque JPEG is encoded as it is", 20 to 20, again.width to again.height)
    }

    /** A JPEG small enough is uploaded as it is, turned only by its EXIF orientation; its preview turns with it. */
    @Test
    fun `previews stand upright like the photo`() {
        val preview = ImageCodec.thumbnail(turnedJpeg(80, 40), edge = 400)!!
        assertEquals(40 to 80, preview.width to preview.height)
    }
}
