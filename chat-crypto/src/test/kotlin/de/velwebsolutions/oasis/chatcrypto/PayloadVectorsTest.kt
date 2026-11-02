package de.velwebsolutions.oasis.chatcrypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The send effect in the message plaintext against the shared vectors (tests/Fixtures/chat-payload-vectors.json of the
 * Laravel project): Android builds every plaintext byte for byte like the web and reads every one the same way.
 */
class PayloadVectorsTest {
    @Serializable
    data class Vectors(val effects: List<String>, val build: List<Build>, val parse: List<Parse>) {
        @Serializable
        data class Build(val text: String, val attachments: List<MessageAttachment>, val effect: String?, val plaintext: String)

        @Serializable
        data class Parse(val plaintext: String, val text: String, val attachments: Int, val effect: String?)
    }

    private val vectors: Vectors by lazy {
        val text = checkNotNull(Vectors::class.java.getResource("/fixtures/chat-payload-vectors.json")).readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(Vectors.serializer(), text)
    }

    @Test
    fun theEffectsAreTheSameFour() {
        assertEquals(vectors.effects, MessagePayload.EFFECTS)
    }

    @Test
    fun everyPlaintextIsBuiltLikeOnTheWeb() {
        for (vector in vectors.build) {
            assertEquals(vector.plaintext, MessagePayload.plaintext(MessageContent(vector.text, vector.attachments, vector.effect)))
        }
    }

    @Test
    fun everyPlaintextIsReadLikeOnTheWeb() {
        for (vector in vectors.parse) {
            val content = MessagePayload.decode(vector.plaintext)
            assertEquals(vector.plaintext, vector.text, content.text)
            assertEquals(vector.plaintext, vector.attachments, content.attachments.size)
            assertEquals(vector.plaintext, vector.effect, content.effect)
        }
        // A number or a flag is no effect either (only a string from the list is).
        assertNull(MessagePayload.decode("""{"t":"oasis-message","v":2,"text":"Hi","attachments":[],"effect":5}""").effect)
    }

    @Test
    fun anUnknownEffectIsNeverSent() {
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.plaintext(MessageContent("Hallo", effect = "regen")) }
    }
}
