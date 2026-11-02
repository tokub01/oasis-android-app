package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.chat.api.DeviceUnlockRow
import de.velwebsolutions.oasis.chat.api.StoredIdentity
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatKeyringException
import de.velwebsolutions.oasis.chat.core.MemoryDeviceSecretStore
import de.velwebsolutions.oasis.chat.data.ChatRepository
import de.velwebsolutions.oasis.chatcrypto.ChatCryptoException
import de.velwebsolutions.oasis.chatcrypto.ChatIdentity
import de.velwebsolutions.oasis.chatcrypto.KdfLimits
import de.velwebsolutions.oasis.chatcrypto.KeyGrant
import de.velwebsolutions.oasis.chatcrypto.PublicKeyBundle
import de.velwebsolutions.oasis.chatcrypto.createKeyGrant
import de.velwebsolutions.oasis.core.network.ApiError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyring's and the device copies' failure paths: races with a lock, refused requests and foreign objects. */
class KeyringAndDevicesTest {
    private val support = ChatTestSupport()
    private val server get() = support.server
    private val crypto = testCrypto
    private val passphrase = "Palme Regen Kompass Tee"

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    private fun failing(method: String, path: String, status: Int = 500) {
        server.failures["$method $path"] = status to """{"message":"kaputt"}"""
    }

    /** A member with keys (minimal Argon2id costs, so the tests stay fast) and an open vault. */
    private fun member(userId: Int, secrets: MemoryDeviceSecretStore = MemoryDeviceSecretStore(available = true)): ChatContext {
        val context = support.context(userId, secrets = secrets)
        val identity = crypto.createIdentity()
        val locked = crypto.lockIdentity(identity, passphrase, KdfLimits.Minimum)
        val stored = run { context.repository.saveIdentity(locked, crypto.exportPublicKeys(identity)) }
        context.vault.setStoredIdentity(stored)
        assertTrue(context.vault.unlock(identity, context.vault.generation))
        return context
    }

    private fun conversation(owner: ChatContext, vararg others: Int): ChatConversation =
        run { owner.repository.createConversation("group", null, others.map(Int::toString), 7) }

    @Test
    fun setupFailsCleanlyForEveryReason() {
        val context = support.context(1)
        assertThrows(ChatCryptoException.InvalidInput::class.java) { run { context.keyring.setUp("") } }

        val vault = support.context(1).vault
        val locked = support.context(1, vault = vault, cryptoDispatcher = HookedDispatcher { vault.lock(announce = false) })
        assertNull("a lock while Argon2id runs drops the new keys", run { locked.keyring.setUp(passphrase) })
        assertNull(server.identities[1])

        val lockedDuringUpload = support.context(1)
        server.onRequest = { if (it == "PUT identity") lockedDuringUpload.vault.lock(announce = false) }
        assertNull(run { lockedDuringUpload.keyring.setUp(passphrase) })
        server.onRequest = {}

        failing("PUT", "identity")
        assertThrows(ApiError::class.java) { run { support.context(3).keyring.setUp(passphrase) } }
    }

    @Test
    fun unlockingChecksTheCopyAndThePublishedKeys() {
        val context = support.context(1)
        val identity = crypto.createIdentity()
        val bundle = crypto.exportPublicKeys(identity)
        assertThrows(ChatCryptoException.WrongPassphrase::class.java) {
            run { context.keyring.unlock(StoredIdentity(null, bundle), passphrase) }
        }
        val other = crypto.createIdentity()

        // Same signing key, other encryption key – validly bound by the signing key.
        fun mixed(encryptionFrom: ChatIdentity, signingFrom: ChatIdentity): PublicKeyBundle {
            val (encryption, _) = encryptionFrom.withPrivateKeys { e, s -> e.copyOf() to s.copyOf() }
            val (_, signing) = signingFrom.withPrivateKeys { e, s -> e.copyOf() to s.copyOf() }
            return crypto.exportPublicKeys(
                ChatIdentity(encryptionFrom.encryptionPublicKey, encryption, signingFrom.signingPublicKey, signing)
            )
        }
        for (published in listOf(crypto.exportPublicKeys(other), mixed(other, identity), bundle.copy(binding = bundle.signing))) {
            val copy = ChatIdentity(
                identity.encryptionPublicKey,
                identity.withPrivateKeys { e, _ -> e.copyOf() },
                identity.signingPublicKey,
                identity.withPrivateKeys { _, s -> s.copyOf() }
            )
            assertThrows(ChatKeyringException.PublishedKeysMismatch::class.java) {
                context.keyring.adopt(copy, StoredIdentity(null, published), context.vault.generation)
            }
            assertTrue(copy.isWiped)
        }
    }

    @Test
    fun theDirectoryFlagsMissingChangedAndInvalidKeys() {
        val mira = member(1)
        member(2)
        val chat = conversation(mira, 2, 3)
        server.identities[3] = buildJsonObject {
            put("user_id", "3")
            put("public_keys", Json.parseToJsonElement("""{"encryption":"AA","signing":"AA","binding":"AA"}"""))
        }
        mira.vault.rememberPublicKeys("2", crypto.verifyPublicKeys(crypto.exportPublicKeys(crypto.createIdentity())))
        val directory = run { mira.keyring.loadDirectory(chat.id) }
        assertEquals(listOf("2"), directory.changedInSession)
        assertEquals(listOf("3"), directory.invalid)
        assertNull(directory.user("4"))

        val withoutKeys = conversation(mira, 2)
        server.identities.remove(2)
        assertTrue(run { mira.keyring.loadDirectory(withoutKeys.id) }.invalid.isEmpty())
    }

    @Test
    fun onlyGrantsOfTheOwnerForThisChatAndMemberOpen() {
        val mira = member(1)
        val jonasIdentity = crypto.createIdentity()
        var grants = emptyList<KeyGrant>()
        val jonas = support.context(2, repository = { real ->
            object : ChatRepository by real {
                override suspend fun keyGrants(conversationId: String) = grants
            }
        })
        val locked = crypto.lockIdentity(jonasIdentity, passphrase, KdfLimits.Minimum)
        run { jonas.repository.saveIdentity(locked, crypto.exportPublicKeys(jonasIdentity)) }
        val chat = conversation(mira, 2)

        run { jonas.keyring.openGrants(chat) }
        jonas.vault.rememberPublicKeys("1", crypto.verifyPublicKeys(crypto.exportPublicKeys(checkNotNull(mira.vault.identity))))
        run { jonas.keyring.openGrants(chat) }
        assertTrue(jonas.vault.unlock(jonasIdentity, jonas.vault.generation))

        val owner = checkNotNull(mira.vault.identity)
        fun grant(version: Int, conversationId: String = chat.id, recipient: String = "2", granter: String = "1") = crypto.createKeyGrant(
            crypto.randomBytes(32),
            conversationId,
            version,
            recipient,
            jonasIdentity.encryptionPublicKey,
            granter,
            owner
        )
        val garbage = grant(9).copy(signature = grant(9).wrappedKey)
        grants = listOf(grant(1), grant(1), garbage, grant(2, conversationId = "999"), grant(3, recipient = "3"), grant(4, granter = "3"))
        // The pending version stays closed: until its activation the owner may still replace the batch.
        run { jonas.keyring.openGrants(chat) }
        assertNull(jonas.vault.conversationKey(chat.id, 1))
        run { jonas.keyring.openGrants(chat.copy(rotationRequired = false)) }
        assertNotNull(jonas.vault.conversationKey(chat.id, 1))
        for (version in 2..9) assertNull(jonas.vault.conversationKey(chat.id, version))
    }

    @Test
    fun theOwnerKeepsTheKeyOfTheBatchTheServerActivated() {
        val browserKey = crypto.randomBytes(32)
        var browserReplaces = false
        var owner: ChatContext? = null
        val mira = support.context(1, repository = { real ->
            object : ChatRepository by real {
                // Mira's browser stored its own batch for the pending version just before this activation.
                override suspend fun activateKey(conversationId: String, keyVersion: Int): ChatConversation {
                    if (browserReplaces) {
                        val vault = checkNotNull(owner).vault
                        val identity = checkNotNull(vault.identity)
                        val batch = listOf("1", "2").map { recipient ->
                            val keys = checkNotNull(vault.publicKeys(recipient))
                            crypto.createKeyGrant(browserKey, conversationId, keyVersion, recipient, keys.encryption, "1", identity)
                        }
                        real.storeKeyGrants(conversationId, keyVersion, batch)
                    }
                    return real.activateKey(conversationId, keyVersion)
                }
            }
        })
        owner = mira
        val identity = crypto.createIdentity()
        val locked = crypto.lockIdentity(identity, passphrase, KdfLimits.Minimum)
        val stored = run { mira.repository.saveIdentity(locked, crypto.exportPublicKeys(identity)) }
        mira.vault.setStoredIdentity(stored)
        assertTrue(mira.vault.unlock(identity, mira.vault.generation))
        member(2)
        member(3)
        val chat = conversation(mira, 2, 3)
        run { mira.keyring.loadDirectory(chat.id) }
        run { mira.keyring.distributeKey(chat) }
        val first = checkNotNull(mira.vault.conversationKey(chat.id, 1)).copyOf()

        // Aylin leaves: version 2 is pending, and the browser's batch replaces Mira's before her activation.
        val smaller = run { mira.repository.removeMember(chat.id, "3") }
        browserReplaces = true
        run { mira.keyring.distributeKey(smaller) }
        assertArrayEquals("the key of the activated batch, which Jonas has", browserKey, mira.vault.conversationKey(chat.id, 2))
        assertArrayEquals("older versions stay as they are", first, mira.vault.conversationKey(chat.id, 1))
    }

    @Test
    fun publishedKeysAreNeverThrownAway() {
        val secrets = MemoryDeviceSecretStore(available = true)
        // Nothing published: the new secret goes again.
        failing("PUT", "identity")
        assertThrows(ApiError::class.java) { run { support.context(1, secrets = secrets).devices.setUpWithDevice() } }
        assertNull(run { secrets.credentialId("1") })

        // Published, but the device copy failed: the keys stay open, so a copy can still be kept.
        val mira = support.context(1, secrets = secrets)
        failing("POST", "device-unlocks")
        assertThrows(ChatKeyringException.DeviceCopyNotStored::class.java) { run { mira.devices.setUpWithDevice() } }
        assertTrue(mira.vault.unlocked)
        assertEquals(crypto.exportPublicKeys(checkNotNull(mira.vault.identity)), mira.vault.storedIdentity?.publicKeys)
        assertNotNull("the row may have been stored before its answer got lost", run { secrets.credentialId("1") })
        run { mira.devices.register() }
        assertEquals(1, server.deviceUnlocks.count { it.first == 1 })

        // Published while Oasis went to the background: known as the stored identity, unlocked on return.
        val jonas = support.context(2, secrets = secrets)
        server.onRequest = { if (it == "PUT identity") jonas.vault.lockForBackground() }
        assertNull(run { jonas.devices.setUpWithDevice() })
        val aylin = support.context(3)
        server.onRequest = { if (it == "PUT identity") aylin.vault.lockForBackground() }
        assertNull(run { aylin.keyring.setUp(passphrase) })
        server.onRequest = {}
        assertFalse(jonas.vault.unlocked)
        assertNotNull(jonas.vault.storedIdentity)
        assertEquals("its device copy is stored", 1, server.deviceUnlocks.count { it.first == 2 })
        assertFalse(aylin.vault.unlocked)
        assertNotNull(aylin.vault.storedIdentity?.lockedIdentity)
    }

    @Test
    fun theOwnersOwnGrantGoesToItsOwnKeyNotTheDirectorysCopy() {
        val mira = member(1)
        val jonas = member(2)
        val chat = conversation(mira, 2)
        run { mira.keyring.loadDirectory(chat.id) }
        // A lying directory: Mira's own entry carries someone else's keys.
        mira.vault.rememberPublicKeys("1", crypto.verifyPublicKeys(crypto.exportPublicKeys(crypto.createIdentity())))
        run { mira.keyring.distributeKey(chat) }
        assertNotNull("Mira opens her own stored grant", mira.vault.conversationKey(chat.id, 1))
        run { jonas.keyring.loadDirectory(chat.id) }
        run { jonas.keyring.openGrants(run { jonas.repository.conversation(chat.id) }) }
        assertArrayEquals(mira.vault.conversationKey(chat.id, 1), jonas.vault.conversationKey(chat.id, 1))
    }

    @Test
    fun keyDistributionNeedsEveryKeyAndAnOpenVault() {
        val mira = member(1)
        val chat = conversation(mira, 2)
        val error = assertThrows(ChatKeyringException.MissingMemberKeys::class.java) { run { mira.keyring.distributeKey(chat) } }
        assertEquals("the own grant needs no directory entry", listOf("jonas"), error.usernames)
        val alone = chat.copy(members = listOf(ConversationMember("1", "mira")))
        mira.vault.rememberPublicKeys("1", crypto.verifyPublicKeys(crypto.exportPublicKeys(checkNotNull(mira.vault.identity))))
        mira.vault.lock(announce = false)
        assertThrows(ChatCryptoException.InvalidInput::class.java) { run { mira.keyring.distributeKey(alone) } }
        assertNull(mira.keyring.safetyNumber("1"))
        val again = member(1)
        assertNull(again.keyring.safetyNumber("2"))
    }

    @Test
    fun deviceSetupAndRegistrationCleanUpAfterThemselves() {
        val secrets = MemoryDeviceSecretStore(available = true)
        val first = support.context(1, secrets = secrets)
        server.onRequest = { if (it == "POST device-unlocks") first.vault.lock(announce = false) }
        assertNull("a lock during the upload keeps the vault closed", run { first.devices.setUpWithDevice() })
        server.onRequest = {}

        // A failed device copy keeps the published keys open (and the secret, whose row may exist after all).
        val failing = support.context(2, secrets = secrets)
        failing("POST", "device-unlocks")
        assertThrows(ChatKeyringException.DeviceCopyNotStored::class.java) { run { failing.devices.setUpWithDevice() } }
        assertTrue(failing.vault.unlocked)
        assertNotNull(run { secrets.credentialId("2") })

        val closed = support.context(3, secrets = secrets)
        assertThrows(ChatKeyringException.DeviceUnlockUnsupported::class.java) { run { closed.devices.register() } }
        val open = member(3, secrets)
        failing("POST", "device-unlocks")
        assertThrows(ApiError::class.java) { run { open.devices.register() } }
        assertNull(run { secrets.credentialId("3") })
    }

    @Test
    fun devicesUnlockRemoveAndLinkOnlyWhatIsTheirs() {
        val secrets = MemoryDeviceSecretStore(available = true)
        val mira = member(1, secrets)
        val stored = checkNotNull(mira.vault.storedIdentity)
        assertThrows(ChatKeyringException.DeviceUnlockUnsupported::class.java) { run { mira.devices.unlock(stored, emptyList()) } }

        val own = run { mira.devices.register() }
        val foreign = DeviceUnlockRow("77", "web-passkey", "Browser", own.lockedIdentity, "2026-10-22T12:00:00Z")
        run { mira.devices.remove(foreign) }
        assertNotNull("another device's row keeps this device's secret", run { secrets.credentialId("1") })
        run { mira.devices.remove(own) }
        assertNull(run { secrets.credentialId("1") })

        val laptop = support.context(1)
        val link = run { laptop.devices.requestLink() }
        assertNull("not approved yet", run { laptop.devices.pickUp(link, stored) })
        failing("DELETE", "device-links/${link.id}")
        run { laptop.devices.cancel(link) }
        mira.vault.lock()
        assertThrows(ChatKeyringException.DeviceUnlockUnsupported::class.java) {
            run { mira.devices.approve(DeviceLinkRequest(link.id, "key", "Laptop")) }
        }
    }
}
