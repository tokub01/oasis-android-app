package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chat.api.AttachmentState
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto
import de.velwebsolutions.oasis.chatcrypto.ChatCryptoException
import de.velwebsolutions.oasis.chatcrypto.MessageAttachment
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.chatcrypto.MessagePoll
import de.velwebsolutions.oasis.chatcrypto.MessageReply
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
    /** The decrypted plaintext of a readable message (the v2 JSON for one with files); empty otherwise. */
    val plaintext: String = "",
    /** Text and files of [plaintext]. */
    val content: MessageContent = MessageContent(""),
    /** The member's view of the files by id (from the history; empty until it was loaded for a realtime message). */
    val attachmentStates: Map<String, AttachmentState> = emptyMap()
) {
    /**
     * Whether the file belongs to this message: own files always, others' only when the server's row of the message
     * lists them. An id copied from another message (someone's view-once photo) is never offered, answered or loaded
     * through this one (conversation-view.js); until the row is known such a file shows as not verifiable.
     */
    fun listed(attachmentId: String, mine: Boolean): Boolean = mine || attachmentStates.containsKey(attachmentId)

    /** The state of one file; before the server named it: pending for others' files, sent for own ones. */
    fun state(attachmentId: String, mine: Boolean): AttachmentState = attachmentStates[attachmentId]
        ?: AttachmentState(attachmentId, status = if (mine) AttachmentState.SENT else AttachmentState.PENDING)

    /** Whether the message could be read. */
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
data class DraftMeasure(
    val characters: Int,
    val bytes: Int,
    val tooLong: Boolean,
    /** Code points of the whole plaintext (with files, effect and quote it is JSON, which counts too: [MessagePayload.fits]). */
    val plaintextCharacters: Int = characters
) {
    /**
     * The most characters this draft can have (docs/oasis-chat-client.md, „Composer limit and draft“): what is still free
     * if the rest were plain letters, so files, a quote or an effect leave less room.
     */
    val max: Int
        get() = maxOf(
            0,
            minOf(
                ChatCrypto.MAX_MESSAGE_LENGTH,
                characters + (ChatCrypto.MAX_MESSAGE_BYTES - bytes),
                characters + (ChatCrypto.MAX_MESSAGE_LENGTH - plaintextCharacters)
            )
        )
}

/** Encrypt, verify and keep messages in order. */
object ChatMessages {
    /**
     * The entry [draft] adds to the plaintext once uploaded (`measureDraft`): its own kind, type, size, name and
     * dimensions, and the longest id, key and nonce an upload can get – a long file name or type shortens the text.
     */
    private fun entry(draft: DraftAttachment) = MessageAttachment(
        id = "9".repeat(19),
        kind = draft.kind.wire,
        mime = draft.mime,
        size = draft.data.size.toLong(),
        key = "k".repeat(43),
        nonce = "n".repeat(32),
        name = draft.name,
        width = draft.width,
        height = draft.height,
        durationMs = draft.durationMs
    )

    /**
     * Characters (code points) of the text, and UTF-8 bytes of the whole plaintext with the entries of [attachments],
     * against the message limits.
     */
    fun measure(
        text: String,
        attachments: List<DraftAttachment> = emptyList(),
        effect: String? = null,
        reply: MessageReply? = null
    ): DraftMeasure {
        val characters = text.codePointCount(0, text.length)
        val plaintext = MessagePayload.plaintext(MessageContent(text, attachments.map(::entry), effect, reply))
        val bytes = plaintext.toByteArray(Charsets.UTF_8).size
        val tooLong = characters > ChatCrypto.MAX_MESSAGE_LENGTH || !MessagePayload.fits(plaintext)
        return DraftMeasure(characters, bytes, tooLong, plaintext.codePointCount(0, plaintext.length))
    }

    /**
     * Encrypt for the conversation's current key version with the server-clock expiry (messages.js `sealMessage`).
     * @throws ChatKeyringException.UntrustedOwner if the owner is not an active owner member or changed
     * @throws ChatKeyringException.StaleKeyVersion if a newer key version of the conversation is open already
     */
    fun seal(plaintext: String, conversation: ChatConversation, vault: ChatVault, crypto: ChatCrypto): MessageEnvelope {
        ChatKeyring.assertSealable(vault, conversation)
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
            now = vault.clock.serverNow()
        )
    }

    /** Verify and decrypt one envelope; never throws – bad input becomes a visible placeholder. Null if already expired. */
    fun open(
        envelope: MessageEnvelope,
        conversationId: String,
        vault: ChatVault,
        crypto: ChatCrypto,
        states: List<AttachmentState> = emptyList()
    ): ChatMessageItem? {
        val expiry = envelope.expiresAt?.let(OasisJson::parseInstant)
        val item = ChatMessageItem(
            envelope.signature,
            envelope,
            envelope.senderId,
            expiry,
            ChatMessageItem.State.Unverifiable,
            attachmentStates = states.associateBy { it.id }
        )
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
            item.copy(state = ChatMessageItem.State.Readable, plaintext = plaintext, content = MessagePayload.decode(plaintext))
        } catch (_: ChatCryptoException.ExpiredMessage) {
            null
        } catch (_: ChatCryptoException) {
            item
        }
    }
}

/**
 * The reactions per target message (`reactionsByTarget`): for each sender their newest reaction counts, an empty emoji
 * removes it. Only the decrypted payload decides what a reaction is, never the server's `kind`.
 */
object ChatReactions {
    /** Target signature → sender id → emoji, from [items] oldest first. */
    fun byTarget(items: List<ChatMessageItem>): Map<String, Map<String, String>> {
        val targets = mutableMapOf<String, MutableMap<String, String>>()
        for (item in items) {
            val reaction = item.content.reaction ?: continue
            targets.getOrPut(reaction.to) { mutableMapOf() }[item.senderId] = reaction.emoji
        }
        return targets.mapValues { (_, senders) -> senders.filterValues { it.isNotEmpty() } }.filterValues { it.isNotEmpty() }
    }
}

/**
 * The pinned message (`pinnedMessage`): the newest valid pin counts, `{"to":""}` removes it. In a 1:1 chat both members may
 * pin, in a group only its owner; pins from anyone else are ignored.
 */
object ChatPins {
    /** The quote of the pinned message, or null. [items] oldest first. */
    fun pinned(items: List<ChatMessageItem>, conversation: ChatConversation): MessageReply? {
        var pinned: MessageReply? = null
        for (item in items) {
            val pin = item.content.pin ?: continue
            if (conversation.type == "direct" || item.senderId == conversation.createdBy) pinned = pin.quote
        }
        return pinned
    }
}

/** A poll as it stands: its creator, whether it was ended, and the counting vote (answer indices) per person. */
data class PollState(val poll: MessagePoll, val creator: String, val closed: Boolean, val votes: Map<String, List<Int>>)

/**
 * Every poll of the timeline (`pollStates`): the newest valid vote per person counts (`[]` takes it back; single choice
 * at most one answer; no index beyond the answers); only its creator can end it, and votes after that do not count.
 */
object ChatPolls {
    /** The polls by signature. [items] oldest first. */
    fun states(items: List<ChatMessageItem>): Map<String, PollState> {
        val polls = mutableMapOf<String, PollState>()
        for (item in items) {
            val content = item.content
            content.poll?.let { polls[item.signature] = PollState(it, item.senderId, closed = false, votes = emptyMap()) }
            content.pollClose?.let { signature ->
                polls[signature]?.takeIf { it.creator == item.senderId }?.let { polls[signature] = it.copy(closed = true) }
            }
            val vote = content.vote ?: continue
            val state = polls[vote.poll]?.takeIf { !it.closed } ?: continue
            val valid = vote.options.all { it < state.poll.options.size } && (state.poll.multiple || vote.options.size <= 1)
            if (!valid) continue
            polls[vote.poll] =
                state.copy(
                    votes = if (vote.options.isEmpty()) {
                        state.votes - item.senderId
                    } else {
                        state.votes +
                            (item.senderId to vote.options)
                    }
                )
        }
        return polls
    }
}

/** In-memory list of one conversation's items, oldest first, de-duplicated by signature. Immutable: changes return a copy. */
data class ChatTimeline(val items: List<ChatMessageItem> = emptyList()) {
    /** Whether a message with this signature is shown already. */
    fun has(signature: String): Boolean = items.any { it.signature == signature }

    /** The timeline with [item] at the end, or null when it is null or already there. */
    fun appending(item: ChatMessageItem?): ChatTimeline? = if (item == null || has(item.signature)) null else ChatTimeline(items + item)

    /** Older items (oldest first) before the current ones, skipping known ones. */
    fun prepending(older: List<ChatMessageItem?>): ChatTimeline {
        val fresh = older.filterNotNull().filter { !has(it.signature) }.distinctBy { it.signature }
        return ChatTimeline(fresh + items)
    }

    /** The timeline with [item] in place of the message with the same signature. */
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
