package de.velwebsolutions.oasis.core

import android.content.Context
import de.velwebsolutions.oasis.core.screentime.PreferencesScreenTimeStore
import de.velwebsolutions.oasis.core.screentime.ScreenTime
import de.velwebsolutions.oasis.core.screentime.ScreenTimeDay
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * „Deine Zeit in Mildcrowd“ (docs/mildcrowd-privacy.md, „Bildschirmzeit“): seconds while in front and used within the last
 * minute, per local day and account, 8 days kept, shown as whole minutes.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenTimeTest {
    private val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
    private val store = PreferencesScreenTimeStore(context).apply { clear() }

    /** A clock on the test's virtual time, starting on Tuesday, 3 November 2026, 23:59:00 UTC. */
    private fun TestScope.clock(start: Instant = Instant.parse("2026-11-03T23:59:00Z")) = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
    }

    @Test
    fun countsOnlyWhileUsedAndSplitsAtMidnight() = runTest {
        val time = ScreenTime(store, userId = { 7 }, clock = clock())
        assertFalse("off until switched on", time.enabled)
        time.enabled = true
        assertTrue(PreferencesScreenTimeStore(context).enabled)
        val counting = backgroundScope.launch { time.count() }
        // 60 s after coming to the front still count, then nothing until the next tap.
        advanceTimeBy(90_500)
        time.touched()
        advanceTimeBy(10_000)
        counting.cancel()
        runCurrent()
        val seconds = store.read(7)
        // Pending seconds go to the day they are written on (like the web): the last 15 of the minute after midnight.
        assertEquals(mapOf("2026-11-03" to 45L, "2026-11-04" to 25L), seconds)
        assertTrue("another account counts for itself", store.read(8).isEmpty())
    }

    @Test
    fun theWeekInWholeMinutesWithTodayLast() = runTest {
        store.write(
            7,
            mapOf("2026-10-27" to 9_999L, "2026-10-29" to 2_700L, "2026-11-01" to 3_900L, "2026-11-04" to 59L)
        )
        val time = ScreenTime(store, userId = { 7 }, clock = clock(Instant.parse("2026-11-04T10:00:00Z")))
        val week = time.week(7)
        assertEquals(
            listOf(
                ScreenTimeDay("Do", 45),
                ScreenTimeDay("Fr", 0),
                ScreenTimeDay("Sa", 0),
                ScreenTimeDay("So", 65),
                ScreenTimeDay("Mo", 0),
                ScreenTimeDay("Di", 0),
                ScreenTimeDay("Heute", 0)
            ),
            week.days
        )
        assertEquals("the day before the week does not count", 110L, week.total)
        assertEquals("0 Min.", ScreenTime.format(0))
        assertEquals("45 Min.", ScreenTime.format(45))
        assertEquals("1 Std. 5 Min.", ScreenTime.format(65))
        assertEquals("2 Std.", ScreenTime.format(120))
    }

    @Test
    fun onlyEightDaysAreKeptAndSignOutForgetsAll() = runTest {
        store.write(7, mapOf("2026-10-27" to 60L, "2026-10-28" to 60L))
        val time = ScreenTime(store, userId = { 7 }, clock = clock(Instant.parse("2026-11-04T10:00:00Z")))
        time.enabled = true
        val counting = backgroundScope.launch { time.count() }
        advanceTimeBy(15_500)
        assertEquals("written every 15 seconds", mapOf("2026-10-28" to 60L, "2026-11-04" to 15L), store.read(7))
        counting.cancel()
        runCurrent()
        time.clear()
        assertTrue(store.read(7).isEmpty())
        assertFalse("sign-out resets the switch", time.enabled)
    }

    @Test
    fun withoutAnAccountOrSecondsNothingIsWritten() = runTest {
        val time = ScreenTime(store, userId = { null }, clock = clock())
        time.enabled = true
        val counting = backgroundScope.launch { time.count() }
        advanceTimeBy(20_500)
        counting.cancel()
        runCurrent()
        ScreenTime(store, userId = { 7 }).save()
        assertTrue(store.read(7).isEmpty())
        // Switched off (the default), nothing counts even with an account.
        val off = ScreenTime(store, userId = { 7 }, clock = clock())
        off.enabled = false
        val idle = backgroundScope.launch { off.count() }
        advanceTimeBy(20_500)
        idle.cancel()
        runCurrent()
        assertTrue(store.read(7).isEmpty())
        // Something unreadable stored counts as nothing.
        context.getSharedPreferences("oasis.screenTime", Context.MODE_PRIVATE).edit()
            .putString(
                "7",
                "kaputt"
            ).putString(
                "9",
                "[60]"
            ).putString("10", "{\"2026").putString("8", """{"2026-11-03":"viel","2026-11-02":{},"2026-11-04":60}""").commit()
        assertTrue(store.read(7).isEmpty())
        assertTrue(store.read(9).isEmpty())
        assertTrue(store.read(10).isEmpty())
        assertEquals(mapOf("2026-11-04" to 60L), store.read(8))
    }
}
