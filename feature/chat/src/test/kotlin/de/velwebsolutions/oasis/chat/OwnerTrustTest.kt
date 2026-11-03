package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.api.PublicUser
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatKeyring
import de.velwebsolutions.oasis.chat.core.ChatKeyringException
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.core.ChatVault
import de.velwebsolutions.oasis.chat.core.describeChatError
import de.velwebsolutions.oasis.chat.data.ChatRepository
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.chatcrypto.KdfLimits
import de.velwebsolutions.oasis.chatcrypto.KeyGrant
import de.velwebsolutions.oasis.chatcrypto.createKeyGrant
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The owner checks of resources/js/chat/client/keyring.js (`ownerIsMember`, `starterLeft`, `ownerTrusted`,
 * `assertSealable`): a lying server gets no grant opened and nothing sealed, while rotation and ended chats keep working.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerTrustTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()
    private val server get() = support.server

    private fun model(context: ChatContext, id: String) = ConversationViewModel(id, context, FakeRealtime()).apply {
        pollInterval = Duration.INFINITE
        sweepInterval = Duration.INFINITE
        recordingTick = Duration.INFINITE
    }

    private suspend fun chat(owner: ChatContext, vararg others: Int, type: String = "group") =
        owner.repository.createConversation(type, null, others.map(Int::toString), 7).id

    private fun conversation(
        type: String = "group",
        createdBy: String = "1",
        members: List<ConversationMember> = listOf(ConversationMember("1", "mira", role = "owner"), ConversationMember("2", "jonas")),
        rotationRequired: Boolean = false,
        keyVersion: Int = 1
    ) = ChatConversation(
        id = "5",
        type = type,
        createdBy = createdBy,
        lifetimeDays = 7,
        keyVersion = keyVersion,
        rotationRequired = rotationRequired,
        members = members,
        serverTime = "2026-10-22T12:00:00Z"
    )

    @Test
    fun theOwnerMustBeAnActiveOwnerMember() {
        val mira = ConversationMember("1", "mira", role = "owner")
        val jonas = ConversationMember("2", "jonas")
        val aylin = ConversationMember("3", "aylin")
        assertTrue(ChatKeyring.ownerIsMember(conversation()))
        assertTrue(ChatKeyring.ownerIsMember(conversation(type = "direct")))
        assertTrue("the other one left a direct chat", ChatKeyring.ownerIsMember(conversation(type = "direct", members = listOf(mira))))
        assertFalse("not a member", ChatKeyring.ownerIsMember(conversation(createdBy = "9")))
        assertFalse("listed as member", ChatKeyring.ownerIsMember(conversation(members = listOf(mira.copy(role = "member"), jonas))))
        assertFalse(
            "a direct chat has two people",
            ChatKeyring.ownerIsMember(conversation(type = "direct", members = listOf(mira, jonas, aylin)))
        )
        assertTrue(ChatKeyring.ownerIsMember(conversation(members = listOf(mira, jonas, aylin))))

        val left = conversation(type = "direct", members = listOf(jonas), rotationRequired = true, keyVersion = 2)
        assertTrue(ChatKeyring.starterLeft(left))
        assertFalse(ChatKeyring.starterLeft(left.copy(rotationRequired = false)))
        assertFalse(ChatKeyring.starterLeft(left.copy(type = "group")))
        assertFalse(ChatKeyring.starterLeft(left.copy(members = listOf(jonas, aylin))))
        assertFalse(ChatKeyring.starterLeft(left.copy(members = listOf(mira))))
    }

    @Test
    fun grantsOpenOnlyForABelievableOwner() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = chat(mira, 2, type = "direct")
        model(mira, id).open()
        // The owner's keys are not known yet: nothing opens.
        jonas.keyring.openGrants(jonas.repository.conversation(id))
        assertNull(jonas.vault.conversationKey(id, 1))
        jonas.keyring.loadDirectory(id)
        val honest = jonas.repository.conversation(id)
        val members = honest.members
        // No owner among the members and no ended chat: nothing opens.
        jonas.keyring.openGrants(honest.copy(createdBy = "9"))
        assertNull(jonas.vault.conversationKey(id, 1))
        // An ended chat whose starter left, but another starter than pinned: nothing opens either.
        val left = honest.copy(createdBy = "9", members = members.filter { it.userId == "2" }, rotationRequired = true, keyVersion = 2)
        assertTrue(ChatKeyring.starterLeft(left))
        jonas.vault.pinOwner(id, "1")
        jonas.keyring.openGrants(left)
        assertNull(jonas.vault.conversationKey(id, 1))
        // The starter it pinned left: the older key opens, for the history.
        jonas.keyring.openGrants(left.copy(createdBy = "1"))
        assertNotNull(jonas.vault.conversationKey(id, 1))
    }

    @Test
    fun theVaultPinsTheFirstOwnerAndKnowsTheNewestKey() {
        val vault = ChatVault(CoroutineScope(Dispatchers.Unconfined), idleLock = Duration.INFINITE)
        vault.prepare("2")
        assertTrue(vault.pinOwner("5", "1"))
        assertTrue(vault.pinOwner("5", "1"))
        assertFalse(vault.pinOwner("5", "3"))
        assertTrue("per conversation", vault.pinOwner("50", "3"))
        vault.lock(announce = false)
        assertFalse("pins outlive a lock, like the web tab's", vault.pinOwner("5", "3"))
        vault.prepare("3")
        assertTrue("another member starts afresh", vault.pinOwner("5", "3"))

        assertEquals(0, vault.newestKeyVersion("5"))
        assertTrue(vault.unlock(testCrypto.createIdentity(), vault.generation))
        vault.rememberConversationKey("5", 1, testCrypto.randomBytes(32), vault.generation)
        vault.rememberConversationKey("5", 3, testCrypto.randomBytes(32), vault.generation)
        vault.rememberConversationKey("50", 7, testCrypto.randomBytes(32), vault.generation)
        assertEquals(3, vault.newestKeyVersion("5"))
        assertEquals(7, vault.newestKeyVersion("50"))
        vault.lock(announce = false)
        assertEquals(0, vault.newestKeyVersion("5"))
    }

    @Test
    fun nothingIsSealedForAnUntrustedOwnerOrAnOlderVersion() {
        val vault = ChatVault(CoroutineScope(Dispatchers.Unconfined), idleLock = Duration.INFINITE)
        vault.prepare("2")
        assertTrue(vault.unlock(testCrypto.createIdentity(), vault.generation))
        val chat = conversation(keyVersion = 2)
        vault.rememberConversationKey("5", 2, testCrypto.randomBytes(32), vault.generation)
        assertNotNull(ChatMessages.seal("Hallo", chat, vault, testCrypto))
        assertThrows(ChatKeyringException.UntrustedOwner::class.java) {
            ChatMessages.seal("Hallo", chat.copy(createdBy = "9"), vault, testCrypto)
        }
        val other = chat.copy(
            members = listOf(ConversationMember("1", "mira"), ConversationMember("3", "aylin", role = "owner")),
            createdBy = "3"
        )
        assertTrue(ChatKeyring.ownerIsMember(other))
        assertThrows("another owner than before", ChatKeyringException.UntrustedOwner::class.java) {
            ChatMessages.seal("Hallo", other, vault, testCrypto)
        }
        vault.rememberConversationKey("5", 1, testCrypto.randomBytes(32), vault.generation)
        vault.rememberConversationKey("5", 3, testCrypto.randomBytes(32), vault.generation)
        assertThrows(ChatKeyringException.StaleKeyVersion::class.java) { ChatMessages.seal("Hallo", chat, vault, testCrypto) }

        assertEquals(ChatKeyring.UNTRUSTED_OWNER, describeChatError(ChatKeyringException.UntrustedOwner))
        assertEquals(
            "Die Person, die diesen Chat gestartet hat, ist nicht dabei oder eine andere als vorher. Zum Schutz deiner Nachrichten kannst du hier nichts schicken. Bitte melde dich bei uns.",
            ChatKeyring.UNTRUSTED_OWNER
        )
        assertEquals(
            "Der Chat wird gerade neu gesichert. Versuch es gleich noch einmal.",
            describeChatError(ChatKeyringException.StaleKeyVersion)
        )
    }

    @Test
    fun aServerNamingAnotherOwnerGetsNoGrantOpenedAndNoComposer() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        support.member(3)
        val id = chat(mira, 2)
        model(mira, id).open()

        // Aylin – not a member – is named as owner from the start: Jonas opens nothing and cannot write.
        server.conversations.getValue(id.toInt()).shownOwner = 3
        val reader = model(jonas, id)
        reader.open()
        assertNull(jonas.vault.conversationKey(id, 1))
        assertTrue(reader.state.value.untrustedOwner)
        assertFalse(reader.state.value.canSend)
        assertFalse("the reason stands where the composer was", reader.state.value.notices.any { it.contains("kein Schlüssel") })

        // The owner listed as plain member is no owner either.
        server.conversations.getValue(id.toInt()).shownOwner = null
        server.conversations.getValue(id.toInt()).ownerRoleHidden = true
        reader.open()
        assertTrue(reader.state.value.untrustedOwner)
        assertNull(jonas.vault.conversationKey(id, 1))

        // Honest again: the key opens and Jonas writes.
        server.conversations.getValue(id.toInt()).ownerRoleHidden = false
        reader.open()
        assertFalse(reader.state.value.untrustedOwner)
        assertTrue(reader.state.value.canSend)

        // Aylin joins and the server then names her as owner: Jonas still holds a key, but nothing goes out any more.
        mira.repository.addMember(id, "3")
        model(mira, id).open()
        server.conversations.getValue(id.toInt()).shownOwner = 3
        reader.open()
        assertNull("grants of another owner stay closed", jonas.vault.conversationKey(id, 2))
        assertNotNull(jonas.vault.conversationKey(id, 1))
        assertTrue(reader.state.value.untrustedOwner)
        assertFalse(reader.state.value.canSend)
        reader.setDraft("Hallo")
        reader.send()
        assertTrue(server.messages[id.toInt()].isNullOrEmpty())
        assertThrows(ChatKeyringException.UntrustedOwner::class.java) {
            ChatMessages.seal("Hallo", checkNotNull(reader.state.value.conversation), jonas.vault, testCrypto)
        }
    }

    @Test
    fun aDirectChatWithMoreThanTwoPeopleIsRefused() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        support.member(3)
        val id = chat(mira, 2, type = "direct")
        model(mira, id).open()
        server.conversations.getValue(id.toInt()).members += 3
        val reader = model(jonas, id)
        reader.open()
        assertTrue(reader.state.value.untrustedOwner)
        assertNull(jonas.vault.conversationKey(id, 1))
    }

    @Test
    fun aMessageForAnOlderVersionIsRefusedBeforeItsFilesGoUp() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        support.member(3)
        val id = chat(mira, 2)
        model(mira, id).open()
        val writer = model(jonas, id)
        writer.open()
        assertTrue(writer.state.value.canSend)

        // Aylin joins; Jonas' other screen of the chat already opened version 2, this one still shows version 1.
        mira.repository.addMember(id, "3")
        model(mira, id).open()
        jonas.keyring.openGrants(jonas.repository.conversation(id))
        assertNotNull(jonas.vault.conversationKey(id, 2))
        writer.startRecording()
        writer.finishRecording()
        writer.setDraft("Hallo")
        writer.send()
        assertEquals(ChatKeyring.NEW_KEY, writer.state.value.composerError)
        assertTrue("no file went up", server.uploads.isEmpty())
        assertTrue(server.messages[id.toInt()].isNullOrEmpty())

        // The refusal reloaded the chat: the next try goes out with version 2.
        assertEquals(2, writer.state.value.conversation?.keyVersion)
        writer.send()
        assertEquals(1, server.messages.getValue(id.toInt()).size)
        assertEquals(1, server.uploads.size)
    }

    @Test
    fun rotationKeepsTheChatWritable() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        support.member(3)
        val id = chat(mira, 2, 3)
        model(mira, id).open()
        mira.repository.removeMember(id, "3")
        val owner = model(mira, id)
        owner.open()
        assertTrue(owner.state.value.canSend)
        val reader = model(jonas, id)
        reader.open()
        assertFalse(reader.state.value.untrustedOwner)
        assertTrue(reader.state.value.canSend)
        reader.setDraft("Noch da")
        reader.send()
        assertEquals("Noch da", reader.state.value.timeline.items.single().plaintext)
    }

    @Test
    fun anEndedDirectChatStaysReadableWithoutComposer() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = chat(mira, 2, type = "direct")
        val starter = model(mira, id)
        starter.open()
        starter.setDraft("Hallo Jonas")
        starter.send()

        // Jonas leaves: Mira reads on, distributes no new key and gets the ended notice, not the untrusted one.
        jonas.repository.leave(id)
        starter.open()
        assertEquals(
            "Dieser Chat ist beendet. Du kannst ihn noch lesen und Nachrichten melden, aber nicht mehr schreiben.",
            starter.state.value.notices.first()
        )
        assertFalse(starter.state.value.untrustedOwner)
        assertFalse(starter.state.value.canSend)
        assertTrue(server.grants.getValue(id.toInt()).none { it["key_version"].toString() == "2" })
        assertEquals("Hallo Jonas", starter.state.value.timeline.items.single().plaintext)
    }

    @Test
    fun aDirectChatWhoseStarterLeftStillOpensItsHistory() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = chat(mira, 2, type = "direct")
        val starter = model(mira, id)
        starter.open()
        starter.setDraft("Hallo Jonas")
        starter.send()
        mira.repository.leave(id)

        // A fresh vault (no key, no pin yet) still opens the starter's older key, for the history.
        val again = support.context(2)
        assertTrue(again.keyring.unlock(checkNotNull(jonas.vault.storedIdentity), TEST_PASSPHRASE))
        val reader = model(again, id)
        reader.open()
        assertEquals("Hallo Jonas", reader.state.value.timeline.items.single().plaintext)
        assertFalse(reader.state.value.untrustedOwner)
        assertFalse(reader.state.value.canSend)
        assertTrue(reader.state.value.notices.first().startsWith("Dieser Chat ist beendet."))
    }

    @Test
    fun theOwnersOwnGrantsVerifyAgainstItsOwnKey() = runTest {
        val attacker = testCrypto.createIdentity()
        var forged = false
        var keepGrants: (List<KeyGrant>) -> List<KeyGrant> = { it }
        val mira = support.context(1, repository = { real ->
            object : ChatRepository by real {
                // A lying directory: Mira's own entry carries the attacker's keys.
                override suspend fun publicKeys(conversationId: String): List<PublicUser> = real.publicKeys(conversationId).map {
                    if (forged && it.id == "1") it.copy(publicKeys = testCrypto.exportPublicKeys(attacker)) else it
                }

                override suspend fun keyGrants(conversationId: String): List<KeyGrant> = keepGrants(real.keyGrants(conversationId))
            }
        })
        val identity = testCrypto.createIdentity()
        val copy = testCrypto.lockIdentity(identity, TEST_PASSPHRASE, KdfLimits.Minimum)
        val stored = mira.repository.saveIdentity(copy, testCrypto.exportPublicKeys(identity))
        mira.vault.setStoredIdentity(stored)
        assertTrue(mira.vault.unlock(identity, mira.vault.generation))
        support.member(2)
        val id = chat(mira, 2)
        model(mira, id).open()
        val honest = checkNotNull(mira.vault.conversationKey(id, 1)).copyOf()

        // After a lock the attacker hands Mira a key of its own for version 1, "granted by Mira".
        mira.vault.lock(announce = false)
        assertTrue(mira.keyring.unlock(stored, TEST_PASSPHRASE))
        forged = true
        val conversation = mira.repository.conversation(id)
        val fake = testCrypto.createKeyGrant(testCrypto.randomBytes(32), id, 1, "1", identity.encryptionPublicKey, "1", attacker)
        keepGrants = { listOf(fake) }
        mira.keyring.loadDirectory(id)
        mira.keyring.openGrants(conversation)
        assertNull(mira.vault.conversationKey(id, 1))

        // Its real grant still opens with the directory lying.
        keepGrants = { it }
        mira.keyring.openGrants(conversation)
        assertArrayEquals(honest, mira.vault.conversationKey(id, 1))
    }
}
