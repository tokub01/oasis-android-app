package de.velwebsolutions.oasis.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the device's API token lives. */
interface TokenStore {
    fun read(): String?

    fun write(token: String?)
}

/** A token held in memory only (tests, previews). */
class InMemoryTokenStore(private var token: String? = null) : TokenStore {
    override fun read(): String? = token

    override fun write(token: String?) {
        this.token = token
    }
}

/**
 * Keeps the token encrypted with an AES key in the Android Keystore (this device only, never in backups), like the
 * iOS Keychain item "available after first unlock, this device only". The encrypted value sits in private preferences.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val preferences = context.getSharedPreferences("oasis.session", Context.MODE_PRIVATE)
    private val lock = Any()
    private var cached: Result<String?>? = null

    override fun read(): String? = synchronized(lock) {
        cached?.let { return it.getOrNull() }
        val token = preferences.getString(KEY, null)?.let { runCatching { decrypt(it) }.getOrNull() }
        cached = Result.success(token)
        token
    }

    override fun write(token: String?) = synchronized(lock) {
        if (token == null) {
            preferences.edit().remove(KEY).commit()
        } else {
            preferences.edit().putString(KEY, encrypt(token)).commit()
        }
        cached = Result.success(token)
        Unit
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(token: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(token.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
    }

    private companion object {
        const val KEY = "api-token"
        const val ALIAS = "de.velwebsolutions.oasis.api-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
