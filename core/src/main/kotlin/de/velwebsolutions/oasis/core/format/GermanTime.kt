package de.velwebsolutions.oasis.core.format

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Dates as the web shows them: German words, German time (Europe/Berlin), whatever the device is set to. Formatters are
 * built once per pattern and shared, because lists format a date in every row.
 */
object GermanTime {
    val zone: ZoneId = ZoneId.of("Europe/Berlin")
    val locale: Locale = Locale.GERMANY

    private val formatters = ConcurrentHashMap<String, DateTimeFormatter>()

    /** `instant` in a pattern such as "d. MMMM yyyy, HH:mm". */
    fun format(instant: Instant, pattern: String, zone: ZoneId = this.zone): String = formatter(pattern).withZone(zone).format(instant)

    /** Parses `text` in `pattern` as wall-clock time in `zone`, e.g. the web's `datetime-local` values. */
    fun parse(text: String, pattern: String, zone: ZoneId = this.zone): Instant? =
        runCatching { LocalDateTime.parse(text, formatter(pattern)).atZone(zone).toInstant() }.getOrNull()

    /** "22.10.2026". */
    fun day(instant: Instant): String = format(instant, "dd.MM.yyyy")

    /** "22.10.2026, 14:05 Uhr". */
    fun dayAndTime(instant: Instant): String = format(instant, "dd.MM.yyyy, HH:mm") + " Uhr"

    private fun formatter(pattern: String): DateTimeFormatter =
        formatters.getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern, locale) }
}
