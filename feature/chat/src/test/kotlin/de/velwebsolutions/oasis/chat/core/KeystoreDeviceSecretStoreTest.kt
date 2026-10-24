package de.velwebsolutions.oasis.chat.core

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.chat.di.ChatModule
import java.io.File
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

/** Software AES keys in place of the Android Keystore, with switches for the keystore's failure modes. */
class SoftwareBiometricKeys : BiometricKeys {
    val keys = mutableMapOf<String, SecretKey>()
    var available = true
    var failsToCreate = false
    var invalidated = false
    var failsToOpen = false

    override fun isAvailable() = available

    override fun encryptCipher(alias: String): Cipher {
        if (failsToCreate) throw InvalidKeyException("no key")
        val key = KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey()
        keys[alias] = key
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(alias: String, iv: ByteArray): Cipher? {
        if (invalidated) throw KeyPermanentlyInvalidatedException()
        if (failsToOpen) throw InvalidKeyException("locked")
        val key = keys[alias] ?: return null
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
    }

    override fun delete(alias: String) {
        keys.remove(alias)
    }
}

/** The biometric copy's storage: sealing, prompts, corrupted data and the keystore's failure modes (Robolectric). */
@RunWith(RobolectricTestRunner::class)
class KeystoreDeviceSecretStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keys = SoftwareBiometricKeys()
    private var prompt: (Cipher) -> Cipher? = { it }
    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "chat.preferences_pb") }
    }
    private val store by lazy { KeystoreDeviceSecretStore(keys, { cipher, _ -> prompt(cipher) }, dataStore) }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun storeRaw(value: String) = dataStore.edit { it[stringPreferencesKey("device-unlock.secret.1")] = value }

    @Test
    fun keepsTheSecretBehindTheBiometricKey() = runBlocking {
        assertTrue(store.isAvailable())
        assertNull(store.credentialId("1"))
        val created = store.create("1")
        assertTrue(created.credentialId.startsWith(APP_CREDENTIAL_PREFIX))
        assertEquals(created.credentialId, store.credentialId("1"))
        assertArrayEquals(created.secret, store.secret("1", "Nachrichten entsperren"))

        store.delete("1")
        assertNull(store.credentialId("1"))
        assertNull(store.secret("1", "Nachrichten entsperren"))
        assertTrue(keys.keys.isEmpty())
    }

    @Test
    fun setupNeedsTheDeviceAndTheConfirmedPrompt() = runBlocking {
        keys.available = false
        assertThrows(ChatKeyringException.DeviceUnlockUnsupported::class.java) { runBlocking { store.create("1") } }
        keys.available = true
        prompt = { null }
        assertThrows(ChatKeyringException.DeviceUnlockUnsupported::class.java) { runBlocking { store.create("1") } }
        assertNull(store.credentialId("1"))
        assertTrue("a cancelled setup leaves no key behind", keys.keys.isEmpty())
        prompt = { it }
        keys.failsToCreate = true
        assertThrows(ChatKeyringException.DeviceUnlockUnsupported::class.java) { runBlocking { store.create("1") } }
        Unit
    }

    @Test
    fun unlockingFailsQuietlyForEveryProblem() = runBlocking {
        val created = store.create("1")
        prompt = { null }
        assertNull("cancelled prompt", store.secret("1", "x"))
        prompt = { it }
        keys.failsToOpen = true
        assertNull("key not usable", store.secret("1", "x"))
        keys.failsToOpen = false
        keys.keys.clear()
        assertNull("key gone", store.secret("1", "x"))

        store.create("1")
        keys.invalidated = true
        assertNull("fingerprints changed", store.secret("1", "x"))
        assertNull("the copy is forgotten", store.credentialId("1"))
        keys.invalidated = false
        assertNotNull(created)
    }

    @Test
    fun corruptedStorageNeverCrashes() = runBlocking {
        store.create("1")
        storeRaw("kein base64 ✓")
        assertNull(store.secret("1", "x"))
        storeRaw(Base64.getEncoder().encodeToString(ByteArray(12)))
        assertNull(store.secret("1", "x"))
        storeRaw(Base64.getEncoder().encodeToString(ByteArray(40)))
        assertNull("the tag does not verify", store.secret("1", "x"))

        // A correctly sealed value of the wrong size.
        val cipher = keys.encryptCipher("oasis.chat.device-unlock.1")
        storeRaw(Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(ByteArray(16))))
        assertNull(store.secret("1", "x"))
    }

    @Test
    fun remembersTheDeclinedOffer() = runBlocking {
        val preferences = DataStoreChatPreferences(dataStore)
        assertFalse(preferences.biometricOfferDeclined("1"))
        preferences.setBiometricOfferDeclined("1", true)
        assertTrue(preferences.biometricOfferDeclined("1"))
        assertFalse(preferences.biometricOfferDeclined("2"))
        preferences.setBiometricOfferDeclined("1", false)
        assertFalse(preferences.biometricOfferDeclined("1"))
    }

    @Test
    fun theKeystoreBindingNeedsADevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val keystore = AndroidKeystoreBiometricKeys(context)
        val biometrics = Shadow.extract<ShadowBiometricManager>(
            context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
        )
        biometrics.setCanAuthenticate(false)
        assertFalse(keystore.isAvailable())
        biometrics.setCanAuthenticate(true)
        biometrics.setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        assertTrue(keystore.isAvailable())
        assertNull("no Android Keystore on the JVM", keystore.decryptCipher("alias", ByteArray(12)))
        keystore.delete("alias")
        assertThrows(GeneralSecurityException::class.java) { keystore.encryptCipher("alias") }
        assertNotNull(ChatModule.dataStore(context))
    }
}
