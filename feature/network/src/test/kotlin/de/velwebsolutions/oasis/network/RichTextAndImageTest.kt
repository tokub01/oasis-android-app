package de.velwebsolutions.oasis.network

import androidx.compose.ui.text.LinkAnnotation
import de.velwebsolutions.oasis.network.api.TextSegment
import de.velwebsolutions.oasis.network.ui.ImageConverter
import de.velwebsolutions.oasis.network.ui.components.RichTextBuilder
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rich text of a Tropfen and the image formats the composer passes on (iOS `RichTextTests`, `ImageTests`). */
class RichTextAndImageTest {
    @Test
    fun `links mentions, topics and external links`() {
        val segments = listOf(
            TextSegment(TextSegment.Kind.Text, "Hallo "),
            TextSegment(TextSegment.Kind.Mention, "@jonas", username = "jonas"),
            TextSegment(TextSegment.Kind.Link, "example.org", url = "https://example.org/a"),
            TextSegment(TextSegment.Kind.Tag, "#Gärten", tag = "Gärten")
        )
        val text = RichTextBuilder.annotated(segments, "http://localhost".toHttpUrl())
        assertEquals("Hallo @jonasexample.org#Gärten", text.text)
        val links = text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("http://localhost/profil/jonas", "https://example.org/a", "http://localhost/themen/G%C3%A4rten"), links)
    }

    @Test
    fun `keeps small formats the server accepts and recognises them`() {
        assertEquals("image/png", ImageConverter.passthroughType(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0))?.mime)
        assertEquals("image/gif", ImageConverter.passthroughType(byteArrayOf(0x47, 0x49, 0x46, 0x38))?.mime)
        assertEquals("image/jpeg", ImageConverter.passthroughType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))?.mime)
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        assertEquals("webp", ImageConverter.passthroughType(webp)?.ext)
        assertNull(ImageConverter.passthroughType("ftypheic0000".toByteArray()))
    }
}
