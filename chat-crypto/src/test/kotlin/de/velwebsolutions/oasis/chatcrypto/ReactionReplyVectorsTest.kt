package de.velwebsolutions.oasis.chatcrypto

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Reactions and replies in the message plaintext against the shared vectors (tests/Fixtures/chat-payload-vectors.json,
 * `reactions` and `replies`): built byte for byte like the web, read the same way, anything malformed ignored.
 */
class ReactionReplyVectorsTest {
    @Serializable
    data class Reply(
        val to: String,
        val sender: String,
        val kind: String,
        val excerpt: String,
        @SerialName("expires_at") val expiresAt: String
    ) {
        fun payload() = MessageReply(to, sender, kind, excerpt, expiresAt)
    }

    @Serializable
    data class Reaction(val to: String, val emoji: String)

    @Serializable
    data class Vectors(val reactions: Reactions, val replies: Replies) {
        @Serializable
        data class Reactions(val set: List<String>, val build: List<Build>, val parse: List<Parse>) {
            @Serializable
            data class Build(val to: String, val emoji: String, val plaintext: String)

            @Serializable
            data class Parse(val plaintext: String, val reaction: Reaction?)
        }

        @Serializable
        data class Replies(val build: List<Build>, val excerpt: List<Excerpt>, val parse: List<Parse>) {
            @Serializable
            data class Build(val text: String, val effect: String?, val reply: Reply, val plaintext: String)

            @Serializable
            data class Excerpt(
                val text: String,
                val attachments: List<Kind>,
                @SerialName("view_once") val viewOnce: Boolean,
                val kind: String,
                val excerpt: String
            )

            @Serializable
            data class Kind(val kind: String)

            @Serializable
            data class Parse(val plaintext: String, val reply: Reply?)
        }
    }

    private val vectors: Vectors by lazy {
        val text = checkNotNull(Vectors::class.java.getResource("/fixtures/chat-payload-vectors.json")).readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(Vectors.serializer(), text)
    }

    private val target = "A".repeat(43) + "B".repeat(43)

    private fun wrapped(field: String) = """{"t":"oasis-message","v":2,"text":"Ja","attachments":[],$field}"""

    @Test
    fun theSixReactionsInTheirOrder() {
        assertEquals(vectors.reactions.set, MessagePayload.REACTIONS)
    }

    @Test
    fun reactionsAreBuiltAndReadLikeOnTheWeb() {
        for (vector in vectors.reactions.build) {
            assertEquals(vector.plaintext, MessagePayload.reaction(vector.to, vector.emoji))
        }
        for (vector in vectors.reactions.parse) {
            val expected = vector.reaction?.let { MessageReaction(it.to, it.emoji) }
            assertEquals(vector.plaintext, expected, MessagePayload.decode(vector.plaintext).reaction)
        }
        // Without a target, or with an emoji that is no string: no reaction.
        assertNull(MessagePayload.decode(wrapped(""""reaction":{"emoji":"👍"}""")).reaction)
        assertNull(MessagePayload.decode(wrapped(""""reaction":{"to":"$target","emoji":1}""")).reaction)
        for (broken in listOf(
            """"to":5,"emoji":"👍"""",
            """"to":{},"emoji":"👍"""",
            """"to":"$target"""",
            """"to":"$target","emoji":{}"""
        )) {
            assertNull(broken, MessagePayload.decode(wrapped(""""reaction":{$broken}""")).reaction)
        }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.reaction(target, "🔥") }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.reaction("kurz", "👍") }
    }

    @Test
    fun repliesAreBuiltAndReadLikeOnTheWeb() {
        for (vector in vectors.replies.build) {
            val content = MessageContent(vector.text, effect = vector.effect, reply = vector.reply.payload())
            assertEquals(vector.plaintext, MessagePayload.plaintext(content))
        }
        for (vector in vectors.replies.parse) {
            assertEquals(vector.plaintext, vector.reply?.payload(), MessagePayload.decode(vector.plaintext).reply)
        }
        // A target that is no signature, a field that is no string, a reply that is no object: no quote.
        val good = """"sender":"12","kind":"text","excerpt":"Hi","expires_at":"2026-11-10T12:00:00Z""""
        assertNull(MessagePayload.decode(wrapped(""""reply":{"to":"kurz",$good}""")).reply)
        assertNull(
            MessagePayload.decode(wrapped(""""reply":{"to":"$target","sender":12,"kind":"text","excerpt":"","expires_at":"x"}""")).reply
        )
        assertNull(MessagePayload.decode(wrapped(""""reply":"$target"""")).reply)
        assertNull(
            MessagePayload.decode(wrapped(""""reply":{"to":"$target","sender":{},"kind":"text","excerpt":"","expires_at":"x"}""")).reply
        )
        assertNull(MessagePayload.decode(wrapped(""""reply":{"to":"$target","sender":"12","excerpt":"","expires_at":"x"}""")).reply)
        assertEquals("Ja", MessagePayload.decode(wrapped(""""reply":{"to":"kurz",$good}""")).text)
        val malformed = MessageReply(target, "12", "once", "Geheim", "2026-11-10T12:00:00Z")
        assertThrows(ChatCryptoException.InvalidInput::class.java) { MessagePayload.plaintext(MessageContent("Hi", reply = malformed)) }
    }

    @Test
    fun whatAReplyQuotes() {
        val expiry = Instant.parse("2026-11-10T12:00:00.250Z")
        for (vector in vectors.replies.excerpt) {
            val files = vector.attachments.map { MessageAttachment("1", it.kind, "", 1, "", "") }
            val reply = MessagePayload.replyTo(target, "12", expiry, vector.text, files, vector.viewOnce)
            assertEquals(vector.text, vector.kind, reply.kind)
            assertEquals(vector.text, vector.excerpt, reply.excerpt)
            assertEquals("2026-11-10T12:00:00Z", reply.expiresAt)
        }
        // Nothing to quote at all: an empty text quote.
        assertEquals("text", MessagePayload.replyTo(target, "12", expiry, " \n ", emptyList(), false).kind)
    }
}
