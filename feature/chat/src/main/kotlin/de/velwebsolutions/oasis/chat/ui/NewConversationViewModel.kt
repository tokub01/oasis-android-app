package de.velwebsolutions.oasis.chat.ui

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.chat.api.PublicUser
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatInput
import de.velwebsolutions.oasis.chat.core.describeChatError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 1:1 or group. */
enum class ConversationKind(val wire: String) { Direct("direct"), Group("group") }

/** What the new-chat screen shows. */
data class NewConversationState(
    val kind: ConversationKind = ConversationKind.Direct,
    val username: String = "",
    val title: String = "",
    val lifetimeDays: Int = 7,
    val selected: List<PublicUser> = emptyList(),
    val error: String? = null,
    /** The confirmation of the last action (toast). */
    val status: String? = null
)

/** Screen "new" (ui/new-conversation.js; iOS `NewConversationModel`): pick people by exact username and start a chat. */
@HiltViewModel(assistedFactory = NewConversationViewModel.Factory::class)
class NewConversationViewModel @AssistedInject constructor(
    /** Preselected by a "Schreiben" button (`#neu/{username}`). */
    @Assisted val preselected: String?,
    context: ChatContext
) : ChatViewModel(context) {
    /** Creates the view model with an optional preselected username (Hilt). */
    @AssistedFactory
    interface Factory {
        /** The view model, with the person of a "Schreiben" button. */
        fun create(preselected: String?): NewConversationViewModel
    }

    private val mutableState = MutableStateFlow(NewConversationState())
    val state: StateFlow<NewConversationState> = mutableState.asStateFlow()

    val canParticipate: Boolean get() = context.canParticipate

    private var preselectionDone = false

    /** "Zu zweit" or "Gruppe". */
    fun setKind(value: ConversationKind) = mutableState.update { it.copy(kind = value) }

    /** The typed username. */
    fun setUsername(value: String) = mutableState.update { it.copy(username = value) }

    /** The typed group name. */
    fun setTitle(value: String) = mutableState.update { it.copy(title = value) }

    /** The picked lifetime. */
    fun setLifetimeDays(value: Int) = mutableState.update { it.copy(lifetimeDays = value) }

    /** The confirmation toast was shown. */
    fun dismissStatus() = mutableState.update { it.copy(status = null) }

    /** Adds the person of a "Schreiben" button once. */
    suspend fun applyPreselection() = inScope {
        val username = preselected ?: return@inScope
        if (preselectionDone || state.value.selected.isNotEmpty()) return@inScope
        preselectionDone = true
        setUsername(username)
        addFromInput()
    }

    /** "Hinzufügen" (also for a preselected username). */
    suspend fun addFromInput() = inScope {
        val current = state.value
        val name = ChatInput.username(current.username)
        if (name.isEmpty()) return@inScope
        val limit = if (current.kind == ConversationKind.Direct) 1 else MAX_GROUP_OTHERS
        if (current.selected.size >= limit) {
            val text = if (current.kind == ConversationKind.Direct) {
                "Ein Chat zu zweit hat genau eine andere Person. Wähle „Gruppe“ für mehr."
            } else {
                "Eine Gruppe hat höchstens 50 Menschen."
            }
            mutableState.update { it.copy(error = text) }
            return@inScope
        }
        try {
            val user = context.repository.findUser(name).firstOrNull()
            when {
                user == null -> mutableState.update { it.copy(error = ChatInput.unknownUser(name)) }

                user.id == context.userId -> mutableState.update { it.copy(error = "Du bist automatisch dabei.") }

                else -> mutableState.update {
                    it.copy(
                        selected = if (it.selected.any { selected -> selected.id == user.id }) it.selected else it.selected + user,
                        username = "",
                        error = null,
                        status = "@${user.username} hinzugefügt."
                    )
                }
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    /** Removes a selected person ("@mira ✕"). */
    fun remove(user: PublicUser) = mutableState.update { current -> current.copy(selected = current.selected.filter { it.id != user.id }) }

    /** "Chat starten": returns the new conversation's id. */
    suspend fun create(): String? = inScope {
        val current = state.value
        if (current.selected.isEmpty()) {
            mutableState.update { it.copy(error = "Füge mindestens eine Person hinzu.") }
            return@inScope null
        }
        if (current.kind == ConversationKind.Direct && current.selected.size != 1) {
            mutableState.update { it.copy(error = "Ein Chat zu zweit hat genau eine andere Person.") }
            return@inScope null
        }
        val trimmed = current.title.trim()
        try {
            val conversation = context.repository.createConversation(
                type = current.kind.wire,
                title = if (current.kind == ConversationKind.Group && trimmed.isNotEmpty()) trimmed else null,
                memberIds = current.selected.map { it.id },
                lifetimeDays = current.lifetimeDays
            )
            mutableState.update { it.copy(error = null) }
            conversation.id
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
            null
        }
    }

    companion object {
        const val MAX_GROUP_OTHERS = 49
    }
}
