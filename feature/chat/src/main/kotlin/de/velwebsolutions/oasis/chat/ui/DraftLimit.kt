package de.velwebsolutions.oasis.chat.ui

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextRange
import de.velwebsolutions.oasis.chat.core.ChatMessages
import de.velwebsolutions.oasis.chat.core.DraftAttachment
import de.velwebsolutions.oasis.chat.core.DraftMeasure
import de.velwebsolutions.oasis.chatcrypto.MessageReply

/**
 * The composer's length limit (docs/oasis-chat-client.md, „Composer limit and draft“): the text with what goes along
 * with it (files, the effect, the quote) as [ChatMessages.measure] counts it. Typing beyond the limit does nothing, a
 * paste keeps only the part that fits, deleting always works.
 */
@Immutable
data class DraftLimit(
    val attachments: List<DraftAttachment> = emptyList(),
    val effect: String? = null,
    val reply: MessageReply? = null
) {
    /** How much of the limits [text] uses with everything going along. */
    fun measure(text: String): DraftMeasure = ChatMessages.measure(text, attachments, effect, reply)

    /** Keeps an edit of the composer within the limit: as much of what was typed or pasted as fits, the cursor after it. */
    fun applyTo(buffer: TextFieldBuffer) {
        val kept = keep(buffer.originalText.toString(), buffer.toString()) { !measure(it).tooLong } ?: return
        buffer.replace(0, buffer.length, kept.text)
        buffer.selection = TextRange(kept.cursor)
    }

    /** The composer's text after an edit that went beyond the limit, and the cursor in it. */
    data class Kept(val text: String, val cursor: Int)

    companion object {
        /** The counter shows from this share of the maximum. */
        private const val COUNTER_FROM = 0.75

        /** „3120 / 4000“ from 75 % of the maximum, „Höchstens 4000 Zeichen“ at or over it, else nothing (""). */
        fun counter(measure: DraftMeasure): String = when {
            measure.tooLong || measure.characters >= measure.max -> "Höchstens ${measure.max} Zeichen"
            measure.characters >= measure.max * COUNTER_FROM -> "${measure.characters} / ${measure.max}"
            else -> ""
        }

        /** The counter is at the limit (shown in the warning colour). */
        fun atLimit(measure: DraftMeasure): Boolean = measure.tooLong || measure.characters >= measure.max

        /**
         * What the composer keeps of the edit from [before] to [after]: null for all of it (it [fits], or it got no
         * longer); otherwise the text with as much of the inserted part as fits, whole characters only.
         */
        fun keep(before: String, after: String, fits: (String) -> Boolean): Kept? {
            if (fits(after) || after.codePointCount(0, after.length) <= before.codePointCount(0, before.length)) return null
            var start = 0
            val shorter = minOf(before.length, after.length)
            while (start < shorter && before[start] == after[start]) start++
            if (start > 0 && Character.isHighSurrogate(after[start - 1])) start--
            var end = 0
            while (end < shorter - start && before[before.length - 1 - end] == after[after.length - 1 - end]) end++
            if (end > 0 && Character.isLowSurrogate(after[after.length - end])) end--
            val head = after.substring(0, start)
            val inserted = after.substring(start, after.length - end)
            val tail = after.substring(after.length - end)
            var low = 0
            var high = inserted.codePointCount(0, inserted.length)
            // The largest number of inserted characters that fits (the room only shrinks with every character).
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (fits(head + inserted.substring(0, inserted.offsetByCodePoints(0, middle)) + tail)) low = middle else high = middle - 1
            }
            val taken = inserted.substring(0, inserted.offsetByCodePoints(0, low))
            return Kept(head + taken + tail, head.length + taken.length)
        }
    }
}
