package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.onboarding.OnboardingCardStore
import de.velwebsolutions.oasis.core.onboarding.PreferencesOnboardingCardStore
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** „Später“ on the card „Mach deine Startseite lebendig“ is kept on this device. */
@RunWith(RobolectricTestRunner::class)
class OnboardingCardTest {
    @Test
    fun `the snooze is kept in the preferences`() {
        val store = PreferencesOnboardingCardStore(RuntimeEnvironment.getApplication())
        assertNull(store.snoozedUntil())
        val until = Instant.parse("2026-11-17T10:00:00Z")
        store.snooze(until)
        assertEquals(until, PreferencesOnboardingCardStore(RuntimeEnvironment.getApplication()).snoozedUntil())
        assertEquals(Duration.ofDays(14), OnboardingCardStore.SNOOZE)
    }
}
