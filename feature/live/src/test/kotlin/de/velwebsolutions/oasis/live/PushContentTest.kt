package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.model.PushPreview
import de.velwebsolutions.oasis.live.push.PushChannel
import de.velwebsolutions.oasis.live.push.PushContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The text of a push: the generic line, swapped for the named one from the Mildcrowd server. */
class PushContentTest {
    private val reference = "Abc123Abc123Abc123Abc123Abc123Abc123Abc1"

    @Test
    fun readsOnlyAWellFormedReference() {
        assertEquals(reference, PushPreview.reference(mapOf("preview" to reference)))
        assertNull(PushPreview.reference(emptyMap()))
        assertNull(PushPreview.reference(mapOf("preview" to "kurz")))
        assertNull(PushPreview.reference(mapOf("preview" to "ä".repeat(40))))
        assertNull(PushPreview.reference(mapOf("preview" to "../../api/v1/auth/me/../../../../../../..")))
    }

    @Test
    fun swapsInTheNamedTextAndKeepsTheGenericLineWithoutIt() {
        val generic = PushContent.from(
            mapOf(
                "title" to "Mildcrowd",
                "body" to "Es gibt Neues für dich.",
                "url" to "https://mildcrowd.com/app/hinweise",
                "preview" to reference
            ),
            null,
            null
        )
        assertEquals("https://mildcrowd.com/app/hinweise", generic.url)
        assertEquals(generic, generic.with(null))
        val named = generic.with(PushPreview("Jonas Weber", "Hat auf deinen Post geantwortet."))
        assertEquals("Jonas Weber", named.title)
        assertEquals("Hat auf deinen Post geantwortet.", named.body)
        assertEquals(generic.url, named.url)
    }

    @Test
    fun fallsBackToTheNotificationPartAndIgnoresStrangeLinks() {
        val content = PushContent.from(mapOf("url" to "javascript:alert(1)"), "Neue Nachricht", "Öffne Mildcrowd")
        assertEquals("Neue Nachricht", content.title)
        assertEquals("Öffne Mildcrowd", content.body)
        assertNull(content.url)
    }

    @Test
    fun chatMessagesGetTheirOwnChannel() {
        val chat = PushContent.from(mapOf("tag" to "oasis-chat", "url" to "https://mildcrowd.com/app/nachrichten"), null, null)
        assertEquals(PushChannel.Chat, chat.channel)
        assertEquals("oasis-chat", chat.tag)
        val chatByLink = PushContent.from(mapOf("url" to "https://mildcrowd.com/app/nachrichten/12"), null, null)
        assertEquals(PushChannel.Chat, chatByLink.channel)
        val hint = PushContent.from(mapOf("tag" to "oasis-hinweise", "url" to "https://mildcrowd.com/app/hinweise"), null, null)
        assertEquals(PushChannel.Hints, hint.channel)
        assertEquals(PushChannel.Hints, PushContent.from(emptyMap(), null, null).channel)
        assertEquals(PushChannel.Hints, PushContent.from(mapOf("tag" to " ", "url" to "mildcrowd://app/hinweise"), null, null).channel)
        assertNull("a blank tag is no tag", PushContent.from(mapOf("tag" to " "), null, null).tag)
        assertEquals(
            "http://localhost/app/nachrichten",
            PushContent.from(mapOf("url" to "http://localhost/app/nachrichten"), null, null).url
        )
        assertEquals("mildcrowd://app/hinweise", PushContent.from(mapOf("url" to "mildcrowd://app/hinweise"), null, null).url)
    }
}
