package de.velwebsolutions.oasis.membership.api

import de.velwebsolutions.oasis.core.format.GermanTime
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** German formatting as the web shows it (`d.m.Y` in Europe/Berlin). */
object MembershipFormat {
    private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", GermanTime.locale)

    /** "11.11.2026"; empty without a date. */
    fun day(instant: Instant?): String = instant?.let(GermanTime::day).orEmpty()

    /** "21.12.2026" for a calendar day (`effective_on`); empty without one. */
    fun calendarDay(day: LocalDate?): String = day?.format(dayFormatter).orEmpty()

    /** "06.11.2026 um 14:32 Uhr" in Berlin time, as the confirmations name a moment (TermsObjections::moment); empty without one. */
    fun moment(instant: Instant?): String =
        instant?.let { GermanTime.format(it, "dd.MM.yyyy") + " um " + GermanTime.format(it, "HH:mm") + " Uhr" }.orEmpty()
}
