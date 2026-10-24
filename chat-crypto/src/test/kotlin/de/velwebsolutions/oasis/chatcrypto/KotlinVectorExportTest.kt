package de.velwebsolutions.oasis.chatcrypto

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The opposite direction: vectors made by the Kotlin port, in the shape of the iOS export (SwiftVectorExport), so the
 * browser core in the Laravel repo can verify them (tests/js/chat/swift-interop.test.mjs reads that shape). The test also
 * opens every one of its own outputs again with the Kotlin core.
 *
 * Write the file: `OASIS_KOTLIN_VECTORS_OUT=/path/chat-kotlin-vectors.json ./gradlew :chat-crypto:test --tests '*VectorExport*'`.
 * TEST-ONLY keys and passphrases.
 */
class KotlinVectorExportTest {
    private val crypto = testCrypto
    private val json = Json { prettyPrint = true }

    private inline fun <reified T> element(value: T): JsonElement = json.encodeToJsonElement(kotlinx.serialization.serializer<T>(), value)

    @Test
    fun buildsVectorsForTheBrowserThatOpenAgainInKotlin() {
        val passphrase = "Palme Regen Kompass Tee – aus Kotlin ✓"
        val owner = crypto.createIdentity()
        val member = crypto.createIdentity()
        val key = crypto.createConversationKey()
        val plaintext = "Hallo aus der App 🌴 – Grüße, Ünïcödé & \"Zeichen\"\nzweite Zeile"
        val envelope = crypto.encryptMessage(key, "4242", 3, "11", owner, plaintext, Instant.ofEpochSecond(4_102_444_800))
        val grant = crypto.createKeyGrant(key, "4242", 3, "12", member.encryptionPublicKey, "11", owner)
        val linkPair = crypto.createLinkKeyPair()
        val linkPublicKey = linkPair.publicKeyBase64
        val linkPrivateKey = crypto.toBase64(linkPair.privateKey)
        val approval = crypto.approveLink(member, linkPublicKey)
        val deviceSecret = crypto.randomBytes(32)
        val ownerMin = crypto.lockIdentity(owner, passphrase, KdfLimits.Minimum)
        val ownerModerate = crypto.lockIdentity(owner, passphrase)
        val memberMin = crypto.lockIdentity(member, passphrase, KdfLimits.Minimum)
        val deviceCopy = crypto.lockForDevice(member, deviceSecret)
        val canonicalFields = listOf("ab", "c", "", "ü🌴")

        val vectors = buildJsonObject {
            put("passphrase", passphrase)
            put(
                "owner",
                buildJsonObject {
                    put("public_keys", element(crypto.exportPublicKeys(owner)))
                    put("locked_min", element(ownerMin))
                    put("locked_moderate", element(ownerModerate))
                }
            )
            put(
                "member",
                buildJsonObject {
                    put("public_keys", element(crypto.exportPublicKeys(member)))
                    put("locked_min", element(memberMin))
                }
            )
            put("safety_number", crypto.fingerprint(owner.signingPublicKey, member.signingPublicKey))
            put("conversation_key", crypto.toBase64(key))
            put("grant", element(grant))
            put(
                "message",
                buildJsonObject {
                    put("plaintext", plaintext)
                    put("envelope", envelope.toJsonObject())
                }
            )
            put(
                "device_link",
                buildJsonObject {
                    put("public_key", linkPublicKey)
                    put("private_key", linkPrivateKey)
                    put("check_number", crypto.checkNumber(linkPublicKey))
                    put("locked_identity", element(approval.lockedIdentity))
                    put("sealed_key", approval.sealedKey)
                }
            )
            put(
                "device_unlock",
                buildJsonObject {
                    put("secret", crypto.toBase64(deviceSecret))
                    put("locked_identity", element(deviceCopy))
                }
            )
            put(
                "canonical",
                buildJsonObject {
                    put("label", "oasis-chat/test/v1")
                    put("fields", element(canonicalFields))
                    put(
                        "bytes",
                        crypto.toBase64(
                            ChatEncoding.canonicalBytes(
                                "oasis-chat/test/v1",
                                canonicalFields.map {
                                    ChatEncoding.Field.Text(it)
                                }
                            )
                        )
                    )
                }
            )
        }

        // Our own outputs, read back from JSON, open again.
        val decodedOwner = json.decodeFromJsonElement(LockedIdentity.serializer(), element(ownerModerate))
        assertEquals(crypto.exportPublicKeys(owner), crypto.exportPublicKeys(crypto.unlockIdentity(decodedOwner, passphrase)))
        val readMember = crypto.unlockIdentity(memberMin, passphrase)
        val opened = crypto.openKeyGrant(
            json.decodeFromJsonElement(KeyGrant.serializer(), element(grant)),
            readMember,
            owner.signingPublicKey
        )
        assertArrayEquals(key, opened.conversationKey)
        val decodedEnvelope = json.decodeFromJsonElement(MessageEnvelope.serializer(), envelope.toJsonObject())
        assertEquals(
            plaintext,
            crypto.decryptMessage(
                decodedEnvelope,
                opened.conversationKey,
                owner.signingPublicKey,
                expectedConversationId = "4242"
            ).plaintext
        )
        val linkCopy = LinkKeyPair(crypto.fromBase64(linkPublicKey), crypto.fromBase64(linkPrivateKey), crypto)
        assertEquals(
            crypto.exportPublicKeys(member),
            crypto.exportPublicKeys(crypto.openLinkApproval(approval.lockedIdentity, approval.sealedKey, linkCopy))
        )
        assertEquals(crypto.exportPublicKeys(member), crypto.exportPublicKeys(crypto.unlockWithDeviceSecret(deviceCopy, deviceSecret)))
        crypto.verifyPublicKeys(crypto.exportPublicKeys(owner))

        System.getenv("OASIS_KOTLIN_VECTORS_OUT")?.let { File(it).writeText(json.encodeToString(JsonElement.serializer(), vectors)) }
    }
}
