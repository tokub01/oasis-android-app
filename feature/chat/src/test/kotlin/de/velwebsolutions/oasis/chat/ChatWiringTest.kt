package de.velwebsolutions.oasis.chat

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import de.velwebsolutions.oasis.chat.core.BiometricPrompter
import de.velwebsolutions.oasis.chat.di.ChatModule
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/** The process-wide wiring (Robolectric): the vault locks on sign-out and in the background; the root knows who is in. */
@RunWith(RobolectricTestRunner::class)
class ChatWiringTest {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val session = Session(InMemoryTokenStore("t"), "Pixel", scope)
    private val server = FakeChatServer()

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun theVaultLocksInTheBackgroundAndOnSignOut() {
        val vault = ChatModule.vault(scope, session)
        idle()
        vault.prepare("1")
        vault.unlock(testCrypto.createIdentity(), vault.generation)
        val process = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        process.handleLifecycleEvent(Lifecycle.Event.ON_START)
        process.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertFalse("the app went to the background", vault.unlocked)

        vault.unlock(testCrypto.createIdentity(), vault.generation)
        runBlocking { session.signOut() }
        idle()
        assertFalse(vault.unlocked)
    }

    @Test
    fun theRootFollowsTheSession() {
        val root = ChatRootViewModel(session, BiometricPrompter())
        assertNull(root.me.value)
        session.update(server.me(1))
        idle()
        assertEquals(1, root.me.value?.id)
        assertTrue(root.prompter is BiometricPrompter)
    }
}
