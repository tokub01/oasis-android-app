package de.velwebsolutions.oasis.design.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** „Darstellung“ of this device: the system's mode, or always light or dark. */
enum class AppearanceChoice(val stored: String?, val label: String) {
    Automatic(null, "Automatisch"),
    Light("light", "Hell"),
    Dark("dark", "Dunkel");

    companion object {
        /** The choice a stored value names; „Automatisch“ for none or an unknown one. */
        fun of(stored: String?): AppearanceChoice = entries.firstOrNull { it.stored != null && it.stored == stored } ?: Automatic
    }
}

/**
 * „Darstellung: Automatisch / Hell / Dunkel“ (docs/oasis-website.md): Oasis follows the system unless this device chose
 * otherwise. Stored only on the device (`oasis-theme`: `light`, `dark`, or missing for automatic, like the web's
 * localStorage key); nothing goes to the server. Both values are snapshot state, so every screen follows a change.
 */
object Appearance {
    const val STORAGE_KEY = "oasis-theme"

    /** The preferences file of the choice (only on this device). */
    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("oasis-appearance", Context.MODE_PRIVATE)

    /** The choice of this device. */
    var choice by mutableStateOf(AppearanceChoice.Automatic)
        private set

    /** Whether the system is in dark mode (set by the activity from its configuration). */
    var systemDark by mutableStateOf(false)

    /** Whether Oasis shows its dark mode now. */
    val isDark: Boolean
        get() = when (choice) {
            AppearanceChoice.Automatic -> systemDark
            AppearanceChoice.Light -> false
            AppearanceChoice.Dark -> true
        }

    /** The token values shown now. */
    val palette: OasisPalette get() = if (isDark) OasisPalette.Dark else OasisPalette.Light

    /** Reads the stored choice and the system's mode (at the start of an activity). */
    fun restore(preferences: SharedPreferences, configuration: Configuration) {
        choice = AppearanceChoice.of(preferences.getString(STORAGE_KEY, null))
        systemDark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    /** Shows and remembers [chosen]; „Automatisch“ removes the stored value. */
    fun choose(chosen: AppearanceChoice, preferences: SharedPreferences) {
        choice = chosen
        preferences.edit { chosen.stored?.let { putString(STORAGE_KEY, it) } ?: remove(STORAGE_KEY) }
    }

    /** Shows [chosen] without storing it (screenshots of both modes in tests). */
    fun show(chosen: AppearanceChoice) {
        choice = chosen
    }
}
