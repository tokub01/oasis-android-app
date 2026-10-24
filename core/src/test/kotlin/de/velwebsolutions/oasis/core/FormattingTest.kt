package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.format.RelativeTime
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.OasisJson
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test
    fun `parses carbon dates with any fraction`() {
        assertEquals(Instant.parse("2026-10-22T13:37:00Z"), OasisJson.parseInstant("2026-10-22T13:37:00.000000Z"))
        assertEquals(Instant.parse("2026-10-22T13:37:00Z"), OasisJson.parseInstant("2026-10-22T13:37:00Z"))
        assertEquals(Instant.parse("2026-10-21T22:00:00Z"), OasisJson.parseInstant("2026-10-22"))
    }

    @Test
    fun `formats german time in Berlin`() {
        val instant = Instant.parse("2026-10-22T12:05:00Z")
        assertEquals("22.10.2026, 14:05 Uhr", GermanTime.dayAndTime(instant))
    }

    @Test
    fun `relative time like the web`() {
        val now = Instant.parse("2026-10-22T12:00:00Z")
        assertEquals("gerade eben", RelativeTime.format(now.minusSeconds(30), now))
        assertEquals("vor 5 Min.", RelativeTime.format(now.minusSeconds(300), now))
        assertEquals("vor 3 Std.", RelativeTime.format(now.minusSeconds(3 * 3600), now))
        assertEquals("gestern", RelativeTime.format(now.minusSeconds(26 * 3600), now))
        assertEquals("12. Okt.", RelativeTime.format(Instant.parse("2026-10-12T12:00:00Z"), now))
    }

    @Test
    fun `german messages for errors`() {
        assertEquals("Das darfst du leider nicht.", ApiError.Forbidden("This action is unauthorized.").userMessage)
        assertEquals("Keine Verbindung zu Oasis. Bitte prüfe dein Internet.", ApiError.Transport("offline").userMessage)
    }

    @Test
    fun `days, older dates and the web's datetime-local values`() {
        val now = Instant.parse("2026-10-22T12:00:00Z")
        assertEquals("12. Okt. 2025", RelativeTime.format(Instant.parse("2025-10-12T12:00:00Z"), now))
        assertEquals("22.10.2026", GermanTime.day(now))
        assertEquals(Instant.parse("2026-10-22T12:30:00Z"), GermanTime.parse("2026-10-22T14:30", "yyyy-MM-dd'T'HH:mm"))
        assertEquals(null, GermanTime.parse("morgen", "yyyy-MM-dd'T'HH:mm"))
        assertEquals("14:00", GermanTime.format(now, "HH:mm"))
    }

    @Test
    fun `confirmations decode with and without a message`() {
        val confirmed = de.velwebsolutions.oasis.core.network.OasisJson.snake.decodeFromString(
            de.velwebsolutions.oasis.core.network.Confirmed.serializer(kotlinx.serialization.serializer<Int>()),
            """{"message":"Gespeichert.","data":3}"""
        )
        assertEquals(de.velwebsolutions.oasis.core.network.Confirmed(3, "Gespeichert."), confirmed)
        assertEquals(de.velwebsolutions.oasis.core.di.DeviceName("Pixel 9"), de.velwebsolutions.oasis.core.di.DeviceName("Pixel 9"))
        assertEquals(null, de.velwebsolutions.oasis.core.network.RealtimeConfig().hostOverride)
    }
}
