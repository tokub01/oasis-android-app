package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.network.OasisJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

private val withDefaults = Json(OasisJson.snake) { encodeDefaults = true }

/**
 * Writes each value as the app sends it (defaults left out) and with every default spelled out, and reads both back
 * unchanged. With [required] a JSON object without any field is refused, so a missing required key cannot slip by.
 */
fun <T> assertWireFormat(serializer: KSerializer<T>, vararg values: T, required: Boolean = true) {
    values.forEach { value ->
        listOf(OasisJson.snake, withDefaults).forEach { json ->
            assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
        }
    }
    if (required) {
        assertTrue(runCatching { OasisJson.snake.decodeFromString(serializer, "{}") }.exceptionOrNull() is SerializationException)
    }
}
