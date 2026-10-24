package de.velwebsolutions.oasis.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.app.shell.LocalAppScreens
import de.velwebsolutions.oasis.app.ui.RootScreen
import de.velwebsolutions.oasis.app.ui.RootViewModel
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ShellUiTest {
    @get:Rule(order = 0)
    val main = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val live = FakeLive()
    private val navigator = Navigator()

    private fun start(me: Me? = mira, error: Exception? = null, token: String? = "t"): RootViewModel {
        val auth = FakeAuth(me ?: mira, error)
        val session = Session(InMemoryTokenStore(token), "Pixel", TestScope()).apply { this.auth = auth }
        val model = RootViewModel(session, live, FakePurchases())
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(LocalNavigator provides navigator, LocalAppScreens provides FakeScreens) {
                    RootScreen(model)
                }
            }
        }
        return model
    }

    @Test
    fun `signed out, unverified, locked and new members see their screen`() {
        start(token = null)
        compose.onNodeWithText("Anmelden-Screen").assertIsDisplayed()
    }

    @Test
    fun `an unverified member confirms the e-mail first`() {
        start(mira.copy(emailVerified = false))
        compose.onNodeWithText("E-Mail-Screen").assertIsDisplayed()
    }

    @Test
    fun `a locked member sees the lock screen`() {
        start(mira.copy(isSuspended = true))
        compose.onNodeWithText("Inhalt ${AccountRoute.Suspended}").assertIsDisplayed()
    }

    @Test
    fun `a new member starts with the welcome tour`() {
        start(mira.copy(onboarded = false))
        compose.onNodeWithText("Inhalt ${AccountRoute.Welcome}").assertIsDisplayed()
    }

    @Test
    fun `offline at start the app waits on the sign-in page`() {
        start(error = ApiError.Transport("offline"))
        compose.onNodeWithText("Anmelden-Screen").assertIsDisplayed()
    }

    @Test
    fun `the tabs show their roots, the header and the counters`() {
        start()
        live.chats.value = 3
        live.hints.value = 2
        compose.onNodeWithText("Inhalt ${NetworkRoute.Source}").assertIsDisplayed()
        compose.onNodeWithText("Banner mira").assertIsDisplayed()
        compose.onNodeWithText("Glocke").assertIsDisplayed()
        compose.onNodeWithText("Chat-Hinweis").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/app-tabs.png")
        assertEquals(listOf(7), live.started)
        mapOf(
            MainTab.Discover to NetworkRoute.Discover,
            MainTab.Oases to NetworkRoute.Oases,
            MainTab.Chats to ChatRoute.Conversations,
            MainTab.Profile to NetworkRoute.Profile("mira")
        ).forEach { (tab, root) ->
            compose.onNodeWithTag("tab.${tab.name.lowercase()}").performClick()
            compose.onNodeWithText("Inhalt $root").assertIsDisplayed()
        }
        compose.waitForIdle()
        assertEquals(false, live.showingChats)
        compose.onNodeWithText("3", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the header searches, goes home and pushes screens with a way back`() {
        start()
        compose.onNodeWithContentDescription("Suchen").performClick()
        compose.onNodeWithText("Inhalt ${NetworkRoute.Search()}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("Inhalt ${NetworkRoute.Source}").assertIsDisplayed()
        compose.onNodeWithTag("tab.oases").performClick()
        compose.onNodeWithContentDescription("Oasis – zur Quelle").performClick()
        assertEquals(MainTab.Source, navigator.selectedTab)
    }

    @Test
    fun `the Mehr menu opens every account page and signs out`() {
        val model = start(mira.copy(isStaff = true, isAdmin = true))
        live.hints.value = 4
        val pages = listOf(
            "Hinweise" to LiveRoute.Inbox, "Freund:innen" to NetworkRoute.Friends, "Gemerkt" to NetworkRoute.Bookmarks,
            "Einstellungen" to AccountRoute.Settings, "Mitgliedschaft" to MembershipRoute.Overview,
            "Deine Zahlen" to NetworkRoute.Statistics, "Menschen einladen" to NetworkRoute.Invitations,
            "Privatsphäre" to AccountRoute.Privacy, "Blockiert & stumm" to AccountRoute.Safety, "Abstimmungen" to AccountRoute.Votes,
            "Begriffe erklärt" to AccountRoute.Glossary, "Moderation" to StaffRoute.Moderation, "Verwaltung" to StaffRoute.AdminDashboard
        )
        pages.forEach { (title, route) ->
            compose.onNodeWithTag("more.menu").performClick()
            compose.onNodeWithText(title).performScrollTo().performClick()
            compose.onNodeWithText("Inhalt $route").assertIsDisplayed()
            navigator.popToRoot()
        }
        compose.onNodeWithTag("more.menu").performClick()
        compose.onAllNodes(isRoot())[1].captureRoboImage("build/outputs/roborazzi/app-more.png")
        compose.onNodeWithText("Abmelden").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Anmelden-Screen").assertIsDisplayed()
        assertTrue(live.stops >= 1)
        assertEquals(model.state.value.javaClass.simpleName, "SignedOut")
    }

    @Test
    fun `sheets close themselves and confirmations show as a snackbar`() {
        start()
        compose.runOnIdle { navigator.present(NetworkRoute.Compose()) }
        compose.onNodeWithText("Inhalt ${NetworkRoute.Compose()}").assertIsDisplayed()
        compose.runOnIdle { navigator.open(NetworkRoute.Post(1)) }
        compose.onNodeWithText("Inhalt ${NetworkRoute.Post(1)}").assertIsDisplayed()
        compose.runOnIdle { navigator.back() }
        compose.onNodeWithContentDescription("Schließen").performClick()
        compose.waitForIdle()
        assertNull(navigator.sheet)
        compose.runOnIdle { NetworkSignals.confirmation.value = "Dein Tropfen ist da." }
        compose.onNodeWithText("Dein Tropfen ist da.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        assertNull(NetworkSignals.confirmation.value)
    }
}
