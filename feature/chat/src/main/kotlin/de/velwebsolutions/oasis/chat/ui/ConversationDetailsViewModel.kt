package de.velwebsolutions.oasis.chat.ui

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatInput
import de.velwebsolutions.oasis.chat.core.describeChatError
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.network.OasisJson
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One member in the safety-number list. */
data class MemberRow(
    val id: String,
    val label: String,
    val username: String,
    val isMe: Boolean,
    /** 12 groups of 5 digits, or "Keine gültigen Schlüssel"; null for the member themselves. */
    val safetyNumber: String?,
    val warning: String?,
    val removable: Boolean
)

/** What the details screen shows. */
data class ConversationDetailsState(
    val conversation: ChatConversation? = null,
    val members: List<MemberRow> = emptyList(),
    /** The mute choice of the picker (`8h`, `1w`, `always`), as on the web. */
    val muteDuration: String = "8h",
    val lifetimeDays: Int = 7,
    val title: String = "",
    val username: String = "",
    /** Why the person in "Mitglied hinzufügen" cannot be added (shown at the field). */
    val addError: String? = null,
    val status: String? = null,
    val error: String? = null,
    val loadFailed: String? = null,
    val isOwner: Boolean = false
) {
    val isGroup: Boolean get() = conversation?.isGroup == true

    /** A group owner cannot leave (they remove members instead). */
    val canLeave: Boolean get() = !(isGroup && isOwner)

    val isMuted: Boolean get() = conversation?.muted == true

    /** "Stumm bis Fr., 24. Oktober um 05:53 Uhr.", "Dauerhaft stumm." or the default (the web's `renderMute`). */
    val muteState: String
        get() {
            if (!isMuted) return "Du bekommst Mitteilungen zu neuen Nachrichten, so wie unter Hinweise einstellen festgelegt."
            val until = conversation?.mutedUntil?.let(OasisJson::parseInstant) ?: return "Dauerhaft stumm."
            return "Stumm bis ${GermanTime.format(until, "EEE, d. MMMM 'um' HH:mm")} Uhr."
        }
}

/**
 * Screen "details" (ui/conversation-details.js; iOS `ConversationDetailsModel`): notifications (mute, only for oneself),
 * lifetime and group name (owner), members with their pairwise safety numbers and key warnings, adding/removing group
 * members (owner), and leaving the chat.
 */
@HiltViewModel(assistedFactory = ConversationDetailsViewModel.Factory::class)
class ConversationDetailsViewModel @AssistedInject constructor(
    @Assisted val conversationId: String,
    context: ChatContext
) : ChatViewModel(context) {
    /** Creates the view model for one conversation (Hilt). */
    @AssistedFactory
    interface Factory {
        /** The view model of [conversationId]. */
        fun create(conversationId: String): ConversationDetailsViewModel
    }

    private val mutableState = MutableStateFlow(ConversationDetailsState())
    val state: StateFlow<ConversationDetailsState> = mutableState.asStateFlow()

    /** The picked mute duration. */
    fun setMuteDuration(value: String) = mutableState.update { it.copy(muteDuration = value) }

    /** The picked lifetime. */
    fun setLifetimeDays(value: Int) = mutableState.update { it.copy(lifetimeDays = value) }

    /** The typed group name. */
    fun setTitle(value: String) = mutableState.update { it.copy(title = value) }

    /** The typed username to add. */
    fun setUsername(value: String) = mutableState.update { it.copy(username = value) }

    /** The confirmation toast was shown. */
    fun dismissStatus() = mutableState.update { it.copy(status = null) }

    /** The error toast was shown. */
    fun dismissError() = mutableState.update { it.copy(error = null) }

    /** The conversation with its members and safety numbers. */
    suspend fun load() = inScope {
        try {
            render(context.repository.conversation(conversationId))
            mutableState.update { it.copy(loadFailed = null) }
        } catch (error: Exception) {
            mutableState.update { it.copy(loadFailed = describeChatError(error)) }
        }
    }

    private suspend fun render(conversation: ChatConversation) {
        val myId = context.userId
        val isOwner = conversation.createdBy == myId
        mutableState.update {
            it.copy(
                conversation = conversation,
                isOwner = isOwner,
                lifetimeDays = conversation.lifetimeDays,
                muteDuration = if (conversation.muted == true) muteDuration(conversation.mutedUntil) else it.muteDuration,
                title = conversation.title.orEmpty()
            )
        }
        val keyring = context.keyring
        val directory = keyring.loadDirectory(conversation.id)
        val rows = conversation.members.map { member ->
            val isMe = member.userId == myId
            val changed = directory.user(member.userId)?.keyChangedAt != null || member.userId in directory.changedInSession
            MemberRow(
                id = member.userId,
                label = label(member, isMe),
                username = member.username,
                isMe = isMe,
                safetyNumber = if (isMe) null else keyring.safetyNumber(member.userId) ?: "Keine gültigen Schlüssel",
                warning = if (!isMe && changed) "Neue Schlüssel – Nummer erneut vergleichen." else null,
                removable = isOwner && conversation.isGroup && !isMe
            )
        }
        mutableState.update { it.copy(members = rows) }
    }

    /** "Stummschalten" or "Dauer ändern" with the picked duration. */
    suspend fun mute() = save("Der Chat ist stummgeschaltet.") { context.repository.mute(conversationId, state.value.muteDuration) }

    /** "Wieder benachrichtigen". */
    suspend fun unmute() = save("Du bekommst wieder Mitteilungen zu diesem Chat.") { context.repository.unmute(conversationId) }

    /** "Speichern" (owner; applies to new messages only). */
    suspend fun saveLifetime() = save("Gespeichert. Gilt für neue Nachrichten.") { updateLifetime() }

    private suspend fun updateLifetime() = context.repository.updateConversation(conversationId, lifetimeDays = state.value.lifetimeDays)

    /** "Namen speichern": the group name (owner), empty clears it. Not encrypted. */
    suspend fun saveTitle() = save("Der Name ist gespeichert.") { updateTitle(state.value.title.trim()) }

    private suspend fun updateTitle(title: String) =
        context.repository.updateConversation(conversationId, setTitle = true, title = title.ifEmpty { null })

    /** "Mitglied hinzufügen" (group owner) – the chat then gets a new key. */
    suspend fun addMember() = inScope {
        val name = ChatInput.username(state.value.username)
        if (name.isEmpty()) return@inScope
        try {
            val user = context.repository.findUser(name).firstOrNull()
            if (user == null) {
                mutableState.update { it.copy(addError = ChatInput.unknownUser(name)) }
                return@inScope
            }
            render(context.repository.addMember(conversationId, user.id))
            mutableState.update {
                it.copy(username = "", addError = null, status = "@$name hinzugefügt. Der Chat bekommt einen neuen Schlüssel.")
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(addError = describeChatError(error)) }
        }
    }

    /** "Entfernen" (group owner, after the confirmation). */
    suspend fun remove(row: MemberRow) = save("@${row.username} entfernt. Der Chat bekommt einen neuen Schlüssel.") { removeMember(row) }

    private suspend fun removeMember(row: MemberRow) = context.repository.removeMember(conversationId, row.id)

    /** "Chat verlassen": the chat disappears from the list. Returns true on success. */
    suspend fun leave(): Boolean = inScope {
        try {
            context.repository.leave(conversationId)
            vault.announcement.value = "Du hast den Chat verlassen."
            true
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
            false
        }
    }

    private suspend fun save(status: String, change: suspend () -> ChatConversation) = inScope {
        try {
            render(change())
            mutableState.update { it.copy(status = status) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    companion object {
        /** The mute choices of the web's select, in the same order and words. */
        val muteChoices = listOf("8h" to "8 Stunden stumm", "1w" to "1 Woche stumm", "always" to "Dauerhaft stumm")

        /** "Mira Sol (@mira) – du · hat den Chat gestartet"; "@mira …" without a display name. */
        fun label(member: ConversationMember, isMe: Boolean): String {
            val name = if (member.displayName.isNullOrEmpty()) member.name else "${member.name} (@${member.username})"
            return name + (if (isMe) " – du" else "") + (if (member.role == "owner") " · hat den Chat gestartet" else "")
        }

        /** The choice that matches a running mute, so "Dauer ändern" starts from it. */
        fun muteDuration(until: String?, now: Instant = Instant.now()): String {
            val end = until?.let(OasisJson::parseInstant) ?: return "always"
            return if (Duration.between(now, end).seconds <= 8 * 3600 + 60) "8h" else "1w"
        }
    }
}
