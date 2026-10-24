package de.velwebsolutions.oasis.chat.core

import android.app.Activity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Authorises a keystore cipher with a strong biometric (the system prompt in the app, a fake in the tests). */
fun interface BiometricAuthenticator {
    /** The cipher, authorised by a strong biometric; null when cancelled, failed or no screen can show the prompt. */
    suspend fun authenticate(cipher: Cipher, title: String): Cipher?
}

/**
 * The system's biometric prompt for a keystore cipher (iOS: the Face ID sheet the Keychain shows). The chat screen
 * registers the activity it is shown in (the app's `MainActivity`, a `FragmentActivity`, as androidx.biometric needs).
 */
class BiometricPrompter : BiometricAuthenticator {
    private var activity: WeakReference<Activity>? = null

    /** The activity a chat screen is shown in; the prompt appears above it. */
    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
    }

    /** The chat screen left [activity]; a newer activity stays attached. */
    fun detach(activity: Activity) {
        if (this.activity?.get() === activity) this.activity = null
    }

    override suspend fun authenticate(cipher: Cipher, title: String): Cipher? = withContext(Dispatchers.Main) {
        val current = activity?.get() as? FragmentActivity ?: return@withContext null
        androidPrompt(current, cipher, title)
    }

    private suspend fun androidPrompt(activity: FragmentActivity, cipher: Cipher, title: String): Cipher? =
        suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText("Abbrechen")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
