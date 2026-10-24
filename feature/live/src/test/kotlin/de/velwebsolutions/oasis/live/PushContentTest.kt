package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.model.PushPreview
import de.velwebsolutions.oasis.live.push.PushContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The text of a push: the generic line, swapped for the named one from the Oasis server. */
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
                "title" to "Oasis",
                "body" to "Es gibt Neues für dich.",
                "url" to "https://get-oasis.com/app/hinweise",
                "preview" to reference
            ),
            null,
            null
        )
        assertEquals("https://get-oasis.com/app/hinweise", generic.url)
        assertEquals(generic, generic.with(null))
        val named = generic.with(PushPreview("Jonas Weber", "Hat auf deinen Tropfen geantwortet."))
        assertEquals("Jonas Weber", named.title)
        assertEquals("Hat auf deinen Tropfen geantwortet.", named.body)
        assertEquals(generic.url, named.url)
    }

    @Test
    fun fallsBackToTheNotificationPartAndIgnoresStrangeLinks() {
        val content = PushContent.from(mapOf("url" to "javascript:alert(1)"), "Neue Nachricht", "Öffne Oasis")
        assertEquals("Neue Nachricht", content.title)
        assertEquals("Öffne Oasis", content.body)
        assertNull(content.url)
    }
}
