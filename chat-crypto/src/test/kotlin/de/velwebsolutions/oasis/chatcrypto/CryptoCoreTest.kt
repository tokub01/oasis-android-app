package de.velwebsolutions.oasis.chatcrypto

import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin core on its own: round trips, limits, malformed input and the security checks of keys.js /
 * conversation.js (mirrors OasisChatCryptoTests/CryptoCoreTests on iOS).
 */
class CryptoCoreTest {
    private val crypto = testCrypto
    private val future = Instant.ofEpochSecond(4_102_444_800) // 2100-01-01

    private fun ChatIdentity.secrets(): ByteArray = withPrivateKeys { encryption, signing -> encryption + signing }

    @Test
    fun locksAndUnlocksWithTheChosenCosts() {
        val identity = crypto.createIdentity()
        val locked = crypto.lockIdentity(identity, "Palme Regen Kompass Tee", KdfLimits.Minimum)
        assertEquals(LockedIdentity.Kdf("argon2id13", 1, 8192, locked.kdf.salt), locked.kdf)
        assertEquals(112, crypto.fromBase64(locked.ciphertext).size)
        val unlocked = crypto.unlockIdentity(locked, "Palme Regen Kompass Tee")
        assertArrayEquals(identity.secrets(), unlocked.secrets())
        assertEquals(KdfLimits(3, 268_435_456), KdfLimits.Moderate)
    }

    @Test
    fun refusesChangedMetadataAndOutOfRangeCosts() {
        val identity = crypto.createIdentity()
        val locked = crypto.lockIdentity(identity, "passphrase-123", KdfLimits.Minimum)
        val other = crypto.exportPublicKeys(crypto.createIdentity())

        val swappedKey = locked.copy(publicKeys = locked.publicKeys.copy(encryption = other.encryption))
        assertThrows(ChatCryptoException.WrongPassphrase::class.java) { crypto.unlockIdentity(swappedKey, "passphrase-123") }

        val raisedCost = locked.copy(kdf = locked.kdf.copy(memlimit = 16384))
        assertThrows(ChatCryptoException.WrongPassphrase::class.java) { crypto.unlockIdentity(raisedCost, "passphrase-123") }

        // SENSITIVE costs (1 GiB): refused before allocating.
        val huge = locked.copy(kdf = locked.kdf.copy(memlimit = 1_073_741_824))
        val error = assertThrows(ChatCryptoException.InvalidInput::class.java) { crypto.unlockIdentity(huge, "passphrase-123") }
        assertEquals("Key derivation parameters are out of the accepted range.", error.reason)
        assertThrows(ChatCryptoException::class.java) { crypto.lockIdentity(identity, "x", KdfLimits(4, 8192)) }
        val wrongAlgorithm = locked.copy(kdf = locked.kdf.copy(alg = "argon2i13"))
        assertThrows(ChatCryptoException::class.java) { crypto.unlockIdentity(wrongAlgorithm, "passphrase-123") }
        assertThrows(ChatCryptoException::class.java) { crypto.lockIdentity(identity, "", KdfLimits.Minimum) }
    }

    @Test
    fun wipedIdentitiesCannotSign() {
        val identity = crypto.createIdentity()
        identity.wipe()
        assertTrue(identity.isWiped)
        assertThrows(ChatCryptoException::class.java) { crypto.exportPublicKeys(identity) }
    }

    @Test
    fun sendsAndReadsMessagesWithinTheLimits() {
        val alice = crypto.createIdentity()
        val key = crypto.createConversationKey()
        val envelope = crypto.encryptMessage(key, "7", 1, "3", alice, "Hallo 🌴", future.plusMillis(900))
        assertEquals("2100-01-01T00:00:00Z", envelope.expiresAt)
        val message = crypto.decryptMessage(envelope, key, alice.signingPublicKey, expectedConversationId = "7")
        assertEquals("Hallo 🌴", message.plaintext)

        val fullLength = "a".repeat(ChatCrypto.MAX_MESSAGE_LENGTH)
        crypto.encryptMessage(key, "7", 1, "3", alice, fullLength, future)
        assertEquals(
            ChatCryptoException.MessageTooLong(4000),
            assertThrows(ChatCryptoException.MessageTooLong::class.java) {
                crypto.encryptMessage(key, "7", 1, "3", alice, fullLength + "a", future)
            }
        )
        // 1500 palm trees: 1500 code points but 6000 UTF-8 bytes – the byte limit applies.
        assertThrows(ChatCryptoException.MessageTooLong::class.java) {
            crypto.encryptMessage(key, "7", 1, "3", alice, "🌴".repeat(1500), future)
        }
        assertThrows(ChatCryptoException::class.java) {
            crypto.encryptMessage(key, "7", 1, "3", alice, "zu spät", Instant.ofEpochSecond(1000))
        }
    }

    @Test
    fun parsesExpiriesLikeTheBrowser() {
        assertEquals(1_792_324_800L, ChatEncoding.parseEnvelopeExpiry("2026-10-18T12:00:00Z"))
        assertEquals(1_792_324_800L, ChatEncoding.parseEnvelopeExpiry("2026-10-18T12:00:00.000000Z"))
        assertEquals(1_792_324_800L, ChatEncoding.parseEnvelopeExpiry("2026-10-18T14:00:00+02:00"))
        assertNull(ChatEncoding.parseEnvelopeExpiry(null))
        for (invalid in listOf(
            "2026-10-18T12:00:00.5Z",
            "2026-10-18 12:00:00Z",
            "2026-13-01T00:00:00Z",
            "1792324800",
            "2026-10-18T12:00:00"
        )) {
            assertThrows(invalid, ChatCryptoException::class.java) { ChatEncoding.parseEnvelopeExpiry(invalid) }
        }
    }

    @Test
    fun rejectsMalformedBase64() {
        for (invalid in listOf("", "a", "ab=", "ab+/", "a b", "ab==", "äb")) {
            assertThrows(invalid, ChatCryptoException::class.java) { crypto.fromBase64(invalid) }
        }
        assertThrows(ChatCryptoException::class.java) { crypto.fromBase64("AAAA", expectedLength = 2) }
    }

    @Test
    fun nativeBase64MatchesTheUrlSafeAlphabetWithoutPadding() {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        for (length in 1..70) {
            val bytes = crypto.randomBytes(length)
            val text = crypto.toBase64(bytes)
            assertEquals(encoder.encodeToString(bytes), text)
            assertArrayEquals(bytes, crypto.fromBase64(text))
        }
    }

    @Test
    fun refusesStrictlyInvalidUtf8() {
        assertNull(ChatEncoding.strictUtf8(byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals("a", ChatEncoding.strictUtf8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x61)))
        assertEquals("Grüße", ChatEncoding.strictUtf8("Grüße".toByteArray()))
    }

    @Test
    fun grantsOnlyOpenForTheirRecipient() {
        val owner = crypto.createIdentity()
        val member = crypto.createIdentity()
        val key = crypto.createConversationKey()
        val grant = crypto.createKeyGrant(key, "9", 2, "5", member.encryptionPublicKey, "4", owner)
        assertArrayEquals(key, crypto.openKeyGrant(grant, member, owner.signingPublicKey).conversationKey)
        assertThrows(ChatCryptoException.InvalidKeyGrant::class.java) { crypto.openKeyGrant(grant, owner, owner.signingPublicKey) }
        assertThrows(ChatCryptoException.InvalidKeyGrant::class.java) { crypto.openKeyGrant(grant, member, member.signingPublicKey) }
    }

    @Test
    fun deviceLinkRoundTripAndCheckNumberFormat() {
        val phone = crypto.createIdentity()
        val pair = crypto.createLinkKeyPair()
        val approval = crypto.approveLink(phone, pair.publicKeyBase64)
        assertEquals(1L, approval.lockedIdentity.kdf.opslimit)
        assertEquals(8192L, approval.lockedIdentity.kdf.memlimit)
        val laptop = crypto.openLinkApproval(approval.lockedIdentity, approval.sealedKey, pair)
        assertArrayEquals(phone.signingPublicKey, laptop.signingPublicKey)
        assertTrue(Regex("""\d{4} \d{4}""").matches(crypto.checkNumber(pair.publicKeyBase64)))
    }
}
