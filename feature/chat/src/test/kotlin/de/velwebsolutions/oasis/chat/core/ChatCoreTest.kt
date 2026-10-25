package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.SafetyLists
import de.velwebsolutions.oasis.chat.testCrypto
import de.velwebsolutions.oasis.chatcrypto.ChatCryptoException
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.VerifiedPublicKeys
import de.velwebsolutions.oasis.chatcrypto.encryptMessage
import de.velwebsolutions.oasis.core.network.ApiError
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat's logic below the view models: errors, safety checks, messages, the timeline, the vault and its clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatCoreTest {
    private val crypto = testCrypto
    private val tone = SafetyLists.Tone(always = listOf("digga"), address = listOf("alter"))

    @Test
    fun describesEveryErrorInGerman() {
        val texts = mapOf(
            ChatCryptoException.WrongPassphrase to "Die Passphrase stimmt nicht.",
            ChatCryptoException.MessageTooLong(4000) to "Die Nachricht ist zu lang.",
            ChatKeyringException.MissingMemberKeys(listOf("mira", "jonas")) to
                "Für mira, jonas gibt es keine gültigen Chat-Schlüssel. Der Chat kann noch keinen Schlüssel bekommen.",
            ChatKeyringException.PublishedKeysMismatch to
                "Die veröffentlichten Schlüssel passen nicht zu deinen. Bitte melde dich bei uns – schreib vorerst nichts Vertrauliches.",
            ApiError.Unauthenticated to "Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.",
            ApiError.Forbidden("Mira nimmt gerade keine Nachrichten an.") to "Mira nimmt gerade keine Nachrichten an.",
            ApiError.Forbidden("This action is unauthorized.") to "Du hast keinen Zugriff (mehr) auf diesen Chat.",
            ApiError.Forbidden("") to "Du hast keinen Zugriff (mehr) auf diesen Chat.",
            ApiError.NotFound to "Diesen Chat gibt es nicht mehr.",
            ApiError.Conflict("stale") to "Der Chat bekommt gerade einen neuen Schlüssel. Versuch es gleich noch einmal.",
            ApiError.Validation("Zusammenfassung", mapOf("title" to listOf("Zu lang."))) to "Zu lang.",
            ApiError.Validation("Zusammenfassung", mapOf("title" to emptyList())) to "Zusammenfassung",
            ApiError.Validation("", emptyMap()) to "Die Eingabe passt nicht.",
            ApiError.TooManyRequests(60) to "Zu viele Anfragen in kurzer Zeit. Warte eine Minute.",
            ApiError.Transport("offline") to ApiError.Transport("offline").userMessage,
            ChatKeyringException.DeviceUnlockUnsupported to "Da ist etwas schiefgegangen. Versuch es gleich noch einmal."
        )
        for ((error, text) in texts) assertEquals(error.toString(), text, describeChatError(error))
    }

    @Test
    fun findsSecretsSolicitationAndSlangEverywhereTheyHide() {
        val lists = mapOf("sexual" to listOf("ficken"), "insult" to emptyList())
        assertEquals(ChatSafety.Kind.Solicitation, ChatSafety.scanIncoming("willst du ficken", lists)?.kind)
        assertNull(ChatSafety.scanIncoming("ganz normal", emptyMap()))
        assertEquals(
            listOf("eine Kartennummer", "eine IBAN"),
            ChatSafety.scanOutgoing("4111-1111-1111-1111 und DE89 3704 0044 0532 0130 00", tone).sensitive
        )
        assertFalse("other scripts' digits are no card number", ChatSafety.containsCardNumber("٤١١١٤١١١٤١١١٤١١١"))
        assertEquals("alter", ChatSafety.toneHint("Hör auf, alter", tone))
        assertEquals("Digga", ChatSafety.toneHint("Na Digga", SafetyLists.Tone(always = listOf("digga"))))
        assertNull(ChatSafety.toneHint("Na Digga", SafetyLists.Tone()))
        assertNull(ChatSafety.toneHint("Na du", tone))
    }

    @Test
    fun measuresDraftsByCharactersAndBytes() {
        assertFalse(ChatMessages.measure("a".repeat(4000)).tooLong)
        assertTrue(ChatMessages.measure("a".repeat(4001)).tooLong)
        assertTrue("1500 palm trees exceed the byte limit", ChatMessages.measure("🌴".repeat(1500)).tooLong)
        assertEquals(DraftMeasure(1, 4, false), ChatMessages.measure("🌴"))
    }

    @Test
    fun formatsLifetimesAndDates() {
        assertEquals("1 Tag", lifetimeLabel(1))
        assertEquals("7 Tagen", lifetimeLabel(7))
        assertEquals("18.10.2026", formatDate("2026-10-18T12:00:00Z"))
        assertEquals("kein Datum", formatDate("kein Datum"))
        assertEquals("18.10.2026, 14:00", formatDateTime(Instant.parse("2026-10-18T12:00:00Z")))
        assertEquals(listOf(1, 7, 30), lifetimeChoices.map { it.first })
    }

    private fun vault(scope: TestScope, idle: Duration = Duration.INFINITE) = ChatVault(scope.backgroundScope, idle).apply { prepare("1") }

    private fun conversation(keyVersion: Int = 1) =
        ChatConversation("7", "direct", null, "1", 7, keyVersion, false, emptyList(), "2026-10-22T12:00:00Z")

    @Test
    fun sealingNeedsAKeyAndAnOpenVault() = kotlinx.coroutines.test.runTest {
        val vault = vault(this)
        assertThrows(ChatCryptoException.InvalidInput::class.java) { ChatMessages.seal("Hallo", conversation(), vault, crypto) }
        vault.unlock(crypto.createIdentity(), vault.generation)
        assertThrows(ChatCryptoException.InvalidInput::class.java) { ChatMessages.seal("Hallo", conversation(), vault, crypto) }
        vault.rememberConversationKey("7", 1, crypto.createConversationKeyForTest(), vault.generation)
        assertEquals("7", ChatMessages.seal("Hallo", conversation(), vault, crypto).conversationId)
        vault.lock(announce = false)
        assertThrows(ChatCryptoException.InvalidInput::class.java) { ChatMessages.seal("Hallo", conversation(), vault, crypto) }
    }

    @Test
    fun opensEnvelopesIntoReadablePlaceholdersOrNothing() = kotlinx.coroutines.test.runTest {
        val vault = vault(this)
        val sender = crypto.createIdentity()
        val key = crypto.createConversationKeyForTest()
        val now = vault.clock.now()
        fun envelope(expires: Instant?, text: String = "Hallo"): MessageEnvelope {
            val sealed = crypto.encryptMessage(key, "7", 1, "2", sender, text, now.plusSeconds(3600), now)
            return if (expires == null) sealed else sealed.copy(expiresAt = expires.toString())
        }
        val fresh = envelope(null)
        assertEquals(ChatMessageItem.State.NoKey, ChatMessages.open(fresh, "7", vault, crypto)?.state)
        vault.unlock(crypto.createIdentity(), vault.generation)
        vault.rememberConversationKey("7", 1, key.copyOf(), vault.generation)
        assertEquals("unknown sender", ChatMessageItem.State.Unverifiable, ChatMessages.open(fresh, "7", vault, crypto)?.state)
        vault.rememberPublicKeys("2", VerifiedPublicKeys(sender.encryptionPublicKey, sender.signingPublicKey))
        val readable = checkNotNull(ChatMessages.open(fresh, "7", vault, crypto))
        assertEquals("Hallo", readable.plaintext)
        assertEquals(readable.signature, readable.id)
        assertNotNull(readable.expiresAt)
        assertEquals(
            "tampered",
            ChatMessageItem.State.Unverifiable,
            ChatMessages.open(fresh.copy(nonce = fresh.signature), "7", vault, crypto)?.state
        )

        val expired = crypto.encryptMessage(key, "7", 1, "2", sender, "Alt", now.plusSeconds(10), now.minusSeconds(60))
        vault.clock.sync(now.plusSeconds(20).toString())
        assertNull("expired messages are dropped", ChatMessages.open(expired, "7", vault, crypto))
        val withoutExpiry = fresh.copy(expiresAt = null)
        assertNull(ChatMessages.open(withoutExpiry, "7", vault, crypto)?.expiresAt)
    }

    private fun item(signature: String, expiresAt: Instant? = null) = ChatMessageItem(
        signature,
        MessageEnvelope(1, "7", 1, "2", "n", "c", signature),
        "2",
        expiresAt,
        ChatMessageItem.State.Readable,
        "x"
    )

    @Test
    fun keepsTheTimelineInOrderAndWithoutDuplicates() {
        val now = Instant.parse("2026-10-22T12:00:00Z")
        val timeline = checkNotNull(ChatTimeline().appending(item("a", now.plusSeconds(60))))
        assertNull(timeline.appending(null))
        assertNull(timeline.appending(item("a")))
        val longer = timeline.prepending(listOf(item("z"), null, item("a"), item("y"), item("z")))
        assertEquals(listOf("z", "y", "a"), longer.items.map { it.signature })
        val replaced = longer.replacing(item("y").copy(plaintext = "neu"))
        assertEquals(listOf("x", "neu", "x"), replaced.items.map { it.plaintext })
        assertNull("nothing expired", replaced.droppingExpired(now))
        assertEquals(listOf("z", "y"), replaced.droppingExpired(now.plusSeconds(60))?.items?.map { it.signature })
    }

    @Test
    fun theVaultKeepsKeysOnlyWhileUnlockedAndForTheRightMember() = kotlinx.coroutines.test.runTest {
        val vault = vault(this)
        vault.prepare("1")
        val stale = crypto.createIdentity()
        assertFalse("a lock in between wipes the identity", vault.unlock(stale, vault.generation - 1))
        assertTrue(stale.isWiped)
        assertFalse(vault.rememberConversationKey("7", 1, ByteArray(32), vault.generation))

        assertTrue(vault.unlock(crypto.createIdentity(), vault.generation))
        assertTrue(vault.unlockedFlow.value)
        val first = ByteArray(32) { 1 }
        assertTrue(vault.rememberConversationKey("7", 1, first, vault.generation))
        assertTrue(vault.rememberConversationKey("7", 1, ByteArray(32) { 2 }, vault.generation))
        assertTrue("the replaced key is wiped", first.all { it == 0.toByte() })
        assertFalse(vault.rememberConversationKey("7", 2, ByteArray(32), vault.generation - 1))

        val keys = VerifiedPublicKeys(ByteArray(32), ByteArray(32))
        assertFalse(vault.rememberPublicKeys("2", keys))
        assertFalse(vault.rememberPublicKeys("2", keys))
        assertTrue(vault.rememberPublicKeys("2", VerifiedPublicKeys(ByteArray(32), ByteArray(32) { 1 })))

        vault.lockForBackground()
        assertEquals("Nachrichten gesperrt. Deine Schlüssel sind aus dem Speicher gelöscht.", vault.announcement.value)
        vault.announcement.value = null
        vault.lockForBackground()
        vault.lock()
        assertNull("locking a locked vault says nothing", vault.announcement.value)
        assertNull(vault.conversationKey("7", 1))
        assertNotNull("public keys are public and stay", vault.publicKeys("2"))

        vault.prepare("2")
        assertNull("another member starts empty", vault.publicKeys("2"))
        assertSame(SafetyLists.Empty, vault.safety)
        assertFalse(vault.stored.isKnown)
        vault.setStoredIdentity(null)
        assertTrue(vault.stored.isKnown)
    }

    @Test
    fun theIdleTimerLocksOnlyTheSessionItWasStartedFor() = kotlinx.coroutines.test.runTest {
        val vault = vault(this, idle = 30.minutes)
        vault.touch()
        vault.unlock(crypto.createIdentity(), vault.generation)
        advanceTimeBy(29.minutes)
        vault.touch()
        advanceTimeBy(29.minutes)
        assertTrue(vault.unlocked)
        // A lock and a new unlock in between: the old timer must not lock the new session.
        vault.lock(announce = false)
        vault.unlock(crypto.createIdentity(), vault.generation)
        advanceTimeBy(31.minutes)
        runCurrent()
        assertFalse(vault.unlocked)
    }

    @Test
    fun theServerClockFollowsTheServerAndIgnoresNonsense() {
        var local = Instant.parse("2026-10-22T12:00:00Z")
        val clock = ServerClock { local }
        clock.sync("2026-10-22T12:00:10Z")
        assertEquals(Instant.parse("2026-10-22T12:00:10Z"), clock.now())
        clock.sync("keine Zeit")
        assertEquals(Instant.parse("2026-10-22T12:00:10Z"), clock.now())
        local = local.plusMillis(500)
        assertEquals(Instant.parse("2026-10-23T12:00:10Z"), clock.expiry(1))
    }
}

private fun de.velwebsolutions.oasis.chatcrypto.ChatCrypto.createConversationKeyForTest() = randomBytes(32)
