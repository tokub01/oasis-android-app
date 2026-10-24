package de.velwebsolutions.oasis.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.app.shell.FeatureScreens
import de.velwebsolutions.oasis.app.shell.LiveShell
import de.velwebsolutions.oasis.app.ui.AppDestination
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import javax.inject.Inject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real activity with the real Hilt graph and the real feature screens, as far as they go without a server. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AppWiringTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var navigator: Navigator

    @Inject lateinit var apiClient: ApiClient

    @Inject lateinit var live: LiveShell

    @Before
    fun inject() = hilt.inject()

    @Test
    fun `starts signed out on the real sign-in page and follows links`() {
        compose.waitUntil(20_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Willkommen zurück.")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            compose.activity.handle(Intent(Intent.ACTION_VIEW, Uri.parse("https://get-oasis.com/app/nachrichten#c/5")))
        }
        assertEquals(MainTab.Chats, navigator.selectedTab)
        compose.runOnIdle { compose.activity.handle(Intent().putExtra(MainActivity.EXTRA_URL, "https://get-oasis.com/oasen/garten")) }
        assertEquals(MainTab.Oases, navigator.selectedTab)
        compose.runOnIdle { compose.activity.handle(Intent().putExtra("url", "https://get-oasis.com/app/entdecken")) }
        assertEquals(MainTab.Discover, navigator.selectedTab)
        compose.runOnIdle { compose.activity.handle(Intent()) }
        compose.runOnIdle { navigator.openWeb("https://get-oasis.com/impressum") }
    }

    @Test
    fun `every area's screen and the shell pieces come from the feature modules`() = runTest {
        val routes = listOf(
            NetworkRoute.Discover,
            ChatRoute.Conversations,
            AccountRoute.Glossary,
            LiveRoute.Inbox,
            MembershipRoute.Overview,
            StaffRoute.Moderation,
            WebRoute("https://get-oasis.com/impressum")
        )
        compose.activity.setContent {
            OasisTheme {
                CompositionLocalProvider(LocalNavigator provides navigator, LocalApiClient provides apiClient) {
                    Column {
                        routes.forEach { route -> Column(Modifier.height(80.dp)) { AppDestination(route) } }
                        FeatureScreens.Banner(mira)
                        FeatureScreens.Bell()
                        FeatureScreens.ChatNotice()
                        Column(Modifier.height(80.dp)) { FeatureScreens.Destination(NetworkRoute.Oases) }
                        Column(Modifier.height(80.dp)) { FeatureScreens.VerifyEmail() }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, live.hints.value)
        assertEquals(0, live.chats.value)
        live.setShowingChats(true)
        live.foreground()
        live.stop()
    }

    @Test
    fun `images load through the API client's OkHttp`() {
        val app = OasisApplication().apply { apiClient = this@AppWiringTest.apiClient }
        val loader = app.newImageLoader(compose.activity)
        assertEquals(true, loader.components.fetcherFactories.isNotEmpty())
    }
}
