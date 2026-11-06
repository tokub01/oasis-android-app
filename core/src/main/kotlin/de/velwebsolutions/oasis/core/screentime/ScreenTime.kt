package de.velwebsolutions.oasis.core.screentime

import android.content.Context
import androidx.core.content.edit
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The seconds per local calendar day (`YYYY-MM-DD`) of one account, kept only on this device. */
interface ScreenTimeStore {
    /** Whether this device counts at all: off until the member switches it on in „Deine Zeit in Oasis“. */
    var enabled: Boolean

    fun read(userId: Int): Map<String, Long>

    fun write(userId: Int, seconds: Map<String, Long>)

    /** Every account's seconds (sign-out). */
    fun clear()
}

/** [ScreenTimeStore] in the app's private preferences (never in a backup: the backup rules leave out all app storage). */
class PreferencesScreenTimeStore(context: Context) : ScreenTimeStore {
    private val preferences = context.getSharedPreferences("oasis.screenTime", Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = preferences.getBoolean(ENABLED, false)
        set(value) {
            preferences.edit { putBoolean(ENABLED, value) }
        }

    override fun read(userId: Int): Map<String, Long> {
        val stored = preferences.getString(userId.toString(), null) ?: return emptyMap()
        val fields = runCatching { Json.parseToJsonElement(stored) as? JsonObject }.getOrNull() ?: return emptyMap()
        return fields.mapNotNull { (day, value) -> (value as? JsonPrimitive)?.longOrNull?.let { day to it } }.toMap()
    }

    override fun write(userId: Int, seconds: Map<String, Long>) {
        val json = JsonObject(seconds.mapValues { JsonPrimitive(it.value) }).toString()
        preferences.edit { putString(userId.toString(), json) }
    }

    /** Every account's seconds and the switch (sign-out). */
    override fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val ENABLED = "enabled"
    }
}

/** One row of „Deine Zeit in Oasis“: „Heute“ or the weekday („Mo“ …) and its whole minutes. */
data class ScreenTimeDay(val label: String, val minutes: Long)

/** The last 7 days, oldest first, and their total in whole minutes. */
data class ScreenTimeWeek(val days: List<ScreenTimeDay>, val total: Long)

/**
 * „Deine Zeit in Oasis“ (docs/oasis-privacy.md, „Bildschirmzeit“; resources/js/screen-time.js): only while the member
 * switched counting on ([enabled], off by default, this device only), a second counts while Oasis is in the foreground
 * with the screen on ([count] runs only then) and the member did something within the last 60 seconds ([touched]);
 * seconds go to the device's local calendar day, only 8 days are kept, written at most every 15 seconds and when the
 * app leaves the foreground. Per account and never sent anywhere.
 */
class ScreenTime(
    private val store: ScreenTimeStore,
    private val userId: () -> Int?,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    @Volatile private var lastInput = 0L
    private var pending = 0L

    /** The switch in „Deine Zeit in Oasis“: counting starts or stops with the next second. */
    var enabled: Boolean
        get() = store.enabled
        set(value) {
            store.enabled = value
        }

    /** A tap, a key or a scroll. */
    fun touched() {
        lastInput = clock.millis()
    }

    /**
     * Counts while the caller runs it (the activity while it is resumed); coming to the front counts as doing something.
     * Saves what is pending when it ends.
     */
    suspend fun count(tickMillis: Long = TICK_MS) {
        touched()
        try {
            while (true) {
                delay(tickMillis)
                if (store.enabled && clock.millis() - lastInput <= IDLE_MS) {
                    pending += 1
                    if (pending >= SAVE_EVERY) save()
                }
            }
        } finally {
            withContext(NonCancellable) { save() }
        }
    }

    /** Adds the pending seconds to today and drops the days older than the kept ones. */
    fun save() {
        val seconds = pending
        pending = 0
        val id = userId() ?: return
        if (seconds == 0L) return
        val today = LocalDate.now(clock)
        val oldest = today.minusDays(KEEP_DAYS - 1).toString()
        val stored = store.read(id)
        val updated = stored + (today.toString() to (stored[today.toString()] ?: 0L) + seconds)
        store.write(id, updated.filterKeys { it >= oldest })
    }

    /** The account's last 7 days (today last) and their total. */
    fun week(id: Int): ScreenTimeWeek {
        val seconds = store.read(id)
        val today = LocalDate.now(clock)
        val days = (6L downTo 0L).map { back ->
            val day = today.minusDays(back)
            day to (seconds[day.toString()] ?: 0L)
        }
        return ScreenTimeWeek(
            days.map { (day, value) -> ScreenTimeDay(if (day == today) "Heute" else WEEKDAYS.getValue(day.dayOfWeek), value / 60) },
            days.sumOf { it.second } / 60
        )
    }

    /** Signed out: every account's seconds go. */
    fun clear() {
        pending = 0
        store.clear()
    }

    companion object {
        const val IDLE_MS = 60_000L
        const val SAVE_EVERY = 15L
        const val KEEP_DAYS = 8L
        private const val TICK_MS = 1_000L

        private val WEEKDAYS = mapOf(
            DayOfWeek.MONDAY to "Mo",
            DayOfWeek.TUESDAY to "Di",
            DayOfWeek.WEDNESDAY to "Mi",
            DayOfWeek.THURSDAY to "Do",
            DayOfWeek.FRIDAY to "Fr",
            DayOfWeek.SATURDAY to "Sa",
            DayOfWeek.SUNDAY to "So"
        )

        /** „0 Min.“, „45 Min.“, „1 Std. 5 Min.“, „2 Std.“ */
        fun format(minutes: Long): String {
            val hours = minutes / 60
            val rest = minutes % 60
            return when {
                hours == 0L -> "$rest Min."
                rest == 0L -> "$hours Std."
                else -> "$hours Std. $rest Min."
            }
        }
    }
}
