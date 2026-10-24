package de.velwebsolutions.oasis.core.network

/**
 * Everything that can go wrong with an API call, already translated into what the screens need: field errors for forms,
 * a German message for banners, and the states that change the whole app (signed out, locked). Same cases as iOS
 * `APIError`.
 */
sealed class ApiError(message: String) : Exception(message) {
    /** 422: Laravel validation errors per field (`errors.login`, `errors.body`, …) plus the summary message. */
    data class Validation(val summary: String, val fields: Map<String, List<String>>) : ApiError(summary)

    /** 401: the token is missing, revoked or expired – the app signs out. */
    data object Unauthenticated : ApiError("unauthenticated")

    /** 403: not allowed (policy, missing verification, locked account). The message comes from the server. */
    data class Forbidden(val serverMessage: String) : ApiError(serverMessage)

    /** 404: gone or never visible to this member. */
    data object NotFound : ApiError("not found")

    /** 409: e.g. e-mail not verified yet (Laravel answers `verified` with 409 for JSON). */
    data class Conflict(val serverMessage: String) : ApiError(serverMessage)

    /** 413: request too large (images, chat payloads). */
    data object PayloadTooLarge : ApiError("payload too large")

    /** 429: rate limit; `retryAfter` in seconds when the server sent it. */
    data class TooManyRequests(val retryAfter: Int?) : ApiError("too many requests")

    /** 5xx or any other unexpected status. */
    data class Server(val status: Int, val serverMessage: String?) : ApiError(serverMessage ?: "HTTP $status")

    /** No connection, timeout, TLS problem. */
    data class Transport(val description: String) : ApiError(description)

    /** The answer did not have the expected shape. */
    data class Decoding(val description: String) : ApiError(description)

    /** The first message for a field, for inline form errors. */
    fun message(field: String): String? = (this as? Validation)?.fields?.get(field)?.firstOrNull()

    /** The first message of every invalid field, keyed by the wire name (`quiet_from`, `body` …); empty otherwise. */
    val fieldMessages: Map<String, String>
        get() = (this as? Validation)?.fields?.mapNotNull { (key, value) -> value.firstOrNull()?.let { key to it } }?.toMap().orEmpty()

    /** A short German text for banners and alerts. */
    val userMessage: String
        get() = when (this) {
            is Validation -> fields.values.firstOrNull()?.firstOrNull() ?: summary

            Unauthenticated -> "Bitte melde dich erneut an."

            is Forbidden ->
                if (serverMessage.isEmpty() || serverMessage == "This action is unauthorized.") {
                    "Das darfst du leider nicht."
                } else {
                    serverMessage
                }

            NotFound -> "Das gibt es nicht (mehr) oder es ist nicht für dich sichtbar."

            is Conflict -> serverMessage.ifEmpty { "Das geht gerade nicht." }

            PayloadTooLarge -> "Das ist zu groß."

            is TooManyRequests -> "Zu viele Versuche. Bitte warte einen Moment."

            is Server -> serverMessage ?: "Da ist etwas schiefgelaufen. Bitte versuch es gleich noch einmal."

            is Transport -> "Keine Verbindung zu Oasis. Bitte prüfe dein Internet."

            is Decoding -> "Die Antwort von Oasis war unerwartet. Bitte aktualisiere die App."
        }

    companion object {
        /** Turns any thrown error into an [ApiError]. */
        fun from(error: Throwable): ApiError = error as? ApiError ?: Transport(error.message ?: error.toString())
    }
}

/** Shorthand for [ApiError.from]. */
fun Throwable.asApiError(): ApiError = ApiError.from(this)
