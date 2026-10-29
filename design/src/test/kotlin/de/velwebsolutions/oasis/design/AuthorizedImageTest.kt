package de.velwebsolutions.oasis.design

import android.content.Context
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import de.velwebsolutions.oasis.design.components.imageModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** What AuthorizedImage asks the image loader for; its disk cache keeps whatever it loads, `no-store` or not. */
@RunWith(RobolectricTestRunner::class)
class AuthorizedImageTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `an image kept off the disk is asked for without the disk cache`() {
        val url = "https://oasis.test/bild.png"
        assertEquals("other images load by their URL", url, imageModel(context, url, cacheOnDisk = true))
        val request = imageModel(context, "https://oasis.test/anhang/0", cacheOnDisk = false) as ImageRequest
        assertEquals("https://oasis.test/anhang/0", request.data)
        assertEquals(CachePolicy.DISABLED, request.diskCachePolicy)
    }
}
