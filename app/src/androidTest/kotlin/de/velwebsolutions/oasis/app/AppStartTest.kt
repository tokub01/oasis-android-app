package de.velwebsolutions.oasis.app

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app on a device: the session restores (or fails offline, as on CI without a backend) and the app ends on
 * the sign-in page or, with a stored session, on the Quelle – never on a crash or an endless start screen.
 */
@RunWith(AndroidJUnit4::class)
class AppStartTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsOnSignInOrTheQuelle() {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodes(hasText("Willkommen zurück.") or hasText("Quelle")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
