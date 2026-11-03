package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.core.ChatSpam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared spam vectors (tests/Fixtures/chat-spam-vectors.json in the Laravel repo, copied byte-identical). */
class ChatSpamTest {
    private val vectors: JsonObject =
        Json.parseToJsonElement(
            checkNotNull(ChatSpamTest::class.java.getResource("/fixtures/chat-spam-vectors.json")).readText()
        ).jsonObject

    @Test
    fun decidesEveryDraftLikeTheVectors() {
        for (draft in vectors.getValue("drafts").jsonArray.map { it.jsonObject }) {
            val text = draft.getValue("text").jsonPrimitive.content
            assertEquals(text, draft.getValue("shouting").jsonPrimitive.boolean, ChatSpam.isShouting(text))
            assertEquals(text, draft.getValue("repetitive").jsonPrimitive.boolean, ChatSpam.isRepetitive(text))
            val hint = when (draft.getValue("hint").jsonPrimitive.contentOrNull) {
                "shouting" -> ChatSpam.SHOUTING_HINT
                "repetition" -> ChatSpam.REPETITION_HINT
                else -> null
            }
            assertEquals(text, hint, ChatSpam.draftHint(text))
        }
    }

    @Test
    fun decidesEveryFloodLikeTheVectors() {
        for (flood in vectors.getValue("flood").jsonArray.map { it.jsonObject }) {
            val guard = ChatSpam.FloodGuard()
            val waits = flood.getValue("sends").jsonArray.map { send ->
                val (at, text) = send.jsonArray
                val now = at.jsonPrimitive.double
                guard.wait(now, text.jsonPrimitive.content).also { if (it == 0) guard.record(now, text.jsonPrimitive.content) }
            }
            assertEquals(
                flood.getValue("name").jsonPrimitive.content,
                flood.getValue("waits").jsonArray.map {
                    it.jsonPrimitive.int
                },
                waits
            )
        }
    }

    @Test
    fun usesTheSharedTexts() {
        val texts = vectors.getValue("texts").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(texts["shouting"], ChatSpam.SHOUTING_HINT)
        assertEquals(texts["repetition"], ChatSpam.REPETITION_HINT)
        assertEquals(texts["send_anyway"], ChatSpam.SEND_ANYWAY)
        assertEquals(texts["flood"], ChatSpam.FLOOD_PAUSE)
        assertEquals(texts.getValue("countdown").replace("{seconds}", "27"), ChatSpam.countdown(27))
        assertEquals(
            vectors.getValue("rules").jsonObject.getValue("server").jsonObject.getValue("message").jsonPrimitive.content,
            ChatSpam.FLOOD_PAUSE
        )
    }

    @Test
    fun aPauseOfTheServerCountsDown() {
        val guard = ChatSpam.FloodGuard()
        assertEquals(0, guard.remaining(0.0))
        guard.pause(0.0, 30)
        assertEquals(30, guard.remaining(0.0))
        assertEquals(1, guard.remaining(29.5))
        assertEquals(0, guard.remaining(30.0))
        repeat(30) { guard.record(it.toDouble() + 100, "m$it") }
        assertEquals(30, guard.wait(130.0, "x"))
    }
}
