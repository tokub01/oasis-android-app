package de.velwebsolutions.oasis.chat.core

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A JCA provider named "AndroidKeyStore" with software keys, so [AndroidKeystoreBiometricKeys] runs on the JVM: it keeps
 * the keys in memory and records the specs the app asks for.
 */
object FakeAndroidKeyStore {
    val keys = ConcurrentHashMap<String, Key>()
    val specs = ConcurrentHashMap<String, KeyGenParameterSpec>()

    private val provider = object : Provider("AndroidKeyStore", 1.0, "Software keys for the tests") {
        init {
            put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", FakeKeyGeneratorSpi::class.java.name)
        }
    }

    fun install() {
        Security.insertProviderAt(provider, 1)
    }

    fun uninstall() {
        Security.removeProvider("AndroidKeyStore")
        keys.clear()
        specs.clear()
    }
}

/** The key generator: a random AES key under the alias of the spec. */
class FakeKeyGeneratorSpi : KeyGeneratorSpi() {
    private var spec: KeyGenParameterSpec? = null

    override fun engineInit(random: SecureRandom?) = Unit

    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        spec = params as KeyGenParameterSpec
    }

    override fun engineInit(keysize: Int, random: SecureRandom?) = Unit

    override fun engineGenerateKey(): SecretKey {
        val spec = checkNotNull(spec)
        val key = SecretKeySpec(ByteArray(spec.keySize / 8).also(SecureRandom()::nextBytes), "AES")
        FakeAndroidKeyStore.keys[spec.keystoreAlias] = key
        FakeAndroidKeyStore.specs[spec.keystoreAlias] = spec
        return key
    }
}

/** The key store: only what the app uses (load, get, delete). */
class FakeKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = FakeAndroidKeyStore.keys[alias]

    override fun engineDeleteEntry(alias: String) {
        FakeAndroidKeyStore.keys.remove(alias)
    }

    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null

    override fun engineGetCertificate(alias: String?): Certificate? = null

    override fun engineGetCreationDate(alias: String?): Date? = null

    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = Unit

    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit

    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit

    override fun engineAliases(): Enumeration<String> = Collections.enumeration(FakeAndroidKeyStore.keys.keys)

    override fun engineContainsAlias(alias: String?): Boolean = FakeAndroidKeyStore.keys.containsKey(alias)

    override fun engineSize(): Int = FakeAndroidKeyStore.keys.size

    override fun engineIsKeyEntry(alias: String?): Boolean = engineContainsAlias(alias)

    override fun engineIsCertificateEntry(alias: String?): Boolean = false

    override fun engineGetCertificateAlias(cert: Certificate?): String? = null

    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
}
