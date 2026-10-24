package de.velwebsolutions.oasis.chatcrypto

import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defensive checks of the crypto core: argument validation, malformed server objects and answers libsodium only
 * gives when something is badly wrong (simulated with a [Sodium] that misbehaves on purpose).
 */
class CryptoEdgeCasesTest {
    private val crypto = testCrypto
    private val future = Instant.ofEpochSecond(4_102_444_800) // 2100-01-01

    /** A crypto core whose libsodium answers [change] differently; everything else is the real binding. */
    private fun cryptoWith(change: (Sodium) -> Sodium) = ChatCrypto(change(JvmSodium()))

    @Test
    fun canonicalFieldsAreOnlyBytesOrText() {
        assertThrows(IllegalArgumentException::class.java) { ChatEncoding.fields(1) }
        ChatEncoding.wipe(null)
    }

    @Test
    fun validatesIdsAndKeyVersions() {
        assertThrows(ChatCryptoException.InvalidInput::class.java) { ChatEncoding.normaliseId("", "id") }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { ChatEncoding.normaliseId("1".repeat(256), "id") }
        assertEquals("1".repeat(255), ChatEncoding.normaliseId("1".repeat(255), "id"))
        assertThrows(ChatCryptoException.InvalidInput::class.java) { ChatEncoding.normaliseKeyVersion(-1) }
    }

    @Test
    fun ordersKeysByteByByteUnsigned() {
        assertTrue(ChatEncoding.precedesOrEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(ChatEncoding.precedesOrEquals(byteArrayOf(1, 3), byteArrayOf(1, 2)))
        assertFalse(ChatEncoding.precedesOrEquals(byteArrayOf(0xFF.toByte()), byteArrayOf(1)))
        assertTrue(ChatEncoding.precedesOrEquals(byteArrayOf(1), byteArrayOf(1, 0)))
        assertFalse(ChatEncoding.precedesOrEquals(byteArrayOf(1, 0), byteArrayOf(1)))
        assertTrue(ChatEncoding.precedesOrEquals(byteArrayOf(1), byteArrayOf(1)))
    }

    @Test
    fun parsesEveryExpiryEdgeLikeDateParse() {
        assertEquals(951_782_400L, ChatEncoding.parseEnvelopeExpiry("2000-02-29T00:00:00Z"))
        assertEquals(1_709_164_800L, ChatEncoding.parseEnvelopeExpiry("2024-02-29T00:00:00Z"))
        assertEquals(1_792_368_000L, ChatEncoding.parseEnvelopeExpiry("2026-10-18T24:00:00Z"))
        assertEquals(1_792_332_000L, ChatEncoding.parseEnvelopeExpiry("2026-10-18T12:00:00-02:00"))
        assertEquals(-62_167_219_200L, ChatEncoding.parseEnvelopeExpiry("0000-01-01T00:00:00Z"))
        assertEquals(-62_164_627_200L, ChatEncoding.parseEnvelopeExpiry("0000-01-31T00:00:00Z"))
        for (invalid in listOf(
            "2026-00-01T00:00:00Z",
            "2026-02-29T00:00:00Z",
            "1900-02-29T00:00:00Z",
            "2026-04-00T00:00:00Z",
            "2026-04-31T00:00:00Z",
            "2026-10-18T25:00:00Z",
            "2026-10-18T24:01:00Z",
            "2026-10-18T24:00:01Z",
            "2026-10-18T12:60:00Z",
            "2026-10-18T12:00:60Z",
            "2026-10-18T12:00:00+24:00",
            "2026-10-18T12:00:00+02:60"
        )) {
            assertThrows(invalid, ChatCryptoException.InvalidInput::class.java) { ChatEncoding.parseEnvelopeExpiry(invalid) }
        }
    }

    @Test
    fun identitiesNeedKeysOfTheRightSize() {
        for ((encryptionPublic, encryptionPrivate, signingPublic, signingPrivate) in listOf(
            listOf(31, 32, 32, 64),
            listOf(32, 31, 32, 64),
            listOf(32, 32, 31, 64),
            listOf(32, 32, 32, 63)
        )) {
            assertThrows(ChatCryptoException.InvalidInput::class.java) {
                ChatIdentity(
                    ByteArray(encryptionPublic),
                    ByteArray(encryptionPrivate) { 1 },
                    ByteArray(signingPublic),
                    ByteArray(signingPrivate) { 1 }
                )
            }
        }
        val identity = crypto.createIdentity()
        identity.wipe()
        assertThrows(ChatCryptoException.InvalidInput::class.java) { identity.withPrivateKeys { _, _ -> Unit } }
    }

    @Test
    fun refusesMalformedLockedIdentities() {
        val identity = crypto.createIdentity()
        val locked = crypto.lockIdentity(identity, "Palme Regen Kompass Tee", KdfLimits.Minimum)
        assertThrows(ChatCryptoException.InvalidInput::class.java) { crypto.unlockIdentity(locked.copy(version = 2), "x") }
        for (kdf in listOf(
            locked.kdf.copy(opslimit = 0),
            locked.kdf.copy(memlimit = 4096),
            locked.kdf.copy(opslimit = 4)
        )) {
            assertThrows(ChatCryptoException.InvalidInput::class.java) { crypto.unlockIdentity(locked.copy(kdf = kdf), "x") }
        }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { crypto.fromBase64(null) }
    }

    @Test
    fun refusesKeysThatDoNotBelongToTheirPublicKeys() {
        val first = crypto.createIdentity()
        val second = crypto.createIdentity()
        fun privateKeys(identity: ChatIdentity) =
            identity.withPrivateKeys { encryption, signing -> encryption.copyOf() to signing.copyOf() }
        val (firstEncryption, firstSigning) = privateKeys(first)
        val (_, secondSigning) = privateKeys(second)
        // Encrypted correctly for its public keys, but the private keys are someone else's.
        val wrongSigning = ChatIdentity(first.encryptionPublicKey, firstEncryption, first.signingPublicKey, secondSigning)
        val lockedSigning = crypto.lockIdentity(wrongSigning, "Palme Regen Kompass Tee", KdfLimits.Minimum)
        assertThrows(ChatCryptoException.WrongPassphrase::class.java) { crypto.unlockIdentity(lockedSigning, "Palme Regen Kompass Tee") }
        val wrongEncryption = ChatIdentity(second.encryptionPublicKey, firstEncryption, first.signingPublicKey, firstSigning)
        val lockedEncryption = crypto.lockIdentity(wrongEncryption, "Palme Regen Kompass Tee", KdfLimits.Minimum)
        assertThrows(ChatCryptoException.WrongPassphrase::class.java) {
            crypto.unlockIdentity(lockedEncryption, "Palme Regen Kompass Tee")
        }
    }

    @Test
    fun failsWhenLibsodiumRefusesOrAnswersOddly() {
        val identity = crypto.createIdentity()
        val noKdf = cryptoWith { base ->
            object : Sodium by base {
                override fun pwhashArgon2id(length: Int, password: ByteArray, salt: ByteArray, opslimit: Long, memlimit: Long): ByteArray? =
                    null
            }
        }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { noKdf.lockIdentity(identity, "x", KdfLimits.Minimum) }

        val locked = crypto.lockIdentity(identity, "Palme Regen Kompass Tee", KdfLimits.Minimum)
        val shortSecret = cryptoWith { base ->
            object : Sodium by base {
                override fun aeadDecrypt(ciphertext: ByteArray, associatedData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray =
                    ByteArray(95)
            }
        }
        assertThrows(ChatCryptoException.WrongPassphrase::class.java) { shortSecret.unlockIdentity(locked, "Palme Regen Kompass Tee") }

        val member = crypto.createIdentity()
        val grant = crypto.createKeyGrant(crypto.createConversationKey(), "9", 1, "5", member.encryptionPublicKey, "4", identity)
        val shortKey = cryptoWith { base ->
            object : Sodium by base {
                override fun boxSealOpen(sealed: ByteArray, publicKey: ByteArray, secretKey: ByteArray): ByteArray = ByteArray(31)
            }
        }
        assertThrows(ChatCryptoException.InvalidKeyGrant::class.java) { shortKey.unwrapKey(grant.wrappedKey, member) }
    }

    @Test
    fun checksPublicKeysAndSafetyNumberInputs() {
        val bundle = crypto.exportPublicKeys(crypto.createIdentity())
        assertThrows(ChatCryptoException.InvalidPublicKeys::class.java) { crypto.verifyPublicKeys(null) }
        assertThrows(ChatCryptoException.InvalidPublicKeys::class.java) {
            crypto.verifyPublicKeys(bundle.copy(signing = bundle.encryption))
        }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { crypto.fingerprint(ByteArray(31), ByteArray(32)) }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { crypto.fingerprint(ByteArray(32), ByteArray(31)) }
        val low = ByteArray(32)
        val high = ByteArray(32) { 1 }
        assertEquals(crypto.fingerprint(low, high), crypto.fingerprint(high, low))
        assertFalse(crypto.constantTimeEqual(ByteArray(2), ByteArray(3)))
        assertFalse(crypto.verifyDetached(ByteArray(63), ByteArray(1), ByteArray(32)))
        assertFalse(crypto.verifyDetached(ByteArray(64), ByteArray(1), ByteArray(31)))
        assertNull(crypto.aeadDecrypt(ByteArray(15), ByteArray(0), ByteArray(24), ByteArray(32)))
        assertNull(crypto.aeadDecrypt(ByteArray(16), ByteArray(0), ByteArray(23), ByteArray(32)))
        assertNull(crypto.aeadDecrypt(ByteArray(16), ByteArray(0), ByteArray(24), ByteArray(31)))
    }

    @Test
    fun verifiedKeysCompareByContent() {
        val keys = VerifiedPublicKeys(byteArrayOf(1), byteArrayOf(2))
        assertEquals(VerifiedPublicKeys(byteArrayOf(1), byteArrayOf(2)), keys)
        assertEquals(VerifiedPublicKeys(byteArrayOf(1), byteArrayOf(2)).hashCode(), keys.hashCode())
        assertNotEquals(VerifiedPublicKeys(byteArrayOf(9), byteArrayOf(2)), keys)
        assertNotEquals(VerifiedPublicKeys(byteArrayOf(1), byteArrayOf(9)), keys)
        assertNotEquals(keys, "keys")
    }

    @Test
    fun grantsRefuseBadKeysAndVersions() {
        val owner = crypto.createIdentity()
        val member = crypto.createIdentity()
        val key = crypto.createConversationKey()
        val grant = crypto.createKeyGrant(key, "9", 2, "5", member.encryptionPublicKey, "4", owner)
        assertThrows(ChatCryptoException.InvalidKeyGrant::class.java) { crypto.openKeyGrant(grant, member, ByteArray(31)) }
        assertThrows(ChatCryptoException.InvalidKeyGrant::class.java) {
            crypto.openKeyGrant(grant.copy(v = 2), member, owner.signingPublicKey)
        }
        assertThrows(ChatCryptoException.InvalidKeyGrant::class.java) { crypto.unwrapKey("kein-base64!", member) }
        assertThrows(ChatCryptoException.InvalidInput::class.java) {
            crypto.createKeyGrant(ByteArray(31), "9", 2, "5", member.encryptionPublicKey, "4", owner)
        }
        assertThrows(ChatCryptoException.InvalidInput::class.java) {
            crypto.createKeyGrant(key, "9", 2, "5", ByteArray(31), "4", owner)
        }
        assertNull(crypto.sealOpen(ByteArray(47), member.encryptionPublicKey, ByteArray(32)))
        assertThrows(ChatCryptoException.InvalidKeyGrant::class.java) { crypto.unwrapKey(grant.wrappedKey, owner) }
    }

    @Test
    fun messagesRefuseBadKeysVersionsAndPayloads() {
        val alice = crypto.createIdentity()
        val key = crypto.createConversationKey()
        val now = Instant.ofEpochSecond(1_800_000_000)
        assertThrows(ChatCryptoException.InvalidInput::class.java) {
            crypto.encryptMessage(ByteArray(31), "7", 1, "3", alice, "Hallo", future)
        }
        val envelope = crypto.encryptMessage(key, "7", 1, "3", alice, "Hallo", future, now)
        assertThrows(ChatCryptoException.InvalidInput::class.java) {
            crypto.decryptMessage(envelope, ByteArray(31), alice.signingPublicKey)
        }
        assertThrows(ChatCryptoException.InvalidInput::class.java) { crypto.decryptMessage(envelope, key, ByteArray(31)) }
        assertThrows(ChatCryptoException.TamperedMessage::class.java) {
            crypto.decryptMessage(envelope.copy(v = 2), key, alice.signingPublicKey)
        }
        assertThrows(ChatCryptoException.TamperedMessage::class.java) {
            crypto.decryptMessage(envelope, key, alice.signingPublicKey, now, expectedConversationId = "8")
        }
        assertThrows(ChatCryptoException.ExpiredMessage::class.java) {
            crypto.decryptMessage(envelope, key, alice.signingPublicKey, Instant.ofEpochSecond(4_102_444_800))
        }
        assertThrows(ChatCryptoException.TamperedMessage::class.java) {
            crypto.decryptMessage(envelope, crypto.createConversationKey(), alice.signingPublicKey, now)
        }
        assertEquals("Hallo", crypto.decryptMessage(envelope, key, alice.signingPublicKey, now).plaintext)
    }

    @Test
    fun readsSignedMessagesWithoutExpiryAndRefusesInvalidUtf8() {
        val alice = crypto.createIdentity()
        val key = crypto.createConversationKey()
        fun sealed(plaintext: ByteArray): MessageEnvelope {
            val associated = messageAssociatedData(1, "7", 1, "3", null)
            val nonce = crypto.randomBytes(24)
            val ciphertext = crypto.sodium.aeadEncrypt(plaintext, associated, nonce, key)
            val signed = ChatEncoding.canonicalBytes(ChatCrypto.MESSAGE_SIGNATURE_LABEL, ChatEncoding.fields(nonce, ciphertext, associated))
            val signature = alice.withPrivateKeys { _, signing -> crypto.sodium.signDetached(signed, signing) }
            return MessageEnvelope(1, "7", 1, "3", crypto.toBase64(nonce), crypto.toBase64(ciphertext), crypto.toBase64(signature))
        }
        val plain = crypto.decryptMessage(sealed("Ohne Ablauf".toByteArray()), key, alice.signingPublicKey)
        assertEquals("Ohne Ablauf", plain.plaintext)
        assertNull(plain.expiresAt)
        assertThrows(ChatCryptoException.TamperedMessage::class.java) {
            crypto.decryptMessage(sealed(byteArrayOf(0xC3.toByte(), 0x28)), key, alice.signingPublicKey)
        }
    }

    @Test
    fun deviceCopiesNeedTheirSecretAndTheRightKeyPair() {
        val identity = crypto.createIdentity()
        assertThrows(ChatCryptoException.InvalidInput::class.java) { crypto.lockForDevice(identity, ByteArray(31)) }
        val secret = crypto.randomBytes(32)
        val copy = crypto.lockForDevice(identity, secret)
        assertArrayEquals(identity.signingPublicKey, crypto.unlockWithDeviceSecret(copy, secret).signingPublicKey)

        val asking = crypto.createLinkKeyPair()
        val approval = crypto.approveLink(identity, asking.publicKeyBase64)
        val stranger = crypto.createLinkKeyPair()
        assertThrows(ChatCryptoException.WrongPassphrase::class.java) {
            crypto.openLinkApproval(approval.lockedIdentity, approval.sealedKey, stranger)
        }
        assertTrue(stranger.privateKey.all { it == 0.toByte() })
    }

    @Test
    fun theJvmBindingRefusesShortInputs() {
        val sodium = JvmSodium()
        assertNull(sodium.aeadDecrypt(ByteArray(15), ByteArray(0), ByteArray(24), ByteArray(32)))
        assertNull(sodium.boxSealOpen(ByteArray(47), ByteArray(32), ByteArray(32)))
        assertNull(sodium.pwhashArgon2id(32, byteArrayOf(1), ByteArray(16), 0, 8192))
    }
}
