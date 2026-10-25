package de.velwebsolutions.oasis.live.hilt

import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.live.ChatToastOverlay
import de.velwebsolutions.oasis.live.HintsButton
import de.velwebsolutions.oasis.live.LiveJson
import de.velwebsolutions.oasis.live.LiveScreen
import de.velwebsolutions.oasis.live.push.OasisMessagingService
import de.velwebsolutions.oasis.testing.StubTransport
import javax.inject.Inject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The module's entry points with their real Hilt view models: the screens per route, the shell pieces, the push service. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class LiveHiltTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var transport: StubTransport

    private var scenario: ActivityScenario<HiltTestActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        val app = ApplicationProvider.getApplicationContext<HiltTestApplication>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, HiltTestActivity::class.java))
        transport.add("GET", "hints", json = LiveJson.HINTS)
        transport.add("GET", "hints/settings", json = LiveJson.SETTINGS)
        transport.add("GET", "live/counts", json = LiveJson.COUNTS)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun showsEveryRouteAndTheShellPieces() {
        scenario = ActivityScenario.launch(HiltTestActivity::class.java).onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalNavigator provides Navigator()) {
                    Column {
                        HintsButton()
                        ChatToastOverlay()
                        LiveScreen(LiveRoute.Inbox)
                    }
                }
            }
        }
        compose.waitUntil(5_000) { transport.requests.any { it.path == "hints" } }
        compose.onNodeWithTag("hints.bell").assertExists()
    }

    @Test
    fun showsTheSettingsRoute() {
        scenario = ActivityScenario.launch(HiltTestActivity::class.java).onActivity { activity ->
            activity.setContent { CompositionLocalProvider(LocalNavigator provides Navigator()) { LiveScreen(LiveRoute.Settings) } }
        }
        compose.waitUntil(5_000) { transport.requests.any { it.path == "hints/settings" } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("digest").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theFirebaseServiceHandsTokensAndMessagesOn() {
        val service = Robolectric.buildService(OasisMessagingService::class.java).create().get()
        service.onNewToken("fcm")
        service.onMessageReceived(RemoteMessage.Builder("oasis").addData("title", "Oasis").build())
        assertTrue("the counters are asked again", transport.requests.any { it.path == "live/counts" } || transport.requests.isEmpty())
    }
}
