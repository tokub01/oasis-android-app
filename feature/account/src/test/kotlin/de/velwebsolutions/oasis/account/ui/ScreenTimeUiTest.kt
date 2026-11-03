package de.velwebsolutions.oasis.account.ui

import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.account.session
import de.velwebsolutions.oasis.account.ui.privacy.ScreenTimeContent
import de.velwebsolutions.oasis.account.ui.privacy.ScreenTimeViewModel
import de.velwebsolutions.oasis.core.screentime.PreferencesScreenTimeStore
import de.velwebsolutions.oasis.core.screentime.ScreenTime
import de.velwebsolutions.oasis.core.screentime.ScreenTimeDay
import de.velwebsolutions.oasis.core.screentime.ScreenTimeWeek
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** „Deine Zeit in Oasis“ at 360 dp, and nothing without an account. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class ScreenTimeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theWeekAt360() {
        val week = ScreenTimeWeek(
            listOf(
                ScreenTimeDay("Do", 45),
                ScreenTimeDay("Fr", 12),
                ScreenTimeDay("Sa", 0),
                ScreenTimeDay("So", 65),
                ScreenTimeDay("Mo", 30),
                ScreenTimeDay("Di", 8),
                ScreenTimeDay("Heute", 20)
            ),
            total = 180
        )
        compose.setContent { OasisTheme { ScreenTimeContent(week) } }
        compose.onNodeWithText("Zusammen 3 Std.").assertExists()
        compose.waitForIdle()
        compose.onAllNodes(isRoot())[0].captureRoboImage("build/outputs/roborazzi/account-screen-time.png")
    }

    @Test
    fun signedOutThereIsNoWeek() {
        val store = PreferencesScreenTimeStore(ApplicationProvider.getApplicationContext())
        val model = ScreenTimeViewModel(ScreenTime(store, userId = { null }), session(StubTransport(), TestScope()))
        model.refresh()
        assertNull(model.week.value)
    }
}
