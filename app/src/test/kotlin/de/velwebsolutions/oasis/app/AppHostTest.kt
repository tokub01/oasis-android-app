package de.velwebsolutions.oasis.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import de.velwebsolutions.oasis.app.shell.AppHost
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the main activity leaves to the shell: intents once, a clean navigator after a sign-out, the recent-apps picture. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppHostTest {
    @Test
    fun `an activity Android creates again leaves the screens its intent opened`() {
        val navigator = Navigator()
        AppHost.created(null, null, navigator)
        assertTrue("without an intent nothing opens", MainTab.entries.all { navigator.stack(it).isEmpty() })
        val share = Intent().putExtra(AppHost.EXTRA_SHARE, true)
        AppHost.created(share, null, navigator)
        assertEquals(listOf(ChatRoute.Share), navigator.stack(MainTab.Chats).toList())
        navigator.open(ChatRoute.Conversation(5))
        navigator.present(NetworkRoute.Compose())
        navigator.sheetDismissible = false
        AppHost.created(share, Bundle(), navigator)
        assertEquals(
            "the chat picked for the share stays",
            listOf(ChatRoute.Share, ChatRoute.Conversation(5)),
            navigator.stack(MainTab.Chats).toList()
        )
        assertEquals("the composer stays open", NetworkRoute.Compose(), navigator.sheet)
        AppHost.created(Intent(share).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), null, navigator)
        assertEquals("reopened from the recent apps, the task's first intent is not handled again", NetworkRoute.Compose(), navigator.sheet)
    }

    @Test
    fun `after a sign-out the next member starts on the roots, a link opened before signing in waits`() = runTest {
        val session = Session(InMemoryTokenStore(), "Pixel", TestScope()).apply { auth = FakeAuth() }
        val navigator = Navigator()
        backgroundScope.launch { AppHost.resetAfterSignOut(session.state, navigator) }
        runCurrent()
        navigator.handle("https://get-oasis.com/tropfen/4")
        session.restore()
        runCurrent()
        session.signIn("mira", "geheim")
        runCurrent()
        session.update(mira.copy(displayName = "Mira S."))
        runCurrent()
        assertEquals("starting and signing in keep it", listOf(NetworkRoute.Post(4)), navigator.stack(MainTab.Source).toList())
        navigator.handle(NetworkRoute.Search("tee"))
        navigator.open(NetworkRoute.Profile("jonas"))
        navigator.present(ChatRoute.NewConversationWith("jonas"))
        navigator.sheetDismissible = false
        navigator.handle("https://get-oasis.com/anmelden/code")
        session.signOut()
        runCurrent()
        assertEquals(MainTab.Source, navigator.selectedTab)
        assertTrue(MainTab.entries.all { navigator.stack(it).isEmpty() })
        assertNull(navigator.sheet)
        assertTrue(navigator.sheetDismissible)
        assertNull(navigator.pendingLink)
    }

    @Test
    fun `while the chat is unlocked the recent apps keep no picture of the app`() = runTest {
        val unlocked = MutableStateFlow(false)
        val enabled = mutableListOf<Boolean>()
        AppHost.recentsScreenshots(backgroundScope, unlocked) { enabled += it }
        runCurrent()
        unlocked.value = true
        runCurrent()
        unlocked.value = false
        runCurrent()
        assertEquals(listOf(true, false, true), enabled)
    }
}
