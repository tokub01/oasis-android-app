package de.velwebsolutions.oasis.core.format

import java.time.Duration
import java.time.Instant

/** "vor 5 Min." / "gestern" / "12. Okt." – relative time in German like the web's timestamps. */
object RelativeTime {
    fun format(instant: Instant, now: Instant = Instant.now()): String {
        val seconds = Duration.between(instant, now).seconds
        if (seconds < 60) return "gerade eben"
        if (seconds < 3600) return "vor ${seconds / 60} Min."
        if (seconds < 86400) return "vor ${seconds / 3600} Std."
        val day = instant.atZone(GermanTime.zone).toLocalDate()
        val today = now.atZone(GermanTime.zone).toLocalDate()
        if (day == today.minusDays(1)) return "gestern"
        return GermanTime.format(instant, if (day.year == today.year) "d. MMM" else "d. MMM yyyy")
    }
}
