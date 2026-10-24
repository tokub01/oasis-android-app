package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chat.api.SafetyLists
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Safety checks that run only on the device, on decrypted text (resources/js/chat/client/safety.js): nothing is sent
 * unless the member reports. Incoming messages are checked for requests for secrets, sexual offers and words of the
 * server's word list; drafts for card numbers, IBANs, passwords and addresses, plus the same tone hint as Tropfen.
 */
object ChatSafety {
    enum class Kind { Phishing, Solicitation, Harassment }

    data class Finding(val kind: Kind, val title: String)

    /** What a draft would reveal, and the slang address for the tone hint. */
    data class Outgoing(val sensitive: List<String>, val tone: String?)

    private const val ASKS_FOR_SECRETS =
        """\b(schick|schicke|gib|sag|nenn|verrat|schreib|brauch\w*|wie\s+(?:ist|lautet))\b[^.!?\n]{0,40}\b(dein\w*|ihr\w*)\s+(passwort|kennwort|pin|tan|code|zugangsdaten|login\w*|kreditkarte\w*|kartennummer|karten-?daten|iban|bankdaten|kontonummer|adresse|anschrift|wohnort|handynummer)\b"""
    private const val SOLICITATION =
        """\b(taschengeld|tg[- ]?treffen|sugar\s?(daddy|baby|mommy|mami)|escort\w*|sex\s?date|sexdate|one\s?night\s?stand|nudes?|nacktbilder|nacktfotos|nacktvideos?|gegen\s+(bezahlung|geld|cash)|was\s+(kostest|nimmst)\s+du|bezahl\w*\s+dich|zeig\s+(mal\s+)?(dich|was)\s+nackt)\b"""
    private const val DISCLOSES_SECRET =
        """\b(mein\w*\s+)?(passwort|kennwort|password|pin|tan|zugangscode|bestätigungscode)\s*(ist|lautet|:|=)\s*\S+"""
    private const val ADDRESS =
        """\b[A-ZÄÖÜ][a-zäöüß-]+(stra(ß|ss)e|str\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee)\s+\d{1,4}\s?[a-z]?\b|\b\d{5}\s+[A-ZÄÖÜ][a-zäöüß]{2,}"""
    private const val CARD = """(?:\d[ -]?){13,19}"""
    private const val IBAN = """\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){11,30}\b"""

    /** Check a decrypted message from someone else. */
    fun scanIncoming(text: String, lists: Map<String, List<String>>): Finding? {
        if (matches(regex(ASKS_FOR_SECRETS, true), text)) {
            return Finding(Kind.Phishing, "Achtung: Hier fragt jemand nach sensiblen Daten.")
        }
        if (matches(regex(SOLICITATION, true), text) || hits(text, lists["sexual"])) {
            return Finding(Kind.Solicitation, "Möglicherweise sexuelle Belästigung erkannt.")
        }
        if (listOf("insult", "hate", "threat", "extremism").any { hits(text, lists[it]) }) {
            return Finding(Kind.Harassment, "Möglicherweise Belästigung erkannt.")
        }
        return null
    }

    /** Check the member's own draft: what sensitive data would leave the device, and a slang address for the hint. */
    fun scanOutgoing(text: String, tone: SafetyLists.Tone): Outgoing {
        val sensitive = buildList {
            if (containsCardNumber(text)) add("eine Kartennummer")
            if (containsIban(text)) add("eine IBAN")
            if (matches(regex(DISCLOSES_SECRET, true), text)) add("ein Passwort oder einen Code")
            if (matches(regex(ADDRESS, false), text)) add("eine Adresse")
        }
        return Outgoing(sensitive, toneHint(text, tone))
    }

    /** Card numbers: 13–19 digits passing the Luhn check. */
    fun containsCardNumber(text: String): Boolean {
        for (match in allMatches(regex(CARD, false), text)) {
            val digits = match.filter { it in '0'..'9' }.map { it - '0' }
            if (digits.size !in 13..19) continue
            var sum = 0
            digits.reversed().forEachIndexed { index, value ->
                var digit = value
                if (index % 2 == 1) {
                    digit *= 2
                    if (digit > 9) digit -= 9
                }
                sum += digit
            }
            if (sum % 10 == 0) return true
        }
        return false
    }

    /** IBANs with a valid mod-97 check sum. */
    fun containsIban(text: String): Boolean {
        for (match in allMatches(regex(IBAN, false), text.uppercase())) {
            val iban = match.replace(Regex("""\s"""), "")
            val rearranged = iban.drop(4) + iban.take(4)
            var rest = 0
            for (character in rearranged) {
                val digits = when (character) {
                    in '0'..'9' -> character.toString()
                    in 'A'..'Z' -> (character.code - 55).toString()
                    else -> continue
                }
                for (digit in digits) rest = (rest * 10 + (digit - '0')) % 97
            }
            if (rest == 1) return true
        }
        return false
    }

    /** Same rule as App\Support\Moderation\ToneHint: always-slang words anywhere, ordinary words only as an address. */
    fun toneHint(text: String, tone: SafetyLists.Tone): String? {
        val plain = text.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
        if (tone.always.isNotEmpty()) {
            val pattern = "(?<![\\p{L}])(${tone.always.joinToString("|") { Pattern.quote(it) }})(?![\\p{L}])"
            firstGroup(regex(pattern, true), plain)?.let { return it }
        }
        if (tone.address.isNotEmpty()) {
            val list = tone.address.joinToString("|") { Pattern.quote(it) }
            firstGroup(regex("(?:^|[.!?]\\s+)($list)\\s*[,!?]", true), plain)?.let { return it }
            firstGroup(regex(",\\s*($list)\\s*[.!?]*\\s*$", true), plain)?.let { return it }
        }
        return null
    }

    private fun hits(text: String, list: List<String>?): Boolean {
        if (list.isNullOrEmpty()) return false
        val normalised = text.lowercase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        val words = normalised.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()
        return list.any { it in words }
    }

    /** Compiled patterns, so the draft check on every keystroke does not compile them again. */
    private val compiled = ConcurrentHashMap<String, Pattern>()

    /**
     * ICU semantics as on iOS: Unicode-aware `\b`, `\w` and case folding. Android's regex engine is ICU and Unicode-aware
     * already (and rejects UNICODE_CHARACTER_CLASS); the JVM of the unit tests needs the flag for the same behaviour.
     */
    private fun regex(pattern: String, caseInsensitive: Boolean): Pattern =
        compiled.getOrPut((if (caseInsensitive) "i" else "-") + pattern) {
            val case = if (caseInsensitive) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
            try {
                Pattern.compile(pattern, case or Pattern.UNICODE_CHARACTER_CLASS)
            } catch (_: IllegalArgumentException) {
                Pattern.compile(pattern, case)
            }
        }

    private fun matches(pattern: Pattern, text: String): Boolean = pattern.matcher(text).find()

    private fun allMatches(pattern: Pattern, text: String): List<String> {
        val matcher = pattern.matcher(text)
        return buildList { while (matcher.find()) add(matcher.group()) }
    }

    private fun firstGroup(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }
}
