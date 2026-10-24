package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.KeystoreTokenStore
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TokenStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `stores the token sealed and reads it back after a restart`() {
        KeystoreTokenStore(context) { key }.write("secret-token")
        val stored = context.getSharedPreferences("oasis.session", 0).getString("api-token", null)
        assertEquals(false, stored!!.contains("secret-token"))
        assertEquals("secret-token", KeystoreTokenStore(context) { key }.read())
    }

    @Test
    fun `a sealed value that no longer opens reads as signed out`() {
        KeystoreTokenStore(context) { key }.write("secret-token")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(KeystoreTokenStore(context) { otherKey }.read())
    }

    @Test
    fun `signing out removes the token and caches the answer`() {
        val store = KeystoreTokenStore(context) { key }
        store.write("secret-token")
        store.write(null)
        assertNull(store.read())
        assertNull(store.read())
        assertNull(KeystoreTokenStore(context) { key }.read())
    }

    @Test
    fun `memory store for previews and tests`() {
        val store = InMemoryTokenStore()
        assertNull(store.read())
        store.write("t")
        assertEquals("t", store.read())
    }

    @Test
    fun `the Keystore key is created once and then reused`() {
        FakeAndroidKeyStore.install()
        FakeAndroidKeyStore.reset()
        KeystoreTokenStore(context).write("secret-token")
        assertEquals(setOf("de.velwebsolutions.oasis.api-token"), FakeAndroidKeyStore.aliases)
        assertEquals("secret-token", KeystoreTokenStore(context).read())
    }

    @Test
    fun `nothing stored reads as signed out`() {
        context.getSharedPreferences("oasis.session", 0).edit().clear().commit()
        assertNull(KeystoreTokenStore(context) { key }.read())
    }
}
