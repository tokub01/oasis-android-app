package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.PushPreview
import de.velwebsolutions.oasis.core.model.failing
import de.velwebsolutions.oasis.core.model.map
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.InstantSerializer
import de.velwebsolutions.oasis.core.network.LocalDateSerializer
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.core.realtime.NoRealtime
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun `loadable keeps loaded values while refreshing and after a failed refresh`() {
        val loaded: Loadable<Int> = Loadable.Loaded(3)
        assertEquals(loaded, loaded.refreshing())
        assertEquals(Loadable.Loading, Loadable.Idle.refreshing())
        assertEquals(loaded, loaded.failing(ApiError.NotFound))
        assertEquals(Loadable.Failed(ApiError.NotFound), (Loadable.Loading as Loadable<Int>).failing(ApiError.NotFound))
        assertEquals(3, loaded.valueOrNull)
        assertNull(loaded.errorOrNull)
        assertEquals(ApiError.NotFound, Loadable.Failed(ApiError.NotFound).errorOrNull)
        assertTrue(Loadable.Loading.isLoading)
        assertFalse(loaded.isLoading)
    }

    @Test
    fun `loadable maps only loaded values`() {
        assertEquals(Loadable.Loaded("3"), (Loadable.Loaded(3) as Loadable<Int>).map { it.toString() })
        assertEquals(Loadable.Idle, (Loadable.Idle as Loadable<Int>).map { it.toString() })
        assertEquals(Loadable.Loading, (Loadable.Loading as Loadable<Int>).map { it.toString() })
        val failed: Loadable<Int> = Loadable.Failed(ApiError.NotFound)
        assertEquals(failed, failed.map { it.toString() })
    }

    @Test
    fun `pages continue by cursor or by page number`() {
        assertEquals("abc", Page(listOf(1), Page.Meta(nextCursor = "abc")).nextCursor)
        assertEquals("3", Page(listOf(1), Page.Meta(currentPage = 2, lastPage = 5)).nextCursor)
        assertNull(Page(listOf(1), Page.Meta(currentPage = 5, lastPage = 5)).nextCursor)
        assertNull(Page(listOf(1), Page.Meta(currentPage = 5)).nextCursor)
        assertNull(Page<Int>(emptyList()).nextCursor)
    }

    @Test
    fun `push preview reference must be 40 letters and digits`() {
        val ref = "a".repeat(20) + "B1".repeat(10)
        assertEquals(ref, PushPreview.reference(mapOf("preview" to ref)))
        assertNull(PushPreview.reference(mapOf("preview" to "short")))
        assertNull(PushPreview.reference(mapOf("preview" to "ä".repeat(40))))
        assertNull(PushPreview.reference(emptyMap()))
    }

    @Test
    fun `realtime event fields as text`() = runTest {
        val event = RealtimeEvent(
            "InboxActivity",
            buildJsonObject {
                put("kind", "chat")
                put("conversation_id", 7)
                put("from", JsonNull)
            }
        )
        assertEquals("chat", event.string("kind"))
        assertEquals("7", event.string("conversation_id"))
        assertNull(event.string("from"))
        assertNull(event.string("missing"))
        assertNull(RealtimeEvent("x", JsonPrimitive(1)).string("kind"))
        val none = NoRealtime()
        assertFalse(none.isConnected.value)
        assertTrue(none.subscribe("private-inbox.1").toList().isEmpty())
    }

    @Test
    fun `dates in both directions`() {
        val instant = Instant.parse("2026-10-22T13:37:00.123456Z")
        assertEquals("\"2026-10-22T13:37:00.123Z\"", OasisJson.verbatim.encodeToString(InstantSerializer, instant))
        assertEquals(
            Instant.parse("2026-10-22T13:37:00.123Z"),
            OasisJson.verbatim.decodeFromString(InstantSerializer, "\"2026-10-22T13:37:00.123Z\"")
        )
        assertNull(OasisJson.parseInstant("gestern"))
        try {
            OasisJson.verbatim.decodeFromString(InstantSerializer, "\"gestern\"")
            throw AssertionError("expected an error")
        } catch (_: SerializationException) {
        }
        val day = LocalDate.of(2008, 2, 29)
        assertEquals("\"2008-02-29\"", OasisJson.verbatim.encodeToString(LocalDateSerializer, day))
        assertEquals(day, OasisJson.verbatim.decodeFromString(LocalDateSerializer, "\"2008-02-29T00:00:00Z\""))
        assertEquals("2008-02-29", OasisJson.formatDay(day))
        assertEquals("\"x\"", OasisJson.snake.encodeToString(String.serializer(), "x"))
    }

    @Test
    fun `every error has a german message`() {
        val messages = mapOf(
            ApiError.Validation("Zusammenfassung", emptyMap()) to "Zusammenfassung",
            ApiError.Validation("x", mapOf("body" to listOf("Zu lang."))) to "Zu lang.",
            ApiError.Unauthenticated to "Bitte melde dich erneut an.",
            ApiError.Forbidden("") to "Das darfst du leider nicht.",
            ApiError.Forbidden("Gesperrt.") to "Gesperrt.",
            ApiError.NotFound to "Das gibt es nicht (mehr) oder es ist nicht für dich sichtbar.",
            ApiError.Conflict("") to "Das geht gerade nicht.",
            ApiError.Conflict("Bitte E-Mail bestätigen.") to "Bitte E-Mail bestätigen.",
            ApiError.PayloadTooLarge to "Das ist zu groß.",
            ApiError.TooManyRequests(30) to "Zu viele Versuche. Bitte warte einen Moment.",
            ApiError.Server(500, null) to "Da ist etwas schiefgelaufen. Bitte versuch es gleich noch einmal.",
            ApiError.Server(503, "Wartung") to "Wartung",
            ApiError.Decoding("x") to "Die Antwort von Oasis war unerwartet. Bitte aktualisiere die App."
        )
        messages.forEach { (error, text) -> assertEquals(text, error.userMessage) }
        assertEquals(
            mapOf("body" to "Zu lang."),
            ApiError.Validation("x", mapOf("body" to listOf("Zu lang."), "none" to emptyList())).fieldMessages
        )
        assertEquals(emptyMap<String, String>(), ApiError.NotFound.fieldMessages)
        assertNull(ApiError.NotFound.message("body"))
        assertEquals(ApiError.NotFound, ApiError.NotFound.asApiError())
        assertEquals(ApiError.Transport("kaputt"), IllegalStateException("kaputt").asApiError())
        assertEquals(ApiError.Transport("java.lang.IllegalStateException"), IllegalStateException().asApiError())
    }
}
