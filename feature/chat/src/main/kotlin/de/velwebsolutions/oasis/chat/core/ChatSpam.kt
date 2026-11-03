package de.velwebsolutions.oasis.chat.core

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil

/**
 * Spam checks on the member's own device (docs/oasis-chat-client.md, „Schreien, Wiederholungen, Fluten“; client/spam.js).
 * Chats are end-to-end encrypted, so the server never sees the text: shouting and repetition give a calm hint and need a
 * second press, flooding pauses sending for a moment. The thresholds and the expected decisions are shared with the web
 * and iOS in chat-spam-vectors.json; the server only counts messages per member and chat (429 with [FLOOD_PAUSE]).
 */
object ChatSpam {
    const val SHOUTING_HINT = "Bitte nicht so laut – viele Großbuchstaben wirken wie Schreien."
    const val REPETITION_HINT = "Das wiederholt sich ziemlich oft."
    const val SEND_ANYWAY = "Trotzdem senden"
    const val FLOOD_PAUSE = "Kurz durchatmen – du kannst gleich weiterschreiben."

    private const val MIN_LETTERS = 12
    private const val UPPER_PERCENT = 70
    private const val IGNORE_WORDS_UP_TO = 3
    private const val SAME_WORD_IN_A_ROW = 4
    private const val SAME_CHARACTER_MORE_THAN = 8

    private val whiteSpace = Regex("(?U)\\s+")
    private val ignoredToken = Regex("^(?:@|https?://|www\\.)", RegexOption.IGNORE_CASE)
    private val word = Regex("[\\p{L}\\p{M}]+")
    private val notWordCharacter = Regex("[^\\p{L}\\p{M}\\p{N}]")
    private val skippedCharacter = Regex("(?U)^[\\s\\p{N}]+$")

    /** „Noch 27 s“ on the send button during a pause. */
    fun countdown(seconds: Int): String = "Noch $seconds s"

    /** The hint for a draft, or null: shouting goes before repetition, only one hint shows. */
    fun draftHint(text: String): String? = when {
        isShouting(text) -> SHOUTING_HINT
        isRepetitive(text) -> REPETITION_HINT
        else -> null
    }

    /**
     * Shouting: at least 12 letters and at least 70 % of them upper case. Counted are the letters (any script) of the
     * words – runs of letters and combining marks – with more than 3 letters; @mentions and links are left out.
     */
    fun isShouting(text: String): Boolean {
        var letters = 0
        var upper = 0
        for (token in tokens(text)) {
            if (ignoredToken.containsMatchIn(token)) continue
            for (match in word.findAll(token)) {
                val codePoints = match.value.codePoints().toArray()
                val wordLetters = codePoints.count(Character::isLetter)
                if (wordLetters > IGNORE_WORDS_UP_TO) {
                    letters += wordLetters
                    upper += codePoints.count { Character.getType(it) == Character.UPPERCASE_LETTER.toInt() }
                }
            }
        }
        return letters >= MIN_LETTERS && upper * 100 >= letters * UPPER_PERCENT
    }

    /**
     * Repetition: the same word 4 or more times in a row (lower case without punctuation; a token of punctuation only is
     * skipped), or the same grapheme – also an emoji with skin tone – more than 8 times in a row; white space and digits
     * never count as a repeated character.
     */
    fun isRepetitive(text: String): Boolean {
        var previous: String? = null
        var run = 0
        for (token in tokens(text)) {
            val shown = token.lowercase(Locale.ROOT).replace(notWordCharacter, "")
            if (shown.isEmpty()) continue
            run = if (shown == previous) run + 1 else 1
            previous = shown
            if (run >= SAME_WORD_IN_A_ROW) return true
        }
        var character: String? = null
        var same = 0
        for (grapheme in graphemes(text)) {
            if (skippedCharacter.matches(grapheme)) {
                character = null
                same = 0
                continue
            }
            same = if (grapheme == character) same + 1 else 1
            character = grapheme
            if (same > SAME_CHARACTER_MORE_THAN) return true
        }
        return false
    }

    private fun tokens(text: String): List<String> = text.split(whiteSpace).filter(String::isNotEmpty)

    private fun graphemes(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val parts = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            parts += text.substring(start, end)
            start = end
            end = iterator.next()
        }
        return parts
    }

    /**
     * Flooding in one chat: the member's own sends that left decide whether another one may leave now. Held are the third
     * same text in a row (not empty) when the first of them is at most 120 seconds old, and an eleventh message within
     * 30 seconds; a hold pauses sending for 30 seconds from then on. Times are seconds (any epoch).
     */
    class FloodGuard {
        private val sent = ArrayDeque<Pair<Double, String>>()
        private var pausedUntil = 0.0

        /** Seconds left before [text] may be sent at [now]; 0: it may leave. Starts the pause when it would flood. */
        fun wait(now: Double, text: String): Int {
            if (now < pausedUntil) return ceil(pausedUntil - now).toInt()
            val trimmed = text.trim()
            val last = sent.toList().takeLast(SAME_TEXT_IN_A_ROW - 1)
            val repeats = trimmed.isNotEmpty() && last.size == SAME_TEXT_IN_A_ROW - 1 && last.all { it.second == trimmed } &&
                now - last.first().first <= SAME_TEXT_WITHIN_SECONDS
            val recent = sent.count { now - it.first < MAX_MESSAGES_WITHIN_SECONDS }
            if (!repeats && recent < MAX_MESSAGES) return 0
            pausedUntil = now + PAUSE_SECONDS
            return PAUSE_SECONDS
        }

        /** The server's own limit (429): the same pause, [seconds] long, from [now]. */
        fun pause(now: Double, seconds: Int) {
            pausedUntil = now + seconds
        }

        /** The pause is over (its countdown ran out). */
        fun endPause() {
            pausedUntil = 0.0
        }

        /** Seconds left of a running pause at [now]; 0 without one. */
        fun remaining(now: Double): Int = if (now < pausedUntil) ceil(pausedUntil - now).toInt() else 0

        /** Note a send that left. */
        fun record(now: Double, text: String) {
            sent.addLast(now to text.trim())
            while (sent.size > MAX_MESSAGES * 2) sent.removeFirst()
        }

        private companion object {
            const val SAME_TEXT_IN_A_ROW = 3
            const val SAME_TEXT_WITHIN_SECONDS = 120
            const val MAX_MESSAGES = 10
            const val MAX_MESSAGES_WITHIN_SECONDS = 30
            const val PAUSE_SECONDS = 30
        }
    }
}
