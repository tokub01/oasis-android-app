package de.velwebsolutions.oasis.account.hilt

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.account.UpdateNotesPrompt
import de.velwebsolutions.oasis.account.ui.releases.PreferencesReleaseNotesStore
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Was ist neu and „Neu in Oasis …“ after an update, as the app shows them (the graph's app version is 0.24.0). */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReleasesHiltTest : HiltScreenTest() {
    @Test
    fun theVersionsShowWithTheAndroidNotesAndHowOasisIsBuilt() {
        signIn()
        stub("GET", "releases", Fixtures.releases)
        show { AccountScreen(AccountRoute.Releases) }
        waitForTag("release.0.24.0")
        compose.onNodeWithText("DEINE APP: OASIS 0.24.0").assertIsDisplayed()
        compose.onNodeWithText("Wie Oasis entsteht").assertIsDisplayed()
        compose.onNodeWithText("Aktuell").assertExists()
        compose.onNodeWithText("• Der Chat startet ruhiger.").assertExists()
        assertEquals(0, compose.onAllNodes(hasText("• Die Versionen stehen auf der Website.")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("• Ein Fehler auf dem iPhone wurde behoben.")).fetchSemanticsNodes().size)
        compose.onNodeWithTag("release.0.16.0").assertExists()
        shot("releases")
    }

    @Test
    fun offlineTheScreenOffersToTryAgain() {
        signIn()
        stub("GET", "releases", """{"message":"Server Error"}""", status = 500)
        show { AccountScreen(AccountRoute.Releases) }
        waitForText("Noch einmal versuchen")
        stub("GET", "releases", Fixtures.releases.replace(""""about":"Oasis""", """"about":" \n\n Oasis"""))
        compose.onNodeWithText("Noch einmal versuchen").tap()
        waitForTag("release.0.24.0")
    }

    @Test
    fun afterAnUpdateTheNotesShowOnceUntilVerstanden() {
        val store = PreferencesReleaseNotesStore(ApplicationProvider.getApplicationContext())
        store.markSeen("0.16.0")
        signIn()
        stub("GET", "releases", Fixtures.releases)
        show { UpdateNotesPrompt() }
        waitForText("Neu in Oasis 0.24.0")
        compose.onNodeWithText("Versionen für alle").assertIsDisplayed()
        screenShot("update-notes")
        compose.onNodeWithText("Verstanden").press()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Neu in Oasis 0.24.0")).fetchSemanticsNodes().isEmpty() }
        assertEquals("0.24.0", store.lastSeen())
    }
}
