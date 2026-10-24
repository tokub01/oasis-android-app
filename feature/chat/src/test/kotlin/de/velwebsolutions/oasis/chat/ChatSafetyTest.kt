package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.api.SafetyLists
import de.velwebsolutions.oasis.chat.core.ChatInput
import de.velwebsolutions.oasis.chat.core.ChatSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as tests/js/chat/safety.test.mjs in the Laravel repo and iOS' ChatSafetyTests. */
class ChatSafetyTest {
    private val lists = mapOf(
        "insult" to listOf("arschloch", "vollidiot"),
        "sexual" to listOf("nudes", "ficken"),
        "hate" to emptyList(),
        "threat" to listOf("umbringen"),
        "extremism" to emptyList()
    )
    private val tone = SafetyLists.Tone(always = listOf("digga", "brudi"), address = listOf("alter", "bro"))

    @Test
    fun recognisesRequestsForSecretsSolicitationAndInsults() {
        assertEquals(ChatSafety.Kind.Phishing, ChatSafety.scanIncoming("Schick mir mal dein Passwort, dann helfe ich dir", lists)?.kind)
        assertEquals(ChatSafety.Kind.Phishing, ChatSafety.scanIncoming("wie ist deine Adresse?", lists)?.kind)
        assertEquals(ChatSafety.Kind.Solicitation, ChatSafety.scanIncoming("Lust auf ein Taschengeld-Treffen?", lists)?.kind)
        assertEquals(ChatSafety.Kind.Solicitation, ChatSafety.scanIncoming("schick nudes", lists)?.kind)
        assertEquals(ChatSafety.Kind.Harassment, ChatSafety.scanIncoming("Du Vollidiot", lists)?.kind)
        assertEquals(ChatSafety.Kind.Harassment, ChatSafety.scanIncoming("Ich bring dich um, ich will dich umbringen", lists)?.kind)
    }

    @Test
    fun leavesOrdinaryMessagesAlone() {
        for (text in listOf(
            "Hallo, wie geht es dir?",
            "Treffen wir uns am Samstag im Stadtgarten?",
            "Das Passwort für das WLAN im Café steht an der Tafel"
        )) {
            assertNull(text, ChatSafety.scanIncoming(text, lists))
        }
    }

    @Test
    fun checksCardNumbersAndIbans() {
        assertTrue(ChatSafety.containsCardNumber("Karte: 4111 1111 1111 1111"))
        assertFalse(ChatSafety.containsCardNumber("Bestellnummer 1234 5678 9012 3456"))
        assertTrue(ChatSafety.containsIban("DE89 3704 0044 0532 0130 00"))
        assertFalse(ChatSafety.containsIban("DE00 3704 0044 0532 0130 00"))
    }

    @Test
    fun warnsBeforeSensitiveDraftsAndFindsTheToneHint() {
        assertEquals(listOf("ein Passwort oder einen Code"), ChatSafety.scanOutgoing("mein Passwort ist sonne123", tone).sensitive)
        assertEquals(listOf("eine Adresse"), ChatSafety.scanOutgoing("Ich wohne in der Lindenstraße 12, 10115 Berlin", tone).sensitive)
        val plain = ChatSafety.scanOutgoing("Bis morgen!", tone)
        assertTrue(plain.sensitive.isEmpty())
        assertNull(plain.tone)
        assertEquals("Digga", ChatSafety.toneHint("Digga, was geht", tone))
        assertNull(ChatSafety.toneHint("Im Alter von 30", tone))
        assertEquals("Alter", ChatSafety.toneHint("Alter, hör auf", tone))
    }

    @Test
    fun normalisesUsernamesAndChecksPassphrases() {
        assertEquals("mira", ChatInput.username("  @Mira "))
        assertEquals("Die Passphrase braucht mindestens 12 Zeichen.", ChatInput.passphraseProblem("kurz", "kurz"))
        assertEquals("Die beiden Eingaben stimmen nicht überein.", ChatInput.passphraseProblem("Palme Regen Kompass", "Palme Regen"))
        assertNull(ChatInput.passphraseProblem("Palme Regen Kompass", "Palme Regen Kompass"))
    }
}
