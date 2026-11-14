package de.velwebsolutions.oasis.core.format

import java.time.Duration
import java.time.Instant

/**
 * "vor 5 Min." / "vor 3 Tagen" / "12. Okt." – the relative time of the meta line in every feed (docs/mildcrowd-network.md):
 * under a minute „gerade eben“, then minutes, hours, days up to a week, then the date (with the year when it is not this
 * year's).
 */
object RelativeTime {
    fun format(instant: Instant, now: Instant = Instant.now()): String {
        val seconds = Duration.between(instant, now).seconds
        if (seconds < 60) return "gerade eben"
        if (seconds < 3600) return "vor ${seconds / 60} Min."
        if (seconds < 86400) return "vor ${seconds / 3600} Std."
        val days = seconds / 86400
        if (days < 7) return if (days == 1L) "vor 1 Tag" else "vor $days Tagen"
        val day = instant.atZone(GermanTime.zone).toLocalDate()
        val short = "${day.dayOfMonth}. ${MONTHS[day.monthValue - 1]}"
        return if (day.year == now.atZone(GermanTime.zone).year) short else "$short ${day.year}"
    }

    /** The web's fixed abbreviations (not the locale's): „3. Nov.“, „14. Mai“, „2. Sept.“. */
    private val MONTHS = listOf("Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sept.", "Okt.", "Nov.", "Dez.")
}
