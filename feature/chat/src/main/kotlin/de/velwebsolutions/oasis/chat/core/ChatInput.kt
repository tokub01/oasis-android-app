package de.velwebsolutions.oasis.chat.core

/** Minimum length of a chat passphrase. */
const val MIN_PASSPHRASE_LENGTH = 12

/** What members type into the chat forms, checked the same way on every screen (as in resources/js/chat/ui). */
object ChatInput {
    /** "@Mira " → "mira": usernames are typed with or without "@" and matched exactly in lower case. */
    fun username(input: String): String = input.trim().removePrefix("@").lowercase()

    /** The answer when nobody can be added under a username (unknown, chat not set up, blocked or not reachable). */
    fun unknownUser(username: String): String = "@$username gibt es nicht oder hat den Chat noch nicht eingerichtet."

    /** Why a new passphrase cannot be used, or null when it can. */
    fun passphraseProblem(passphrase: String, confirmation: String): String? {
        if (passphrase.codePointCount(0, passphrase.length) < MIN_PASSPHRASE_LENGTH) {
            return "Die Passphrase braucht mindestens $MIN_PASSPHRASE_LENGTH Zeichen."
        }
        return if (passphrase == confirmation) null else "Die beiden Eingaben stimmen nicht überein."
    }
}
