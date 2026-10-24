package de.velwebsolutions.oasis.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
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

/** The AES key that seals the token; the Android Keystore on devices, a software key in tests. */
fun interface TokenKeyProvider {
    fun key(): SecretKey
}

/** A non-exportable AES-256 key in the Android Keystore, created on first use. */
object AndroidKeystoreTokenKey : TokenKeyProvider {
    private const val ALIAS = "de.velwebsolutions.oasis.api-token"

    override fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

/**
 * Keeps the token encrypted with AES-GCM under a key from [keys] (the Android Keystore: this device only, never in
 * backups), like the iOS Keychain item "available after first unlock, this device only". The sealed value sits in private
 * preferences; a value that no longer opens (key gone after a restore) reads as signed out.
 */
class KeystoreTokenStore(
    context: Context,
    private val keys: TokenKeyProvider = AndroidKeystoreTokenKey
) : TokenStore {
    private val preferences = context.getSharedPreferences("oasis.session", Context.MODE_PRIVATE)
    private val lock = Any()
    private var loaded = false
    private var cached: String? = null

    override fun read(): String? = synchronized(lock) {
        if (!loaded) {
            val stored = preferences.getString(KEY, null)
            cached = if (stored == null) null else runCatching { decrypt(stored) }.getOrNull()
            loaded = true
        }
        cached
    }

    override fun write(token: String?) = synchronized(lock) {
        // Written synchronously: the token must be on disk before the app may be killed after signing in.
        preferences.edit(commit = true) {
            if (token == null) remove(KEY) else putString(KEY, encrypt(token))
        }
        cached = token
        loaded = true
    }

    private fun encrypt(token: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keys.key()) }
        val sealed = cipher.iv + cipher.doFinal(token.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
    }

    private companion object {
        const val KEY = "api-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
