package de.velwebsolutions.oasis.chat.core

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

/**
 * The keys behind the biometric copy: one AES-256-GCM key per alias that only a strong biometric unlocks. The app uses
 * the Android Keystore ([AndroidKeystoreBiometricKeys]); the tests use software keys.
 */
interface BiometricKeys {
    /** Whether this device can protect a key with a strong biometric (fingerprint or face). */
    fun isAvailable(): Boolean

    /** A new key under [alias], as a cipher ready to encrypt once the biometric prompt authorised it. */
    fun encryptCipher(alias: String): Cipher

    /**
     * A cipher that decrypts with the key under [alias] and [iv] once authorised; null when there is no such key.
     * @throws KeyPermanentlyInvalidatedException when fingerprints or faces changed since the key was made.
     */
    fun decryptCipher(alias: String, iv: ByteArray): Cipher?

    /** Removes the key under [alias], if there is one. */
    fun delete(alias: String)
}

/**
 * [BiometricKeys] in the Android Keystore: every use needs a strong biometric, and the key becomes invalid when
 * fingerprints or faces change (iOS: `.biometryCurrentSet`).
 */
class AndroidKeystoreBiometricKeys(private val context: Context) : BiometricKeys {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    override fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override fun encryptCipher(alias: String): Cipher {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        // Before Android 11 a key with user authentication and no validity duration already needs a biometric per use.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec.build()) }.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(alias: String, iv: ByteArray): Cipher? {
        val key = runCatching { keyStore.getKey(alias, null) as? SecretKey }.getOrNull() ?: return null
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
    }

    override fun delete(alias: String) {
        runCatching { keyStore.deleteEntry(alias) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}

/** AES-GCM as both key implementations use it. */
internal const val TRANSFORMATION = "AES/GCM/NoPadding"
internal const val KEY_BITS = 256
internal const val TAG_BITS = 128

/**
 * [DeviceSecretStore] on [BiometricKeys]: the 32-byte secret is stored encrypted with the member's biometric key in
 * DataStore (IV || ciphertext), next to the credential id, which is readable without a prompt so the unlock button can
 * be shown.
 */
class KeystoreDeviceSecretStore(
    private val keys: BiometricKeys,
    private val authenticator: BiometricAuthenticator,
    private val dataStore: DataStore<Preferences>
) : DeviceSecretStore {
    override fun isAvailable(): Boolean = keys.isAvailable()

    override suspend fun credentialId(userId: String): String? = dataStore.data.first()[credentialKey(userId)]

    override suspend fun create(userId: String): DeviceSecret {
        if (!isAvailable()) throw ChatKeyringException.DeviceUnlockUnsupported
        delete(userId)
        val secret = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
        val sealed = try {
            authenticator.authenticate(keys.encryptCipher(alias(userId)), "Chat auf diesem Gerät einrichten")
                ?.let { it.iv + it.doFinal(secret) }
        } catch (_: GeneralSecurityException) {
            null
        }
        if (sealed == null) {
            delete(userId)
            throw ChatKeyringException.DeviceUnlockUnsupported
        }
        val credentialId = newCredentialId()
        dataStore.edit {
            it[credentialKey(userId)] = credentialId
            it[secretKey(userId)] = Base64.getEncoder().encodeToString(sealed)
        }
        return DeviceSecret(credentialId, secret)
    }

    override suspend fun secret(userId: String, reason: String): ByteArray? {
        val stored = dataStore.data.first()[secretKey(userId)] ?: return null
        val sealed = runCatching { Base64.getDecoder().decode(stored) }.getOrNull()
        if (sealed == null || sealed.size <= IV_BYTES) return null
        val cipher = try {
            keys.decryptCipher(alias(userId), sealed.copyOfRange(0, IV_BYTES))
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Fingerprints or faces changed: this copy is gone for good, the member unlocks another way.
            delete(userId)
            null
        } catch (_: GeneralSecurityException) {
            null
        }
        val authorised = cipher?.let { authenticator.authenticate(it, reason) } ?: return null
        return try {
            authorised.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).takeIf { it.size == SECRET_BYTES }
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    override suspend fun delete(userId: String) {
        keys.delete(alias(userId))
        dataStore.edit {
            it.remove(credentialKey(userId))
            it.remove(secretKey(userId))
        }
    }

    private fun alias(userId: String) = "oasis.chat.device-unlock.$userId"

    private fun credentialKey(userId: String) = stringPreferencesKey("device-unlock.credential.$userId")

    private fun secretKey(userId: String) = stringPreferencesKey("device-unlock.secret.$userId")

    private companion object {
        const val IV_BYTES = 12
        const val SECRET_BYTES = 32
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
