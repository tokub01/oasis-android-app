package de.velwebsolutions.oasis.app

import de.velwebsolutions.oasis.app.ui.RootViewModel
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RootViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val live = FakeLive()
    private val purchases = FakePurchases()
    private val auth = FakeAuth()
    private val session = Session(InMemoryTokenStore("t"), "Pixel", TestScope()).apply { auth = this@RootViewModelTest.auth }

    @Test
    fun `restores the session and goes live once per member`() = runTest {
        val model = RootViewModel(session, live, purchases)
        assertEquals(SessionState.SignedIn(mira), model.state.value)
        model.tabsAppeared(7)
        model.tabsAppeared(7)
        assertEquals(listOf(7), live.started)
        assertEquals(1, purchases.observed)
        model.foreground()
        assertEquals(1, live.foregrounds)
        model.tabsAppeared(8)
        assertEquals(listOf(7, 8), live.started)
    }

    @Test
    fun `signing out stops live and lets the next member go live`() = runTest {
        val model = RootViewModel(session, live, purchases)
        model.tabsAppeared(7)
        model.signOut()
        assertEquals(SessionState.SignedOut, model.state.value)
        assertEquals(1, live.stops)
        assertTrue(auth.loggedOut)
        model.tabsAppeared(7)
        assertEquals(listOf(7, 7), live.started)
    }

    @Test
    fun `the confirmation is cleared once shown`() {
        val model = RootViewModel(session, live, purchases)
        NetworkSignals.confirmation.value = "Dein Tropfen ist da."
        assertEquals("Dein Tropfen ist da.", model.confirmation.value)
        model.clearConfirmation()
        assertNull(NetworkSignals.confirmation.value)
    }

    @Test
    fun `signing out before the tabs appeared`() = runTest {
        val model = RootViewModel(session, live, purchases)
        model.signOut()
        assertEquals(SessionState.SignedOut, model.state.value)
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun `a sheet with a draft cannot be swiped away`() {
        assertEquals(
            false,
            de.velwebsolutions.oasis.app.ui.sheetMayChange(androidx.compose.material3.SheetValue.Hidden, dismissible = false)
        )
        assertEquals(true, de.velwebsolutions.oasis.app.ui.sheetMayChange(androidx.compose.material3.SheetValue.Hidden, dismissible = true))
        assertEquals(
            true,
            de.velwebsolutions.oasis.app.ui.sheetMayChange(androidx.compose.material3.SheetValue.Expanded, dismissible = false)
        )
    }
}
