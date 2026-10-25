package de.velwebsolutions.oasis.chatcrypto

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The encoding helpers shared by every chat object – the Kotlin twin of resources/js/chat/encoding.js. Base64 (URL-safe
 * without padding, exactly like the browser) goes through libsodium and lives on [ChatCrypto].
 *
 * [canonicalBytes] prefixes every field with its 4-byte big-endian length after a domain-separation label, so different
 * field lists never produce the same signed bytes.
 */
object ChatEncoding {
    /** One field of [canonicalBytes]: bytes, or a string encoded as UTF-8. */
    sealed interface Field {
        val bytes: ByteArray

        /** Raw bytes (keys, nonces, ciphertexts). */
        class Bytes(override val bytes: ByteArray) : Field

        /** A string, signed as its UTF-8 bytes. */
        class Text(value: String) : Field {
            override val bytes: ByteArray = value.toByteArray(Charsets.UTF_8)
        }
    }

    /** `label || len(f1) || f1 || len(f2) || f2 …` with 4-byte big-endian lengths (the label is length-prefixed too). */
    fun canonicalBytes(label: String, fields: List<Field>): ByteArray {
        val parts = listOf(Field.Text(label)) + fields
        val buffer = ByteBuffer.allocate(parts.sumOf { 4 + it.bytes.size })
        for (part in parts) {
            buffer.putInt(part.bytes.size)
            buffer.put(part.bytes)
        }
        return buffer.array()
    }

    /** Shorthand for a field list of strings and byte arrays. */
    fun fields(vararg values: Any): List<Field> = values.map { value ->
        when (value) {
            is ByteArray -> Field.Bytes(value)
            is String -> Field.Text(value)
            else -> throw IllegalArgumentException("Unsupported canonical field.")
        }
    }

    /** Overwrites a buffer holding secret material with zeros (best effort – the JVM may have made copies). */
    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    /** Validates an identifier like `normaliseId` in encoding.js: non-empty, at most 255 UTF-16 units. */
    fun normaliseId(value: String, name: String): String {
        if (value.isEmpty() || value.length > 255) {
            throw ChatCryptoException.InvalidInput("$name must be a non-empty string or a safe integer.")
        }
        return value
    }

    /** Validates a key version (non-negative safe integer). */
    fun normaliseKeyVersion(value: Int): Int {
        if (value < 0) throw ChatCryptoException.InvalidInput("keyVersion must be a non-negative safe integer.")
        return value
    }

    /** `a` before `b` byte by byte (unsigned), like Swift's `lexicographicallyPrecedes` on `[UInt8]`. */
    internal fun precedesOrEquals(a: ByteArray, b: ByteArray): Boolean {
        for (index in 0 until minOf(a.size, b.size)) {
            val left = a[index].toInt() and 0xFF
            val right = b[index].toInt() and 0xFF
            if (left != right) return left < right
        }
        return a.size <= b.size
    }

    /** Strict UTF-8 like `new TextDecoder('utf-8', { fatal: true })`: invalid sequences fail, a leading BOM is dropped. */
    fun strictUtf8(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return if (text.startsWith('﻿')) text.substring(1) else text
    }

    /** `YYYY-MM-DDTHH:mm:ssZ` of whole Unix seconds, like `toISOString().replace('.000Z', 'Z')`. */
    fun formatExpiry(seconds: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(seconds))

    private val expiryPattern =
        Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,6}))?(Z|([+-])(\d{2}):(\d{2}))$""")

    /**
     * Parses an envelope expiry: null, or ISO 8601 (`(\.\d{1,6})?` fraction, `Z` or `±HH:MM`) on a whole second, into
     * Unix seconds. Mirrors `parseEnvelopeExpiry` (JS `Date.parse` keeps milliseconds, so a non-zero fraction is refused).
     */
    fun parseEnvelopeExpiry(value: String?): Long? {
        if (value == null) return null
        val invalid = ChatCryptoException.InvalidInput("Invalid expires_at.")
        val match = expiryPattern.matchEntire(value) ?: throw invalid
        val group = match.groupValues
        val year = group[1].toInt()
        val month = group[2].toInt()
        val day = group[3].toInt()
        val hour = group[4].toInt()
        val minute = group[5].toInt()
        val second = group[6].toInt()
        // Date.parse rejects out-of-range fields (month 13, hour 25 …); 24:00:00 is the one ISO exception it accepts.
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val daysInMonth = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val validDate = month in 1..12 && day in 1..daysInMonth[month - 1]
        val validTime = minute < 60 && second < 60 && (hour < 24 || (hour == 24 && minute == 0 && second == 0))
        if (!validDate || !validTime) throw invalid
        val fraction = group[7]
        if (fraction.isNotEmpty() && fraction.take(3).padEnd(3, '0').toInt() != 0) throw invalid
        var offsetSeconds = 0L
        if (group[9].isNotEmpty()) {
            val hours = group[10].toInt()
            val minutes = group[11].toInt()
            if (hours >= 24 || minutes >= 60) throw invalid
            offsetSeconds = (hours * 3600L + minutes * 60L) * (if (group[9] == "-") -1 else 1)
        }
        return daysFromCivil(year, month, day) * 86_400 + hour * 3600L + minute * 60L + second - offsetSeconds
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val m = month.toLong()
        val dayOfYear = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }
}
