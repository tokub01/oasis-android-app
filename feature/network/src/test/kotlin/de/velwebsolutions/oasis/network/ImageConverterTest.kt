package de.velwebsolutions.oasis.network

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.network.ui.ImageConverter
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Picked images on their way to the server: passed through when small, re-encoded when large (iOS `ImageTests`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageConverterTest {
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
    }

    @Test
    fun `a small PNG goes as it is`() {
        val data = png(40, 20)
        assertEquals(40 to 20, ImageConverter.pixelSize(data))
        val upload = ImageConverter.upload(data, "bild-1")!!
        assertEquals("bild-1.png", upload.filename)
        assertEquals("image/png", upload.mimeType)
        assertTrue(upload.data.contentEquals(data))
    }

    @Test
    fun `a large image is scaled down to a JPEG, and unreadable data is refused`() {
        val upload = ImageConverter.upload(png(400, 200), "banner", maxEdge = 100)!!
        assertEquals("banner.jpg", upload.filename)
        assertEquals("image/jpeg", upload.mimeType)
        assertNull(ImageConverter.upload("kein bild".toByteArray(), "x"))
        assertNull(ImageConverter.pixelSize("kein bild".toByteArray()))
        assertNull(ImageConverter.thumbnail("kein bild".toByteArray()))
    }

    @Test
    fun `the composer preview is decoded small`() {
        val thumbnail = ImageConverter.thumbnail(png(800, 400), edge = 100)
        assertNotNull(thumbnail)
        assertTrue(thumbnail!!.width <= 400)
    }

    @Test
    fun `reads a picked file and gives up on a missing one`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "bild.png").apply { writeBytes(png(30, 30)) }
        assertEquals("foto.png", ImageConverter.upload(context, Uri.fromFile(file), "foto")?.filename)
        assertNull(ImageConverter.upload(context, Uri.fromFile(File(context.cacheDir, "fehlt.png")), "foto"))
    }
}
