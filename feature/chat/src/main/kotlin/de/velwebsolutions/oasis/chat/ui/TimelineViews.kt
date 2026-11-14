package de.velwebsolutions.oasis.chat.ui

import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatPins
import de.velwebsolutions.oasis.chat.core.ChatPolls
import de.velwebsolutions.oasis.chat.core.ChatReactions
import de.velwebsolutions.oasis.chat.core.PollState
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.chatcrypto.MessageReply
import java.time.Instant

/**
 * What the timeline shows besides its bubbles, for the member [myId] at [now] (docs/mildcrowd-chat-client.md, "Reactions
 * and replies", "Pinned message and polls"): the reaction chips, the quotes of replies, the pin bar and the polls.
 */
internal class TimelineViews(val myId: String, private val senderName: (String) -> String, val now: Instant) {
    /** The chips of every target: in the order of the set, the own one marked, the names with „Du“ last. */
    fun reactions(items: List<ChatMessageItem>): Map<String, List<ReactionChip>> = ChatReactions.byTarget(items).mapValues { (_, senders) ->
        MessagePayload.REACTIONS.mapNotNull { emoji ->
            val who = senders.filterValues { it == emoji }.keys
            if (who.isEmpty()) return@mapNotNull null
            ReactionChip(emoji, who.size, myId in who, "$emoji ${names(who)}")
        }
    }

    /** The quotes of the replies by the reply's signature. */
    fun quotes(items: List<ChatMessageItem>): Map<String, ReplyQuote> =
        items.mapNotNull { item -> item.content.reply?.let { item.signature to quote(it) } }.toMap()

    /** „Angeheftet: …“ of the pinned message, or null. */
    fun pinBar(items: List<ChatMessageItem>, conversation: ChatConversation?): PinBar? = conversation
        ?.let { ChatPins.pinned(items, it) }
        ?.let { PinBar(it.to, QuoteTexts.of(it), expired(it.expiresAt)) }

    /** Every poll as its bubble shows it; voting only while it is open and one may write ([canWrite]). */
    fun polls(items: List<ChatMessageItem>, canWrite: Boolean): Map<String, PollView> =
        ChatPolls.states(items).mapValues { (_, poll) -> pollView(poll, canWrite) }

    /** Who did something, as named in the chat, „Du“ last („Mira, Du“). */
    private fun names(who: Collection<String>): String =
        (who.filter { it != myId }.map(senderName) + listOfNotNull("Du".takeIf { myId in who })).joinToString(", ")

    /** Whether a quote's original (`expires_at`) is gone. */
    private fun expired(expiresAt: String): Boolean = !Instant.parse(expiresAt).isAfter(now)

    /** A reply's quote as shown: the original sender's name and the text, or gone once the original expired. */
    private fun quote(reply: MessageReply): ReplyQuote = ReplyQuote(
        to = reply.to,
        name = if (reply.sender == myId) "Du" else senderName(reply.sender),
        text = QuoteTexts.of(reply),
        gone = expired(reply.expiresAt)
    )

    private fun pollView(state: PollState, canWrite: Boolean): PollView {
        val mine = state.votes[myId].orEmpty()
        val open = !state.closed && canWrite
        val answers = state.poll.options.mapIndexed { index, option ->
            val who = state.votes.filterValues { index in it }.keys
            PollAnswer(option, who.size, index in mine, "$option: ${names(who)}")
        }
        return PollView(state.poll.question, answers, state.poll.multiple, state.closed, open, canClose = open && state.creator == myId)
    }
}

/** What a reply or pin quotes of [item]; nothing of a view-once message; null without a valid expiry. */
internal fun quoteOf(item: ChatMessageItem): MessageReply? = item.expiresAt?.let { expiry ->
    MessagePayload.replyTo(
        item.signature,
        item.senderId,
        expiry,
        item.content.text,
        item.content.attachments,
        viewOnce = item.attachmentStates.values.any { it.viewOnce }
    )
}

/** What the timeline shows besides its bubbles: the reactions, quotes, pin bar, polls and the rows of the list. */
internal class DerivedTimeline(
    val reactions: Map<String, List<ReactionChip>>,
    val quotes: Map<String, ReplyQuote>,
    val pinBar: PinBar?,
    val polls: Map<String, PollView>,
    val rows: List<TimelineRow>
) {
    companion object {
        /** All of it for [items]; [rows] lays out the bubbles (every item but the control messages). */
        fun of(views: TimelineViews, items: List<ChatMessageItem>, conversation: ChatConversation?, canWrite: Boolean, readAt: Instant?) =
            DerivedTimeline(
                views.reactions(items),
                views.quotes(items),
                views.pinBar(items, conversation),
                views.polls(items, canWrite),
                timelineRows(items.filter { !it.content.control }, conversation?.lifetimeDays ?: 0, readAt, views.myId, views.now)
            )
    }
}

/** The last value of [get], made again only when one of its inputs (always the same number) is another object than before. */
internal class IdentityCache<T : Any> {
    private var inputs: Array<out Any?> = emptyArray()
    private var value: T? = null

    fun get(vararg key: Any?, compute: () -> T): T {
        val cached = value
        if (cached != null && key.indices.all { key[it] === inputs[it] }) return cached
        return compute().also {
            inputs = key
            value = it
        }
    }
}
