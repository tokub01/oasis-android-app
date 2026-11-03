package de.velwebsolutions.oasis.chatcrypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned message and polls in the message plaintext against the shared vectors (tests/Fixtures/chat-payload-vectors.json,
 * `pins` and `polls`): built byte for byte like the web, read the same way, anything malformed ignored.
 */
class PinPollVectorsTest {
    private val vectors: JsonObject by lazy {
        val text = checkNotNull(PinPollVectorsTest::class.java.getResource("/fixtures/chat-payload-vectors.json")).readText()
        Json.parseToJsonElement(text).jsonObject
    }

    private val target = "A".repeat(43) + "B".repeat(43)

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun quote(element: Any?): MessageReply? = (element as? JsonObject)?.let {
        MessageReply(it.text("to"), it.text("sender"), it.text("kind"), it.text("excerpt"), it.text("expires_at"))
    }

    private fun wrapped(field: String) = """{"t":"oasis-message","v":2,"text":"","attachments":[],$field}"""

    @Test
    fun pinsAreBuiltAndReadLikeOnTheWeb() {
        val pins = vectors.getValue("pins").jsonObject
        for (vector in pins.getValue("build").jsonArray.map { it.jsonObject }) {
            assertEquals(vector.text("plaintext"), MessagePayload.pin(quote(vector["quote"])))
        }
        for (vector in pins.getValue("parse").jsonArray.map { it.jsonObject }) {
            val expected = when (val pin = vector["pin"]) {
                is JsonObject -> MessagePin(if (pin.text("to").isEmpty()) null else quote(pin))
                else -> null
            }
            val content = MessagePayload.decode(vector.text("plaintext"))
            assertEquals(vector.text("plaintext"), expected, content.pin)
            assertEquals(expected != null, content.control)
        }
        assertNull(MessagePayload.decode(wrapped(""""pin":"$target"""")).pin)
        val malformed = MessageReply(target, "12", "once", "Geheim", "2026-11-10T12:00:00Z")
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.pin(malformed) }
    }

    @Test
    fun pollsVotesAndTheirEndAreBuiltLikeOnTheWeb() {
        for (vector in vectors.getValue("polls").jsonObject.getValue("build").jsonArray.map { it.jsonObject }) {
            val vote = vector["vote"] as? JsonObject
            val built = when {
                vote != null -> MessagePayload.vote(vote.text("poll"), vote.getValue("options").jsonArray.map { it.jsonPrimitive.int })

                vector.containsKey("close") -> MessagePayload.pollClose(vector.text("close"))

                else -> MessagePayload.poll(
                    vector.text("question"),
                    vector.getValue("options").jsonArray.map { it.jsonPrimitive.content },
                    vector.getValue("multiple").jsonPrimitive.boolean
                )
            }
            assertEquals(vector.text("plaintext"), built)
        }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.poll(" ", listOf("a", "b"), false) }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.poll("Q", listOf("a"), false) }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.poll("Q", List(7) { "a" }, false) }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.poll("Q", listOf("a", " "), false) }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.vote(target, listOf(1, 1)) }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.vote(target, listOf(6)) }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.vote(target, listOf(-1)) }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.vote("kurz", emptyList()) }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.pollClose("kurz") }
    }

    @Test
    fun pollsVotesAndTheirEndAreReadLikeOnTheWeb() {
        for (vector in vectors.getValue("polls").jsonObject.getValue("parse").jsonArray.map { it.jsonObject }) {
            val plaintext = vector.text("plaintext")
            val content = MessagePayload.decode(plaintext)
            val poll = (vector["poll"] as? JsonObject)?.let { poll ->
                MessagePoll(
                    poll.text("question"),
                    poll.getValue("options").jsonArray.map {
                        it.jsonPrimitive.content
                    },
                    poll.getValue("multiple").jsonPrimitive.boolean
                )
            }
            val vote = (vector["vote"] as? JsonObject)?.let { vote ->
                MessageVote(vote.text("poll"), vote.getValue("options").jsonArray.map { it.jsonPrimitive.int })
            }
            val close = (vector["poll_close"] as? JsonObject)?.text("poll")
            assertEquals(plaintext, poll, content.poll)
            assertEquals(plaintext, vote, content.vote)
            assertEquals(plaintext, close, content.pollClose)
        }
        // Not objects, missing or mistyped fields: nothing.
        val broken = listOf(
            """"poll":"Q"""",
            """"poll":{"options":["a","b"],"multiple":false}""",
            """"poll":{"question":"Q","options":"a","multiple":false}""",
            """"poll":{"question":"Q","options":["a",2],"multiple":false}""",
            """"poll":{"question":"Q","options":["a","b"],"multiple":"ja"}""",
            """"poll":{"question":"Q","options":["a","b"]}""",
            """"vote":[1]""",
            """"vote":{"options":[1]}""",
            """"vote":{"poll":"$target","options":1}""",
            """"vote":{"poll":"$target","options":["1"]}""",
            """"vote":{"poll":"$target","options":[{}]}""",
            """"vote":{"poll":"kurz","options":[]}""",
            """"vote":{"poll":"$target","options":[-1]}""",
            """"poll_close":"$target"""",
            """"poll_close":{"poll":"kurz"}"""
        )
        for (field in broken) {
            val content = MessagePayload.decode(wrapped(field))
            assertNull(field, content.poll ?: content.vote ?: content.pollClose)
            assertFalse(field, content.control)
        }
        assertEquals(
            MessagePoll("Q", listOf("a", "b"), true),
            MessagePayload.decode(wrapped(""""poll":{"question":"Q","options":["a","b"],"multiple":true}""")).poll
        )
        assertTrue(MessagePayload.decode(wrapped(""""vote":{"poll":"$target","options":[]}""")).control)
        assertTrue(MessagePayload.decode(MessagePayload.pollClose(target)).control)
        assertTrue(MessagePayload.decode(MessagePayload.reaction(target, "")).control)
    }
}
