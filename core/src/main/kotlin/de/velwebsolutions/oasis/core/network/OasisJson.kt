package de.velwebsolutions.oasis.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * JSON coding shared by every request: the Laravel API uses snake_case keys and ISO-8601 dates (Carbon's
 * `toISOString()`, e.g. `2026-10-22T13:37:00.000000Z`, with up to six fractional digits). Properties are camelCase in
 * Kotlin and converted like iOS' `convertFromSnakeCase`; [verbatim] keeps keys as declared (chat crypto envelopes,
 * camelCase on the wire and signed byte for byte).
 */
object OasisJson {
    /** camelCase in Kotlin ⇄ snake_case on the wire (default for all Laravel resources). */
    val snake: Json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    /** Keys exactly as declared. */
    val verbatim: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Parses ISO-8601 with or without fractional seconds (any number of digits) and plain `Y-m-d` dates. */
    fun parseInstant(raw: String): Instant? = try {
        if (raw.length == 10) {
            LocalDate.parse(raw).atStartOfDay(GermanZone).toInstant()
        } else {
            OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        }
    } catch (_: DateTimeParseException) {
        null
    }

    /** `2026-10-22T13:37:00.000Z`, like the iOS encoder. */
    fun formatInstant(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))

    /** `Y-m-d`, as the API expects for birth dates and other calendar days. */
    fun formatDay(day: LocalDate): String = day.toString()

    private val GermanZone = java.time.ZoneId.of("Europe/Berlin")
}

/** ISO-8601 instants as the API sends them. Use as `@Serializable(with = InstantSerializer::class)`. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("oasis.Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return OasisJson.parseInstant(raw) ?: throw kotlinx.serialization.SerializationException("Unbekanntes Datumsformat: $raw")
    }

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(OasisJson.formatInstant(value))
}

/** `Y-m-d` calendar days (birth dates, membership periods). */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("oasis.LocalDate", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString().take(10))

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
}

/** An [Instant] property decoded with [InstantSerializer]. */
typealias OasisInstant = @kotlinx.serialization.Serializable(with = InstantSerializer::class) Instant

/** A [LocalDate] property decoded with [LocalDateSerializer]. */
typealias OasisDay = @kotlinx.serialization.Serializable(with = LocalDateSerializer::class) LocalDate
