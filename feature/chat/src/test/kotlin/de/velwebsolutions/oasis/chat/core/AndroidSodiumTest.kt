package de.velwebsolutions.oasis.chat.core

import com.goterl.lazysodium.SodiumJava
import de.velwebsolutions.oasis.chat.testCrypto
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto
import de.velwebsolutions.oasis.chatcrypto.KdfLimits
import de.velwebsolutions.oasis.chatcrypto.approveLink
import de.velwebsolutions.oasis.chatcrypto.checkNumber
import de.velwebsolutions.oasis.chatcrypto.createKeyGrant
import de.velwebsolutions.oasis.chatcrypto.createLinkKeyPair
import de.velwebsolutions.oasis.chatcrypto.decryptMessage
import de.velwebsolutions.oasis.chatcrypto.encryptMessage
import de.velwebsolutions.oasis.chatcrypto.openKeyGrant
import de.velwebsolutions.oasis.chatcrypto.openLinkApproval
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's libsodium binding, run on the JVM with lazysodium-java (same native class and calls as lazysodium-android):
 * every primitive, and objects it makes open with the test fixture's binding and the other way round.
 */
class AndroidSodiumTest {
    private val sodium = AndroidSodium(SodiumJava())
    private val crypto = ChatCrypto(sodium)
    private val other = testCrypto

    @Test
    fun encodesBase64AndComparesInConstantTime() {
        val bytes = byteArrayOf(-1, 0, 1, 2, 3)
        assertEquals(other.toBase64(bytes), sodium.toBase64(bytes))
        assertArrayEquals(bytes, sodium.fromBase64(sodium.toBase64(bytes)))
        assertNull(sodium.fromBase64("ab=="))
        assertTrue(sodium.memcmp(bytes, bytes.copyOf()))
        assertFalse(sodium.memcmp(bytes, ByteArray(5)))
        assertEquals(16, sodium.randomBytes(16).size)
    }

    @Test
    fun locksIdentitiesThatTheOtherBindingOpens() {
        val identity = crypto.createIdentity()
        val locked = crypto.lockIdentity(identity, "Palme Regen Kompass Tee", KdfLimits.Minimum)
        assertArrayEquals(identity.signingPublicKey, other.unlockIdentity(locked, "Palme Regen Kompass Tee").signingPublicKey)
        val back = other.lockIdentity(identity, "Palme Regen Kompass Tee", KdfLimits.Minimum)
        assertArrayEquals(identity.encryptionPublicKey, crypto.unlockIdentity(back, "Palme Regen Kompass Tee").encryptionPublicKey)
        assertEquals(other.checkNumber("abc"), crypto.checkNumber("abc"))
        assertEquals(
            other.fingerprint(identity.signingPublicKey, ByteArray(32)),
            crypto.fingerprint(identity.signingPublicKey, ByteArray(32))
        )
        crypto.verifyPublicKeys(crypto.exportPublicKeys(identity))
    }

    @Test
    fun sealsGrantsMessagesAndLinks() {
        val owner = crypto.createIdentity()
        val member = other.createIdentity()
        val key = crypto.randomBytes(32)
        val grant = crypto.createKeyGrant(key, "9", 1, "5", member.encryptionPublicKey, "4", owner)
        assertArrayEquals(key, other.openKeyGrant(grant, member, owner.signingPublicKey).conversationKey)
        val now = Instant.ofEpochSecond(1_800_000_000)
        val envelope = crypto.encryptMessage(key, "9", 1, "4", owner, "Hallo 🌴", now.plusSeconds(60), now)
        assertEquals("Hallo 🌴", other.decryptMessage(envelope, key, owner.signingPublicKey, now).plaintext)
        val back = other.encryptMessage(key, "9", 1, "5", member, "Zurück", now.plusSeconds(60), now)
        assertEquals("Zurück", crypto.decryptMessage(back, key, member.signingPublicKey, now).plaintext)

        val pair = crypto.createLinkKeyPair()
        val approval = other.approveLink(member, pair.publicKeyBase64)
        assertArrayEquals(
            member.signingPublicKey,
            crypto.openLinkApproval(approval.lockedIdentity, approval.sealedKey, pair).signingPublicKey
        )
    }

    @Test
    fun theOpenFunctionsAnswerNullInsteadOfThrowing() {
        assertNull(sodium.aeadDecrypt(ByteArray(15), ByteArray(0), ByteArray(24), ByteArray(32)))
        assertNull(sodium.aeadDecrypt(ByteArray(32), ByteArray(0), ByteArray(24), ByteArray(32)))
        assertNull(sodium.boxSealOpen(ByteArray(47), ByteArray(32), ByteArray(32)))
        val pair = sodium.boxKeyPair()
        assertNull(sodium.boxSealOpen(ByteArray(80), pair.publicKey, pair.secretKey))
        assertNull(sodium.pwhashArgon2id(32, byteArrayOf(1), ByteArray(16), 0, 8192))
        assertFalse(sodium.signVerifyDetached(ByteArray(64), byteArrayOf(1), sodium.signKeyPair().publicKey))
    }
}
