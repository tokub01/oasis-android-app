package de.velwebsolutions.oasis.chat.core

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

/**
 * The prompt's plumbing (Robolectric): which activity shows it and leaving the screen. The system prompt's answers
 * (confirmed, refused) only come on a real device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(org.robolectric.RobolectricTestRunner::class)
class BiometricPrompterTest {
    private val prompter = BiometricPrompter()
    private val cipher: Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey())
    }

    @After
    fun tearDown() = ShadowBiometricManager.reset()

    private fun activity(): FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))

    /** Starts the prompt on the main thread (the test thread is Robolectric's main looper, so it must not block). */
    private fun authenticate() = CoroutineScope(Dispatchers.Main).async(start = CoroutineStart.UNDISPATCHED) {
        prompter.authenticate(cipher, "Nachrichten entsperren")
    }

    /** The prompt's answer once the main looper ran everything that was queued. */
    private fun answer(deferred: Deferred<Cipher?>): Cipher? {
        repeat(20) { idle() }
        assertTrue(deferred.isCompleted)
        return deferred.getCompleted()
    }

    @Test
    fun withoutAChatScreenThereIsNoPrompt() {
        assertNull(answer(authenticate()))
        prompter.attach(Robolectric.buildActivity(Activity::class.java).setup().get())
        assertNull("androidx.biometric needs a FragmentActivity", answer(authenticate()))
    }

    @Test
    fun detachingOnlyForgetsTheSameActivity() {
        val first = activity()
        val second = activity()
        prompter.attach(first)
        prompter.attach(second)
        prompter.detach(first)
        prompter.detach(second)
        assertNull(answer(authenticate()))
    }

    @Test
    fun leavingTheScreenCancelsThePrompt() {
        val activity = activity()
        Shadow.extract<ShadowBiometricManager>(activity.getSystemService(BiometricManager::class.java)).apply {
            setCanAuthenticate(true)
            setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        }
        prompter.attach(activity)
        val answer = authenticate()
        idle()
        answer.cancel()
        idle()
        assertTrue(answer.isCancelled)
    }
}
