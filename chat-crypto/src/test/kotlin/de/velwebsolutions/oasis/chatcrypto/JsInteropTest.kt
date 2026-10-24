package de.velwebsolutions.oasis.chatcrypto

import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors made by the browser crypto core (Laravel repo: tests/Support/generate-swift-chat-fixture.mjs, the same file
 * the iOS port reads). The Kotlin port must open, verify and decrypt every one of them byte for byte.
 */
@Serializable
data class JsVectors(
    val passphrase: String,
    val owner: Person,
    val member: Person,
    @SerialName("safety_number") val safetyNumber: String,
    @SerialName("conversation_key") val conversationKey: String,
    val grants: List<KeyGrant>,
    val messages: List<Message>,
    @SerialName("device_link") val deviceLink: DeviceLink,
    @SerialName("device_unlock") val deviceUnlock: DeviceUnlock,
    val canonical: List<Canonical>,
    val base64: List<Base64Vector>,
    @SerialName("check_numbers") val checkNumbers: List<CheckNumber>
) {
    @Serializable
    data class Person(
        val id: String,
        @SerialName("public_keys") val publicKeys: PublicKeyBundle,
        @SerialName("locked_min") val lockedMin: LockedIdentity,
        @SerialName("locked_moderate") val lockedModerate: LockedIdentity? = null
    )

    @Serializable
    data class Message(
        val plaintext: String,
        @SerialName("key_version") val keyVersion: Int,
        val expired: Boolean? = null,
        val envelope: MessageEnvelope
    )

    @Serializable
    data class DeviceLink(
        @SerialName("public_key") val publicKey: String,
        @SerialName("private_key") val privateKey: String,
        @SerialName("check_number") val checkNumber: String,
        @SerialName("locked_identity") val lockedIdentity: LockedIdentity,
        @SerialName("sealed_key") val sealedKey: String
    )

    @Serializable
    data class DeviceUnlock(val secret: String, @SerialName("locked_identity") val lockedIdentity: LockedIdentity)

    @Serializable
    data class Canonical(val label: String, val fields: List<String>, val bytes: String)

    @Serializable
    data class Base64Vector(val hex: String, val base64: String)

    @Serializable
    data class CheckNumber(@SerialName("public_key") val publicKey: String, val check: String)

    companion object {
        fun load(): JsVectors {
            val text = checkNotNull(JsVectors::class.java.getResource("/fixtures/js-vectors.json")).readText()
            return Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), text)
        }
    }
}

fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** A crypto core on lazysodium-java, shared by the tests (libsodium is initialised once). */
val testCrypto: ChatCrypto by lazy { ChatCrypto(JvmSodium()) }

class JsInteropTest {
    private val vectors = JsVectors.load()
    private val crypto = testCrypto

    private fun ChatIdentity.secrets(): ByteArray = withPrivateKeys { encryption, signing -> encryption + signing }

    @Test
    fun encodesBase64AndCanonicalBytesLikeTheBrowser() {
        for (vector in vectors.base64) {
            val bytes = hexBytes(vector.hex)
            assertEquals(vector.base64, crypto.toBase64(bytes))
            if (bytes.isNotEmpty()) assertArrayEquals(bytes, crypto.fromBase64(vector.base64))
        }
        for (vector in vectors.canonical) {
            val bytes = ChatEncoding.canonicalBytes(vector.label, vector.fields.map { ChatEncoding.Field.Text(it) })
            assertEquals(vector.bytes, crypto.toBase64(bytes))
        }
    }

    @Test
    fun unlocksIdentitiesLockedInTheBrowserWithBothCostLevels() {
        val owner = crypto.unlockIdentity(vectors.owner.lockedMin, vectors.passphrase)
        assertEquals(vectors.owner.publicKeys.signing, crypto.exportPublicKeys(owner).signing)
        // The binding signature is deterministic (Ed25519), so the whole bundle must be identical.
        assertEquals(vectors.owner.publicKeys, crypto.exportPublicKeys(owner))

        val moderate = checkNotNull(vectors.owner.lockedModerate)
        assertEquals(3L, moderate.kdf.opslimit)
        assertEquals(268_435_456L, moderate.kdf.memlimit)
        val again = crypto.unlockIdentity(moderate, vectors.passphrase)
        assertArrayEquals(owner.secrets(), again.secrets())

        assertThrows(ChatCryptoException.WrongPassphrase::class.java) {
            crypto.unlockIdentity(vectors.owner.lockedMin, "falsche Passphrase")
        }
    }

    @Test
    fun normalisesThePassphraseToNfcLikeTheBrowser() {
        // "ü" typed as u + combining diaeresis must derive the same key as the precomposed form.
        val decomposed = Normalizer.normalize(vectors.passphrase, Normalizer.Form.NFD)
        assertNotEquals(vectors.passphrase, decomposed)
        crypto.unlockIdentity(vectors.member.lockedMin, decomposed)
    }

    @Test
    fun verifiesBundlesAndComputesTheSameSafetyNumber() {
        val owner = crypto.verifyPublicKeys(vectors.owner.publicKeys)
        val member = crypto.verifyPublicKeys(vectors.member.publicKeys)
        assertEquals(vectors.safetyNumber, crypto.fingerprint(owner.signing, member.signing))
        assertEquals(vectors.safetyNumber, crypto.fingerprint(member.signing, owner.signing))

        val swapped = vectors.owner.publicKeys.copy(encryption = vectors.member.publicKeys.encryption)
        assertThrows(ChatCryptoException.InvalidPublicKeys::class.java) { crypto.verifyPublicKeys(swapped) }
    }

    @Test
    fun opensBrowserGrantsAndDecryptsBrowserMessages() {
        val member = crypto.unlockIdentity(vectors.member.lockedMin, vectors.passphrase)
        val owner = crypto.verifyPublicKeys(vectors.owner.publicKeys)
        val keys = mutableMapOf<Int, ByteArray>()
        for (grant in vectors.grants) {
            val opened = crypto.openKeyGrant(grant, member, owner.signing)
            assertEquals("4242", opened.conversationId)
            assertEquals("12", opened.recipientId)
            assertEquals("11", opened.granterId)
            keys[opened.keyVersion] = opened.conversationKey
        }
        assertEquals(vectors.conversationKey, crypto.toBase64(checkNotNull(keys[2])))

        for (message in vectors.messages) {
            val key = checkNotNull(keys[message.keyVersion])
            if (message.expired == true) {
                assertThrows(ChatCryptoException.ExpiredMessage::class.java) {
                    crypto.decryptMessage(message.envelope, key, owner.signing)
                }
                continue
            }
            val decrypted = crypto.decryptMessage(message.envelope, key, owner.signing, expectedConversationId = "4242")
            assertEquals(message.plaintext, decrypted.plaintext)
            assertEquals("11", decrypted.senderId)

            // The server may re-format the expiry (Laravel's ".000000Z") as long as the instant stays the same.
            val reformatted = message.envelope.copy(expiresAt = message.envelope.expiresAt!!.replace("Z", ".000000Z"))
            assertEquals(message.plaintext, crypto.decryptMessage(reformatted, key, owner.signing).plaintext)
        }
    }

    @Test
    fun rejectsBrowserMessagesThatWereTamperedWith() {
        val member = crypto.unlockIdentity(vectors.member.lockedMin, vectors.passphrase)
        val owner = crypto.verifyPublicKeys(vectors.owner.publicKeys)
        val key = crypto.openKeyGrant(vectors.grants[0], member, owner.signing).conversationKey
        val original = vectors.messages[0].envelope
        val memberKeys = crypto.verifyPublicKeys(vectors.member.publicKeys)

        val changed = listOf(
            original.copy(conversationId = "4243"),
            original.copy(senderId = "12"),
            original.copy(expiresAt = "2099-01-01T00:00:01Z"),
            original.copy(keyVersion = 3)
        )
        for (envelope in changed) {
            assertThrows(ChatCryptoException.TamperedMessage::class.java) { crypto.decryptMessage(envelope, key, owner.signing) }
        }
        // Authentic, but replayed into another conversation than the one shown.
        assertThrows(ChatCryptoException.TamperedMessage::class.java) {
            crypto.decryptMessage(original, key, owner.signing, expectedConversationId = "1")
        }
        // Signed by someone else than the claimed sender.
        assertThrows(ChatCryptoException.TamperedMessage::class.java) { crypto.decryptMessage(original, key, memberKeys.signing) }
        // A grant reassigned to another version fails its signature.
        val moved = vectors.grants[0].copy(keyVersion = 7)
        assertThrows(ChatCryptoException.InvalidKeyGrant::class.java) { crypto.openKeyGrant(moved, member, owner.signing) }
    }

    @Test
    fun opensBrowserDeviceLinkApprovalsAndBiometricCopies() {
        assertEquals(vectors.deviceLink.checkNumber, crypto.checkNumber(vectors.deviceLink.publicKey))
        for (vector in vectors.checkNumbers) assertEquals(vector.check, crypto.checkNumber(vector.publicKey))

        val pair = LinkKeyPair(crypto.fromBase64(vectors.deviceLink.publicKey), crypto.fromBase64(vectors.deviceLink.privateKey), crypto)
        val linked = crypto.openLinkApproval(vectors.deviceLink.lockedIdentity, vectors.deviceLink.sealedKey, pair)
        assertEquals(vectors.member.publicKeys, crypto.exportPublicKeys(linked))
        assertTrue("the one-time key is wiped", pair.privateKey.all { it == 0.toByte() })

        val device = crypto.unlockWithDeviceSecret(vectors.deviceUnlock.lockedIdentity, crypto.fromBase64(vectors.deviceUnlock.secret))
        assertEquals(vectors.member.publicKeys, crypto.exportPublicKeys(device))
    }

    @Test
    fun decodesNumericIdsLikeStrings() {
        val json = Json { ignoreUnknownKeys = true }
        val envelope = json.encodeToString(JsonObjectSerializer, vectors.messages[0].envelope.toJsonObject())
            .replace("\"conversation_id\":\"4242\"", "\"conversation_id\":4242")
        assertEquals("4242", json.decodeFromString(MessageEnvelope.serializer(), envelope).conversationId)
    }
}

private val JsonObjectSerializer = kotlinx.serialization.json.JsonObject.serializer()
