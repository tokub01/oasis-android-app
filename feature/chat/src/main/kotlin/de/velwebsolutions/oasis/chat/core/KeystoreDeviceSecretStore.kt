package de.velwebsolutions.oasis.chat.core

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricPrompt as FrameworkBiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The system's biometric prompt for a keystore cipher (iOS: the Face ID sheet the Keychain shows). The chat screen
 * registers the activity it is shown in. With a `FragmentActivity` the prompt of androidx.biometric is used, otherwise
 * the platform prompt (API 28+), which works with any activity.
 */
class BiometricPrompter {
    private var activity: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (this.activity?.get() === activity) this.activity = null
    }

    /** The cipher, authorised by a strong biometric; null when cancelled, failed or no activity is shown. */
    suspend fun authenticate(cipher: Cipher, title: String): Cipher? = withContext(Dispatchers.Main) {
        val current = activity?.get() ?: return@withContext null
        if (current is FragmentActivity) androidPrompt(current, cipher, title) else platformPrompt(current, cipher, title)
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

    private suspend fun platformPrompt(activity: Activity, cipher: Cipher, title: String): Cipher? =
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val signal = CancellationSignal()
            val builder = FrameworkBiometricPrompt.Builder(activity)
                .setTitle(title)
                .setNegativeButton("Abbrechen", executor) { _, _ -> if (continuation.isActive) continuation.resume(null) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setAllowedAuthenticators(BIOMETRIC_STRONG)
            val callback = object : FrameworkBiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: FrameworkBiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            builder.build().authenticate(FrameworkBiometricPrompt.CryptoObject(cipher), signal, executor, callback)
            continuation.invokeOnCancellation { signal.cancel() }
        }
}

/**
 * [DeviceSecretStore] on the Android Keystore: an AES-256-GCM key per member that only a strong biometric unlocks, for
 * every single use, and that becomes invalid when fingerprints or faces change (iOS: `.biometryCurrentSet`). The
 * 32-byte secret is stored encrypted with that key in DataStore, next to the credential id, which is readable without a
 * prompt so the unlock button can be shown.
 */
class KeystoreDeviceSecretStore(
    private val context: Context,
    private val prompter: BiometricPrompter,
    private val dataStore: DataStore<Preferences>
) : DeviceSecretStore {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    override fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun credentialId(userId: String): String? = dataStore.data.first()[credentialKey(userId)]

    override suspend fun create(userId: String): DeviceSecret {
        if (!isAvailable()) throw ChatKeyringException.DeviceUnlockUnsupported
        delete(userId)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, generateKey(alias(userId))) }
            val authorised =
                prompter.authenticate(cipher, "Chat auf diesem Gerät einrichten") ?: throw ChatKeyringException.DeviceUnlockUnsupported
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val sealed = authorised.iv + authorised.doFinal(secret)
            val credentialId = newCredentialId()
            dataStore.edit {
                it[credentialKey(userId)] = credentialId
                it[secretKey(userId)] = Base64.getEncoder().encodeToString(sealed)
            }
            return DeviceSecret(credentialId, secret)
        } catch (error: GeneralSecurityException) {
            delete(userId)
            throw ChatKeyringException.DeviceUnlockUnsupported
        } catch (error: ChatKeyringException) {
            delete(userId)
            throw error
        }
    }

    override suspend fun secret(userId: String, reason: String): ByteArray? {
        val sealed = dataStore.data.first()[secretKey(userId)]?.let { Base64.getDecoder().decode(it) } ?: return null
        if (sealed.size <= IV_BYTES) return null
        val key = runCatching { keyStore.getKey(alias(userId), null) as? SecretKey }.getOrNull() ?: return null
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_BYTES)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Fingerprints or faces changed: this copy is gone for good, the member unlocks another way.
            delete(userId)
            return null
        } catch (_: GeneralSecurityException) {
            return null
        }
        val authorised = prompter.authenticate(cipher, reason) ?: return null
        return try {
            authorised.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).takeIf { it.size == 32 }
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    override suspend fun delete(userId: String) {
        runCatching { keyStore.deleteEntry(alias(userId)) }
        dataStore.edit {
            it.remove(credentialKey(userId))
            it.remove(secretKey(userId))
        }
    }

    private fun generateKey(alias: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.R
                ) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun alias(userId: String) = "oasis.chat.device-unlock.$userId"

    private fun credentialKey(userId: String) = stringPreferencesKey("device-unlock.credential.$userId")

    private fun secretKey(userId: String) = stringPreferencesKey("device-unlock.secret.$userId")

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** [ChatPreferences] in DataStore (iOS: UserDefaults). */
class DataStoreChatPreferences(private val dataStore: DataStore<Preferences>) : ChatPreferences {
    override suspend fun biometricOfferDeclined(userId: String): Boolean = dataStore.data.first()[declinedKey(userId)] == true

    override suspend fun setBiometricOfferDeclined(userId: String, declined: Boolean) {
        dataStore.edit { it[declinedKey(userId)] = declined }
    }

    private fun declinedKey(userId: String) = booleanPreferencesKey("biometric-offer-declined.$userId")
}
