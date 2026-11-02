package de.velwebsolutions.oasis.chat.ui

import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.describeChatError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the report dialog shows. */
data class ReportState(
    val category: String? = null,
    val comment: String = "",
    val error: String? = null,
    val sent: Boolean = false,
    val blocked: Boolean = false,
    /** The quoted message; forgotten after sending. */
    val quote: String? = null,
    /** Files of the message, which go to the moderation team with it. */
    val attachments: Int = 0
)

/**
 * Dialog "report" (ui/report-dialog.js; iOS `ReportModel`): consent-based reporting of one decrypted message – the
 * signed envelope plus the plaintext the reporter sees and the reason. A message with files forwards its v2 plaintext
 * unchanged: the server opens exactly those files with the keys in it for the moderation case. Lives as long as the dialog (owned by the
 * conversation screen).
 */
class ReportModel(
    private val context: ChatContext,
    private val conversationId: String,
    val item: ChatMessageItem,
    senderName: String
) {
    private val mutableState = MutableStateFlow(
        ReportState(
            quote = "$senderName: ${item.content.text.ifEmpty { "(nur Anhang)" }}",
            attachments = item.content.attachments.size
        )
    )
    val state: StateFlow<ReportState> = mutableState.asStateFlow()

    /** The picked problem. */
    fun setCategory(value: String) = mutableState.update { it.copy(category = value) }

    /** The optional comment. */
    fun setComment(value: String) = mutableState.update { it.copy(comment = value) }

    /** "Melden". */
    suspend fun send() {
        val category = state.value.category
        if (category == null) {
            mutableState.update { it.copy(error = "Wähl bitte aus, was nicht stimmt.") }
            return
        }
        val trimmed = state.value.comment.trim()
        val reason = if (trimmed.isEmpty()) category else "$category\n\n$trimmed"
        try {
            context.repository.report(conversationId, item.envelope, item.plaintext, reason)
            mutableState.update { it.copy(quote = null, error = null, sent = true) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    /** "Person blockieren" on the thanks screen. */
    suspend fun block() {
        try {
            context.repository.block(item.senderId)
            mutableState.update { it.copy(blocked = true) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = describeChatError(error)) }
        }
    }

    companion object {
        /** The note that the message's files go along with the report (report-dialog.js); null without files. */
        fun attachmentNote(count: Int): String? = when (count) {
            0 -> null
            1 -> "Der Anhang dieser Nachricht (Foto, Sprachnachricht, Video oder Datei) geht mit an das Moderationsteam."
            else -> "Die $count Anhänge dieser Nachricht (Fotos, Sprachnachrichten, Videos oder Dateien) gehen mit an das Moderationsteam."
        }

        /** `App\Enums\Moderation\ReportCategory::label()`, in order. */
        val categories = listOf(
            "Mobbing – jemand wird fertiggemacht, bloßgestellt oder ausgegrenzt",
            "Belästigung oder Beleidigung",
            "Sexuelle Belästigung oder Anfragen",
            "Hass, Hetze oder Extremismus",
            "Drohung oder Gewalt",
            "Betrug oder Fragen nach Passwort, Bank- oder Kartendaten",
            "Spam oder Fake-Profil",
            "Etwas anderes stimmt nicht"
        )
    }
}
