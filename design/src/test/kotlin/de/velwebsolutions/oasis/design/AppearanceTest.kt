package de.velwebsolutions.oasis.design

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.design.components.AppearancePicker
import de.velwebsolutions.oasis.design.theme.Appearance
import de.velwebsolutions.oasis.design.theme.AppearanceChoice
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisPalette
import de.velwebsolutions.oasis.design.theme.OasisTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** „Darstellung: Automatisch / Hell / Dunkel“ (docs/oasis-website.md): the stored choice, the system's mode and the tokens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class AppearanceTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = Appearance.preferences(context)

    @After
    fun reset() {
        Appearance.choose(AppearanceChoice.Automatic, preferences)
        Appearance.systemDark = false
    }

    private fun configuration(night: Boolean) = Configuration().apply {
        uiMode = (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO) or Configuration.UI_MODE_TYPE_NORMAL
    }

    @Test
    fun theDeviceChoiceIsStoredAndFollowsTheSystemWhenAutomatic() {
        Appearance.restore(preferences, configuration(night = true))
        assertEquals(AppearanceChoice.Automatic, Appearance.choice)
        assertTrue("automatic follows the system", Appearance.isDark)
        Appearance.choose(AppearanceChoice.Light, preferences)
        assertEquals("light", preferences.getString(Appearance.STORAGE_KEY, null))
        assertFalse(Appearance.isDark)
        Appearance.choose(AppearanceChoice.Dark, preferences)
        Appearance.restore(preferences, configuration(night = false))
        assertEquals(AppearanceChoice.Dark, Appearance.choice)
        assertTrue(Appearance.isDark)
        assertEquals(OasisPalette.Dark, Appearance.palette)
        Appearance.choose(AppearanceChoice.Automatic, preferences)
        assertNull("automatic removes the stored value", preferences.getString(Appearance.STORAGE_KEY, null))
        assertFalse(Appearance.isDark)
        assertEquals(AppearanceChoice.Automatic, AppearanceChoice.of("sepia"))
        assertEquals(AppearanceChoice.Automatic, AppearanceChoice.of(null))
        assertEquals(listOf("Automatisch", "Hell", "Dunkel"), AppearanceChoice.entries.map { it.label })
    }

    @Test
    fun theTokensAreTheWebsTable() {
        val light = OasisPalette.Light
        val dark = OasisPalette.Dark
        assertEquals(Color(0xFF0E2B2F), light.night)
        assertEquals(Color(0xFFF4EDE1), dark.night)
        assertEquals(Color(0xFF12353A), light.night2)
        assertEquals(Color(0xFFE6DDCC), dark.night2)
        assertEquals(Color(0xFF0F1A1B), dark.sand)
        assertEquals(Color(0xFFA3441F), light.danger)
        assertEquals(Color(0xFFF2906B), dark.danger)
        assertEquals(Color(0xFFFFFFFF), light.onDanger)
        assertEquals(Color(0xFF0B2326), dark.onDanger)
        assertEquals(Color(0xFF5CC4BA), dark.springText)
        Appearance.show(AppearanceChoice.Dark)
        val shown = listOf(
            OasisColors.Night, OasisColors.Night2, OasisColors.NightLine, OasisColors.Sand, OasisColors.Paper, OasisColors.Field,
            OasisColors.Dune, OasisColors.DuneLine, OasisColors.PaperLine, OasisColors.FieldLine, OasisColors.Ink, OasisColors.Ink2,
            OasisColors.Muted, OasisColors.Sun, OasisColors.SunInk, OasisColors.SunText, OasisColors.SunOnNight, OasisColors.Spring,
            OasisColors.SpringText, OasisColors.OnNight, OasisColors.OnNight2, OasisColors.OnNightMuted, OasisColors.Danger,
            OasisColors.OnDanger
        )
        assertEquals(
            listOf(
                dark.night, dark.night2, dark.nightLine, dark.sand, dark.paper, dark.field, dark.dune, dark.duneLine, dark.paperLine,
                dark.fieldLine, dark.ink, dark.ink2, dark.muted, dark.sun, dark.sunInk, dark.sunText, dark.sunOnNight, dark.spring,
                dark.springText, dark.onNight, dark.onNight2, dark.onNightMuted, dark.danger, dark.onDanger
            ),
            shown
        )
    }

    @Test
    fun thePickerSwitchesAtOnce() {
        compose.setContent { OasisTheme { AppearancePicker() } }
        compose.onNodeWithTag("appearance.Automatic").assertIsSelected()
        compose.onNodeWithTag("appearance.Dark").performClick()
        compose.waitForIdle()
        assertTrue(Appearance.isDark)
        compose.onNodeWithTag("appearance.Dark").assertIsSelected()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/design-appearance-dark.png")
        compose.onNodeWithTag("appearance.Light").performClick()
        compose.waitForIdle()
        assertEquals("light", preferences.getString(Appearance.STORAGE_KEY, null))
    }
}
