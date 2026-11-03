package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.live.api.QuietMode
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Ruhemodus as the server last named it (`live/counts`, the hint settings and their changes): one state for the
 * calm banner on every screen and for Hinweise einstellen (docs/oasis-notifications.md, „Ruhemodus“).
 */
@Singleton
class QuietModeState @Inject constructor() {
    private val mutable = MutableStateFlow(QuietMode())

    val mode: StateFlow<QuietMode> = mutable.asStateFlow()

    /** On or off and until when; the choices stay as known (the counts do not repeat them). */
    fun update(mode: QuietMode) {
        mutable.value = mode.copy(modes = mode.modes.ifEmpty { mutable.value.modes })
    }

    /** Signed out: nothing is known any more. */
    fun clear() {
        mutable.value = QuietMode()
    }

    companion object {
        /**
         * „Ruhemodus bis 07:00 Uhr.“ on the same German day, „Ruhemodus bis Mittwoch, 4. November, 07:00 Uhr.“ later, and
         * „Ruhemodus an, bis du ihn ausschaltest.“ without an end (`UpdateHintSettings::quietModeStatus`).
         */
        fun status(until: Instant?, now: Instant = Instant.now()): String {
            if (until == null) return "Ruhemodus an, bis du ihn ausschaltest."
            val today = GermanTime.format(until, "yyyy-MM-dd") == GermanTime.format(now, "yyyy-MM-dd")
            return "Ruhemodus bis " + GermanTime.format(until, if (today) "HH:mm" else "EEEE, d. MMMM, HH:mm") + " Uhr."
        }
    }
}
