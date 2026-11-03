package de.velwebsolutions.oasis.core.onboarding

import android.content.Context
import androidx.core.content.edit
import java.time.Duration
import java.time.Instant

/**
 * „Später“ on the Quelle's card „Mach dein Tal lebendig“ (docs/oasis-accounts.md, „Nach der Registrierung“): until when
 * the card stays hidden on this device.
 */
interface OnboardingCardStore {
    fun snoozedUntil(): Instant?

    fun snooze(until: Instant)

    companion object {
        /** How long „Später“ hides the card. */
        val SNOOZE: Duration = Duration.ofDays(14)
    }
}

/** [OnboardingCardStore] in the app's private preferences. */
class PreferencesOnboardingCardStore(context: Context) : OnboardingCardStore {
    private val preferences = context.getSharedPreferences("oasis.onboardingCard", Context.MODE_PRIVATE)

    override fun snoozedUntil(): Instant? = preferences.getLong(KEY, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)

    override fun snooze(until: Instant) {
        preferences.edit { putLong(KEY, until.toEpochMilli()) }
    }

    private companion object {
        const val KEY = "snoozed-until"
    }
}
