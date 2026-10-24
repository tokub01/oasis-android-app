package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chatcrypto.ChatCryptoException
import de.velwebsolutions.oasis.core.network.ApiError

/** Errors of the key handling above the crypto core (client/keyring.js). */
sealed class ChatKeyringException(message: String) : Exception(message) {
    /** The owner could not distribute a key: these members have no verifiable keys. */
    data class MissingMemberKeys(val usernames: List<String>) : ChatKeyringException("Missing member keys.")

    /** The server publishes other keys than the identity that was just unlocked. */
    data object PublishedKeysMismatch : ChatKeyringException("Published keys do not match.")

    /** This device cannot keep a biometric copy (no fingerprint or face unlock enrolled, cancelled …). */
    data object DeviceUnlockUnsupported : ChatKeyringException("Device unlock unavailable.")
}

/** Short German texts for API and crypto errors (resources/js/chat/ui/errors.js). */
fun describeChatError(error: Throwable): String = when (error) {
    ChatCryptoException.WrongPassphrase -> "Die Passphrase stimmt nicht."

    is ChatCryptoException.MessageTooLong -> "Die Nachricht ist zu lang."

    is ChatKeyringException.MissingMemberKeys ->
        "Für ${error.usernames.joinToString(", ")} gibt es keine gültigen Chat-Schlüssel. Der Chat kann noch keinen Schlüssel bekommen."

    ChatKeyringException.PublishedKeysMismatch ->
        "Die veröffentlichten Schlüssel passen nicht zu deinen. Bitte melde dich bei uns – schreib vorerst nichts Vertrauliches."

    ApiError.Unauthenticated -> "Deine Sitzung ist abgelaufen. Bitte melde dich erneut an."

    // Server texts like the invitation refusal are more precise than the generic one.
    is ApiError.Forbidden ->
        if (error.serverMessage.isNotEmpty() && error.serverMessage != "This action is unauthorized.") {
            error.serverMessage
        } else {
            "Du hast keinen Zugriff (mehr) auf diesen Chat."
        }

    ApiError.NotFound -> "Diesen Chat gibt es nicht mehr."

    is ApiError.Conflict -> "Der Chat bekommt gerade einen neuen Schlüssel. Versuch es gleich noch einmal."

    is ApiError.Validation -> error.fields.values.firstOrNull()?.firstOrNull() ?: error.summary.ifEmpty { "Die Eingabe passt nicht." }

    is ApiError.TooManyRequests -> "Zu viele Anfragen in kurzer Zeit. Warte eine Minute."

    is ApiError.Transport -> error.userMessage

    else -> "Da ist etwas schiefgegangen. Versuch es gleich noch einmal."
}

/** How the app names the biometric unlock (iOS: Face ID), in the texts of setup, unlock and settings. */
internal object Biometrics {
    /** The browser's "Face ID unsupported" text, adapted: the app keeps the secret in the device's keystore. */
    const val UNSUPPORTED =
        "Das hat nicht geklappt. Auf diesem Gerät ist kein Fingerabdruck und keine Gesichtserkennung eingerichtet, oder es wurde abgebrochen. Dann nimm eine Passphrase."
}
