package de.velwebsolutions.oasis.chatcrypto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The wire format of the crypto objects: required fields, optional expiry and the flexible ids. */
class ModelsSerializationTest {
    private val json = Json
    private val explicit = Json { encodeDefaults = true }

    private val kdf = LockedIdentity.Kdf("argon2id13", 1, 8192, "c2FsdA")
    private val locked = LockedIdentity(1, kdf, "bm9uY2U", "Y2lwaGVy", LockedIdentity.PublicKeys("ZQ", "cw"))
    private val envelope = MessageEnvelope(1, "7", 1, "3", "bg", "Yw", "cw")

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T, format: Json = json) =
        assertEquals(value, format.decodeFromString(serializer, format.encodeToString(serializer, value)))

    @Test
    fun everyObjectSurvivesTheRoundTrip() {
        roundTrip(LockedIdentity.serializer(), locked)
        roundTrip(PublicKeyBundle.serializer(), PublicKeyBundle("ZQ", "cw", "Yg"))
        roundTrip(KeyGrant.serializer(), KeyGrant(1, "9", 2, "5", "4", "dw", "cw"))
        roundTrip(LinkApproval.serializer(), LinkApproval(locked, "a2V5"))
        roundTrip(MessageEnvelope.serializer(), envelope)
        roundTrip(MessageEnvelope.serializer(), envelope, explicit)
        roundTrip(MessageEnvelope.serializer(), envelope.copy(expiresAt = "2100-01-01T00:00:00Z"))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun refusesObjectsWithMissingFields() {
        for ((serializer, text) in listOf<Pair<KSerializer<*>, String>>(
            LockedIdentity.serializer() to "{}",
            LockedIdentity.Kdf.serializer() to "{}",
            LockedIdentity.PublicKeys.serializer() to "{}",
            PublicKeyBundle.serializer() to "{}",
            KeyGrant.serializer() to "{}",
            LinkApproval.serializer() to "{}",
            MessageEnvelope.serializer() to "{}"
        )) {
            assertThrows(serializer.descriptor.serialName, MissingFieldException::class.java) { json.decodeFromString(serializer, text) }
        }
    }

    @Test
    fun writesTheEnvelopeLikeTheBrowser() {
        assertEquals(JsonNull, envelope.toJsonObject()["expires_at"])
        assertEquals("\"2100-01-01T00:00:00Z\"", envelope.copy(expiresAt = "2100-01-01T00:00:00Z").toJsonObject()["expires_at"].toString())
    }

    @Test
    fun acceptsStringAndNumericIdsOnly() {
        fun grant(id: String) =
            """{"v":1,"conversation_id":$id,"key_version":1,"recipient_id":"5","granter_id":"4","wrapped_key":"w","signature":"s"}"""
        assertEquals("9", json.decodeFromString(KeyGrant.serializer(), grant("9")).conversationId)
        assertEquals("abc", json.decodeFromString(KeyGrant.serializer(), grant("\"abc\"")).conversationId)
        for (invalid in listOf("null", "true", "1.5", "[1]", "{}")) {
            assertThrows(invalid, SerializationException::class.java) { json.decodeFromString(KeyGrant.serializer(), grant(invalid)) }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun readsIdsFromOtherFormatsAsStrings() {
        val decoder = object : AbstractDecoder() {
            override val serializersModule: SerializersModule = EmptySerializersModule()

            override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE

            override fun decodeString(): String = "42"
        }
        assertEquals("42", FlexibleIdSerializer.deserialize(decoder))
    }
}
