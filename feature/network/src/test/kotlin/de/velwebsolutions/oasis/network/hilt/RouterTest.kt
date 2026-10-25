package de.velwebsolutions.oasis.network.hilt

import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.NetworkScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Every network route reaches its screen with the view models Hilt creates (the app's entry point, `NetworkScreen`). */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class RouterTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Before
    fun registerActivity() {
        val app = ApplicationProvider.getApplicationContext<HiltTestApplication>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, NetworkTestActivity::class.java))
    }

    @Test
    fun `every route shows its screen`() {
        val routes = listOf(
            NetworkRoute.Source, NetworkRoute.Discover, NetworkRoute.Swipe, NetworkRoute.Post(1), NetworkRoute.EditPost(1),
            NetworkRoute.CarryPost(1), NetworkRoute.Compose(), NetworkRoute.Oases, NetworkRoute.Oasis("garten"),
            NetworkRoute.CreateOasis, NetworkRoute.EditOasis("garten"), NetworkRoute.CloseOasis("garten"),
            NetworkRoute.OasisStatistics("garten"), NetworkRoute.Profile("jonas"), NetworkRoute.EditProfile, NetworkRoute.Friends,
            NetworkRoute.Statistics, NetworkRoute.Invitations, NetworkRoute.Invitation("abc"), NetworkRoute.Search(),
            NetworkRoute.Search("garten"), NetworkRoute.Tag("garten"), NetworkRoute.Bookmarks
        )
        ActivityScenario.launch(NetworkTestActivity::class.java).use { scenario ->
            routes.forEach { route ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        OasisTheme { CompositionLocalProvider(LocalNavigator provides Navigator()) { NetworkScreen(route) } }
                    }
                }
                compose.waitForIdle()
            }
        }
    }
}
