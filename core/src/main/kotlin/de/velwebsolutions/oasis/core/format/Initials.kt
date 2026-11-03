package de.velwebsolutions.oasis.core.format

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * The initials of a name like the server's `User::initials()`: the first grapheme of the first and of the last word
 * (NFC), upper case with the root locale unless that changes the grapheme's length. By grapheme, so an emoji, a letter
 * with a combining accent or „İ“ stays whole: „Yıldız“ → „Y“, „İlkay Şahin“ → „İŞ“, „🌵 Kaktus“ → „🌵K“.
 */
object Initials {
    private val separators = Regex("[\\s\\-_.]+")

    /** "MS" for „Mira Sol“, "M" for „mira“; null for a name without any word. */
    fun of(name: String): String? {
        val words = Normalizer.normalize(name.removePrefix("@"), Normalizer.Form.NFC).split(separators).filter(String::isNotEmpty)
        if (words.isEmpty()) return null
        val shown = if (words.size == 1) words else listOf(words.first(), words.last())
        return shown.joinToString("") { upper(firstGrapheme(it)) }
    }

    /** The first user-perceived character of [word] (a whole emoji sequence, a letter with its accents). */
    fun firstGrapheme(word: String): String {
        val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT)
        graphemes.setText(word)
        val end = graphemes.next()
        return if (end == BreakIterator.DONE) word else word.substring(0, end)
    }

    /** „ı“ → „I“, „ö“ → „Ö“; „ß“ (→ „SS“) and „ŉ“ stay as they are. */
    private fun upper(grapheme: String): String = grapheme.uppercase(Locale.ROOT).takeIf { it.length == grapheme.length } ?: grapheme
}
