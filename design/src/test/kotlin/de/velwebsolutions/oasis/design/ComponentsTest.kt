package de.velwebsolutions.oasis.design

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.ErrorStateView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.theme.OasisTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `button runs its action`() {
        var taps = 0
        compose.setContent { OasisTheme { OasisButton("Veröffentlichen", onClick = { taps++ }) } }
        compose.onNodeWithText("Veröffentlichen").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `error state shows the german message and retries`() {
        var retries = 0
        compose.setContent { OasisTheme { ErrorStateView(ApiError.Transport("offline"), onRetry = { retries++ }) } }
        compose.onNodeWithText("Keine Verbindung zu Oasis. Bitte prüfe dein Internet.").assertIsDisplayed()
        compose.onNodeWithText("Noch einmal versuchen").performClick()
        compose.waitForIdle()
        assertEquals(1, retries)
    }
}
