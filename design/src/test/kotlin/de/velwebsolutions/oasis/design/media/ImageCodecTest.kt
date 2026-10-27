package de.velwebsolutions.oasis.design.media

import android.graphics.Bitmap
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
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
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
}
