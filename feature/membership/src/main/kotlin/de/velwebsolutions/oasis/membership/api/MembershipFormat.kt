package de.velwebsolutions.oasis.membership.api

import de.velwebsolutions.oasis.core.format.GermanTime
import java.time.Instant

/** German formatting as the web shows it (`d.m.Y` in Europe/Berlin). */
object MembershipFormat {
    /** "11.11.2026"; empty without a date. */
    fun day(instant: Instant?): String = instant?.let(GermanTime::day).orEmpty()
}
