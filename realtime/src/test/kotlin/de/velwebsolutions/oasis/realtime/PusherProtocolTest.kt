package de.velwebsolutions.oasis.realtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frames of the Pusher protocol (version 7) as Reverb sends them, and its error code ranges. */
class PusherProtocolTest {
    @Test
    fun decodesThePayloadThatPusherEncodesAsAString() {
        val message = PusherMessage.parse("""{"event":"MessageSent","channel":"private-conversation.3","data":"{\"id\":7}"}""")!!
        assertEquals("MessageSent", message.event)
        assertEquals("private-conversation.3", message.channel)
        assertEquals(JsonObject(mapOf("id" to JsonPrimitive(7))), message.payload)
    }

    @Test
    fun keepsPayloadsThatAreNoJsonStringAsTheyAre() {
        assertEquals(JsonPrimitive("plain text"), PusherMessage.parse("""{"event":"e","data":"plain text"}""")!!.payload)
        assertEquals(JsonPrimitive(5), PusherMessage.parse("""{"event":"e","data":5}""")!!.payload)
        assertEquals(
            JsonObject(mapOf("code" to JsonPrimitive(4001))),
            PusherMessage.parse("""{"event":"e","data":{"code":4001}}""")!!.payload
        )
        val withoutData = PusherMessage.parse("""{"event":"pusher:pong"}""")!!
        assertEquals(JsonNull, withoutData.payload)
        assertNull(withoutData.channel)
    }

    @Test
    fun ignoresFramesWithoutAnEvent() {
        assertNull(PusherMessage.parse("no json"))
        assertNull(PusherMessage.parse("[1,2]"))
        assertNull(PusherMessage.parse("""{"data":"{}"}"""))
        assertNull(PusherMessage.parse("""{"event":null}"""))
        assertNull(PusherMessage.parse("""{"event":{"name":"x"}}"""))
    }

    @Test
    fun sortsTheErrorCodesIntoTheProtocolRanges() {
        val fatal = listOf(4000, 4099).map(::PusherError)
        val afterBackoff = listOf(3999, 4100, 4199, 4300).map(::PusherError)
        val atOnce = listOf(4200, 4299).map(::PusherError)
        assertTrue(fatal.all { it.isFatal && !it.reconnectsAtOnce })
        assertTrue(afterBackoff.none { it.isFatal || it.reconnectsAtOnce })
        assertTrue(atOnce.all { it.reconnectsAtOnce && !it.isFatal })
        assertFalse(PusherError(4200).isFatal)
        assertEquals("Pusher error 4001", PusherError(4001).message)
    }

    @Test
    fun writesFramesWithEventAndData() {
        assertEquals(
            """{"event":"pusher:subscribe","data":{"channel":"status"}}""",
            ReverbRealtime.frame("pusher:subscribe", JsonObject(mapOf("channel" to JsonPrimitive("status"))))
        )
        assertEquals("""{"event":"x","data":[]}""", ReverbRealtime.frame("x", JsonArray(emptyList())))
    }
}
