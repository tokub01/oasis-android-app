package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto
import de.velwebsolutions.oasis.chatcrypto.ChatCryptoException
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.decryptMessage
import de.velwebsolutions.oasis.chatcrypto.encryptMessage
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.network.OasisJson
import java.time.Instant

/** One message as shown (client/messages.js). Plaintext lives only in memory and is dropped when it expires. */
data class ChatMessageItem(
    val signature: String,
    val envelope: MessageEnvelope,
    val senderId: String,
    val expiresAt: Instant?,
    val state: State,
    /** The text of a readable message; empty otherwise. */
    val plaintext: String = ""
) {
    enum class State {
        Readable,

        /** No key for this version (sent before the member joined, or after a key loss). */
        NoKey,

        /** Sender unknown or signature/ciphertext invalid. */
        Unverifiable
    }

    val id: String get() = signature
}

/** How much of the limits a draft uses (`measureDraft`). */
data class DraftMeasure(val characters: Int, val bytes: Int, val tooLong: Boolean)

/** Encrypt, verify and keep messages in order. */
object ChatMessages {
    fun measure(text: String): DraftMeasure {
        val characters = text.codePointCount(0, text.length)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        return DraftMeasure(characters, bytes, characters > ChatCrypto.MAX_MESSAGE_LENGTH || bytes > ChatCrypto.MAX_MESSAGE_BYTES)
    }

    /** Encrypt for the conversation's current key version with the server-clock expiry. */
    fun seal(plaintext: String, conversation: ChatConversation, vault: ChatVault, crypto: ChatCrypto): MessageEnvelope {
        val identity = vault.identity
        val key = vault.conversationKey(conversation.id, conversation.keyVersion)
        if (identity == null || key == null) throw ChatCryptoException.InvalidInput("conversationKey must be a Uint8Array of 32 bytes.")
        return crypto.encryptMessage(
            conversationKey = key,
            conversationId = conversation.id,
            keyVersion = conversation.keyVersion,
            senderId = vault.userId,
            identity = identity,
            plaintext = plaintext,
            expiresAt = vault.clock.expiry(conversation.lifetimeDays),
            now = vault.clock.now()
        )
    }

    /** Verify and decrypt one envelope; never throws – bad input becomes a visible placeholder. Null if already expired. */
    fun open(envelope: MessageEnvelope, conversationId: String, vault: ChatVault, crypto: ChatCrypto): ChatMessageItem? {
        val expiry = envelope.expiresAt?.let(OasisJson::parseInstant)
        val item = ChatMessageItem(envelope.signature, envelope, envelope.senderId, expiry, ChatMessageItem.State.Unverifiable)
        val key = vault.conversationKey(conversationId, envelope.keyVersion) ?: return item.copy(state = ChatMessageItem.State.NoKey)
        val sender = vault.publicKeys(envelope.senderId) ?: return item
        return try {
            val plaintext = crypto.decryptMessage(
                envelope,
                key,
                sender.signing,
                vault.clock.now(),
                expectedConversationId = conversationId
            ).plaintext
            item.copy(state = ChatMessageItem.State.Readable, plaintext = plaintext)
        } catch (_: ChatCryptoException.ExpiredMessage) {
            null
        } catch (_: ChatCryptoException) {
            item
        }
    }
}

/** In-memory list of one conversation's items, oldest first, de-duplicated by signature. Immutable: changes return a copy. */
data class ChatTimeline(val items: List<ChatMessageItem> = emptyList()) {
    fun has(signature: String): Boolean = items.any { it.signature == signature }

    /** The timeline with [item] at the end, or null when it is null or already there. */
    fun appending(item: ChatMessageItem?): ChatTimeline? = if (item == null || has(item.signature)) null else ChatTimeline(items + item)

    /** Older items (oldest first) before the current ones, skipping known ones. */
    fun prepending(older: List<ChatMessageItem?>): ChatTimeline {
        val fresh = older.filterNotNull().filter { !has(it.signature) }.distinctBy { it.signature }
        return ChatTimeline(fresh + items)
    }

    fun replacing(item: ChatMessageItem): ChatTimeline = ChatTimeline(items.map { if (it.signature == item.signature) item else it })

    /** Without expired items (and their plaintext); null when nothing expired. */
    fun droppingExpired(now: Instant): ChatTimeline? {
        val kept = items.filter { it.expiresAt == null || it.expiresAt.isAfter(now) }
        return if (kept.size == items.size) null else ChatTimeline(kept)
    }
}

/** "1 Tag" / "7 Tagen" inside a sentence ("löschen sich nach …"). */
fun lifetimeLabel(days: Int): String = if (days == 1) "1 Tag" else "$days Tagen"

/** The lifetime choices of the controls, in the nominative: "1 Tag", "7 Tage", "30 Tage". */
val lifetimeChoices: List<Pair<Int, String>> = listOf(1 to "1 Tag", 7 to "7 Tage", 30 to "30 Tage")

/** German date and time like the web's `formatDateTime` ("12.10.2026, 14:05"). */
fun formatDateTime(instant: Instant): String = GermanTime.format(instant, "dd.MM.yyyy, HH:mm")

/** "18.10.2026" like `toLocaleDateString('de-DE')`. */
fun formatDate(isoString: String): String = OasisJson.parseInstant(isoString)?.let { GermanTime.format(it, "d.M.yyyy") } ?: isoString
