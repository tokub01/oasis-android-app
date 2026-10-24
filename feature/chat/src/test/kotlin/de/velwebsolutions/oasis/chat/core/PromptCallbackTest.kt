package de.velwebsolutions.oasis.chat.core

import androidx.biometric.BiometricPrompt
import javax.crypto.Cipher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The prompt's answers reach the waiting coroutine once: the cipher, or null for a refusal or a missing cipher. */
@RunWith(RobolectricTestRunner::class)
class PromptCallbackTest {
    private val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)

    private fun answer(deliver: (PromptCallback) -> Unit): Cipher? = runBlocking {
        suspendCancellableCoroutine { continuation: CancellableContinuation<Cipher?> ->
            val callback = PromptCallback(continuation)
            deliver(callback)
            // A second answer after the first one is ignored.
            deliver(callback)
        }
    }

    /** The system's result object (its constructor is package-private, so the test reaches it by reflection). */
    private fun result(crypto: BiometricPrompt.CryptoObject?): BiometricPrompt.AuthenticationResult =
        BiometricPrompt.AuthenticationResult::class.java
            .getDeclaredConstructor(BiometricPrompt.CryptoObject::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(crypto, BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC)

    @Test
    fun confirmedHandsOverTheCipher() {
        assertSame(cipher, answer { it.onAuthenticationSucceeded(result(BiometricPrompt.CryptoObject(cipher))) })
        assertNull(answer { it.onAuthenticationSucceeded(result(null)) })
    }

    @Test
    fun refusedAnswersNull() {
        assertNull(answer { it.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "Abgebrochen") })
    }
}
