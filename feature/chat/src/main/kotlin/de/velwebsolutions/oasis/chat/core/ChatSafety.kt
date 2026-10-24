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

    // Word boundaries, word characters and spaces spelled out with Unicode classes, so the patterns mean the same on
    // Android's ICU engine (like iOS) and on the JVM of the unit tests, without engine flags. Digits are [0-9] like
    // JavaScript's \d: no card number, IBAN or postcode uses other scripts' digits.
    private const val S = "(?<![\\p{L}\\p{N}_])"
    private const val E = "(?![\\p{L}\\p{N}_])"
    private const val W = "[\\p{L}\\p{N}_]"
    private const val SPACE = "[\\s\\p{Z}]"
    private const val NOT_SPACE = "[^\\s\\p{Z}]"

    private val ASKS_FOR_SECRETS =
        "$S(schick|schicke|gib|sag|nenn|verrat|schreib|brauch$W*|wie$SPACE+(?:ist|lautet))$E[^.!?\\n]{0,40}$S(dein$W*|ihr$W*)$SPACE+(passwort|kennwort|pin|tan|code|zugangsdaten|login$W*|kreditkarte$W*|kartennummer|karten-?daten|iban|bankdaten|kontonummer|adresse|anschrift|wohnort|handynummer)$E"
    private val SOLICITATION =
        "$S(taschengeld|tg[- ]?treffen|sugar$SPACE?(daddy|baby|mommy|mami)|escort$W*|sex$SPACE?date|sexdate|one$SPACE?night$SPACE?stand|nudes?|nacktbilder|nacktfotos|nacktvideos?|gegen$SPACE+(bezahlung|geld|cash)|was$SPACE+(kostest|nimmst)$SPACE+du|bezahl$W*$SPACE+dich|zeig$SPACE+(mal$SPACE+)?(dich|was)$SPACE+nackt)$E"
    private val DISCLOSES_SECRET =
        "$S(mein$W*$SPACE+)?(passwort|kennwort|password|pin|tan|zugangscode|bestätigungscode)$SPACE*(ist|lautet|:|=)$SPACE*$NOT_SPACE+"

    private val ADDRESS =
        "$S[A-ZÄÖÜ][a-zäöüß-]+(stra(ß|ss)e|str\\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee)$SPACE+[0-9]{1,4}$SPACE?[a-z]?$E|$S[0-9]{5}$SPACE+[A-ZÄÖÜ][a-zäöüß]{2,}"
    private const val CARD = "(?:[0-9][ -]?){13,19}"
    private val IBAN = "$S[A-Z]{2}[0-9]{2}(?:$SPACE?[A-Z0-9]){11,30}$E"

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
            // The pattern found 13–19 digits with optional single spaces or hyphens between them.
            val digits = match.filter { it != ' ' && it != '-' }.map { it - '0' }
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
            val iban = match.filter { it.isLetterOrDigit() }
            val rearranged = iban.drop(4) + iban.take(4)
            var rest = 0
            for (character in rearranged) {
                // Only A–Z and 0–9 are left: letters count as 10 (A) to 35 (Z).
                val digits = if (character <= '9') character.toString() else (character.code - 55).toString()
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

    /** Case folding as on iOS: Unicode-aware, so "ÄRGER" matches "ärger". */
    private fun regex(pattern: String, caseInsensitive: Boolean): Pattern =
        compiled.getOrPut((if (caseInsensitive) "i" else "-") + pattern) {
            Pattern.compile(pattern, if (caseInsensitive) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)
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
