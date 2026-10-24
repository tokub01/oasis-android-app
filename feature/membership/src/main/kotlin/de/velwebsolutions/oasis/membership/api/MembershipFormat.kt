package de.velwebsolutions.oasis.membership.api

import de.velwebsolutions.oasis.core.format.GermanTime
import java.text.NumberFormat
import java.time.Instant
import kotlin.math.ceil

/** German formatting as the web shows it (`d.m.Y` in Europe/Berlin) and the trial countdown of the app layout. */
object MembershipFormat {
    /** "11.11.2026"; empty without a date. */
    fun day(instant: Instant?): String = instant?.let(GermanTime::day).orEmpty()

    /** "1 Tag" / "5 Tage". */
    fun days(count: Int): String = if (count == 1) "1 Tag" else "$count Tage"

    /** "1.000" – German thousands separator like `number_format(…, 0, ',', '.')`. */
    fun count(value: Int): String = NumberFormat.getIntegerInstance(GermanTime.locale).format(value)

    /**
     * Days left in the free trial for the banner of the app layout, rounded up like the web
     * (`ceil(now()->diffInDays(trial_ends_at))`); null once it is over or while the member has a paid or gifted
     * membership ([membershipTier] is set).
     */
    fun trialBannerDaysLeft(trialEndsAt: Instant?, membershipTier: String?, now: Instant = Instant.now()): Int? {
        if (membershipTier != null || trialEndsAt == null || !trialEndsAt.isAfter(now)) return null
        val seconds = (trialEndsAt.toEpochMilli() - now.toEpochMilli()) / 1000.0
        return ceil(seconds / 86_400).toInt()
    }
}
