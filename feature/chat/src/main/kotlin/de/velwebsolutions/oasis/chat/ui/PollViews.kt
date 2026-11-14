package de.velwebsolutions.oasis.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisCheckbox
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/** „Angeheftet: …“: the pinned message's quote text, and [gone] once its expiry passed (then not tappable). */
data class PinBar(val to: String, val text: String, val gone: Boolean) {
    /** What the bar says after „Angeheftet:“. */
    val shown: String get() = if (gone) QuoteTexts.GONE else text
}

/**
 * The poll form above the field (docs/mildcrowd-chat-client.md, "Pinned message and polls"): the question, 2–6 answers and
 * „Mehrere Antworten erlaubt“, with the message of the last failed „Umfrage senden“.
 */
data class PollForm(
    val question: String = "",
    val options: List<String> = listOf("", ""),
    val multiple: Boolean = false,
    val error: String? = null
) {
    /** „Antwort hinzufügen“ up to 6. */
    val canAdd: Boolean get() = options.size < MessagePayload.POLL_MAX_OPTIONS

    /** „Entfernen“ down to 2. */
    val canRemove: Boolean get() = options.size > MessagePayload.POLL_MIN_OPTIONS

    /** The answers that are sent: trimmed, empty ones dropped. */
    val answers: List<String> get() = options.map(String::trim).filter(String::isNotEmpty)

    /** Why the poll cannot be sent yet, or null. */
    val problem: String?
        get() {
            val asked = question.trim()
            return when {
                asked.isEmpty() -> "Bitte stell eine Frage."

                points(asked) > MessagePayload.POLL_QUESTION_LENGTH ->
                    "Die Frage ist zu lang (höchstens ${MessagePayload.POLL_QUESTION_LENGTH} Zeichen)."

                answers.size < MessagePayload.POLL_MIN_OPTIONS -> "Eine Umfrage braucht mindestens zwei Antworten."

                answers.any { points(it) > MessagePayload.POLL_OPTION_LENGTH } ->
                    "Eine Antwort ist zu lang (höchstens ${MessagePayload.POLL_OPTION_LENGTH} Zeichen)."

                else -> null
            }
        }

    fun withQuestion(value: String) = copy(question = value)

    fun withOption(index: Int, value: String) = copy(
        options = options.mapIndexed { position, option ->
            if (position ==
                index
            ) {
                value
            } else {
                option
            }
        }
    )

    fun adding() = if (canAdd) copy(options = options + "") else this

    fun removing(index: Int) = if (canRemove) copy(options = options.filterIndexed { position, _ -> position != index }) else this

    fun withMultiple(value: Boolean) = copy(multiple = value)

    private fun points(text: String) = text.codePointCount(0, text.length)
}

/** One answer of a poll bubble: its text, the count, whether it is the own vote, and who voted („Samstag: Mira, Du“). */
data class PollAnswer(val text: String, val count: Int, val mine: Boolean, val voters: String) {
    /** „2 Stimmen für Samstag“. */
    val description: String get() = "$count ${if (count == 1) "Stimme" else "Stimmen"} für $text"
}

/** A poll as its bubble shows it: [open] while one may still vote, [canClose] for its creator while it is open. */
data class PollView(
    val question: String,
    val answers: List<PollAnswer>,
    val multiple: Boolean,
    val closed: Boolean,
    val open: Boolean,
    val canClose: Boolean
) {
    /** The vote after tapping answer [index]: single choice replaces (the chosen one again takes it back), multiple toggles. */
    fun choosing(index: Int): List<Int> {
        val mine = answers.indices.filter { answers[it].mine }
        return when {
            index in mine -> mine - index
            multiple -> mine + index
            else -> listOf(index)
        }
    }

    /** „Mehrere Antworten möglich“, „Beendet“, both, or nothing. */
    val note: String? get() = listOfNotNull(
        "Mehrere Antworten möglich".takeIf {
            multiple
        },
        "Beendet".takeIf { closed }
    ).joinToString(" · ").ifEmpty { null }
}

/** The steps of a poll bubble: vote for an answer (by index), and „Umfrage beenden“ (after confirming). */
class PollActions(val onVote: (Int) -> Unit, val onClose: () -> Unit) {
    companion object {
        val None = PollActions({}, {})
    }
}

/** The steps of the poll form. */
class PollFormActions(
    val onOpen: () -> Unit,
    val onEdit: ((PollForm) -> PollForm) -> Unit,
    val onCancel: () -> Unit,
    val onSend: suspend () -> Unit
) {
    companion object {
        /** Screens without polls (previews). */
        val None = PollFormActions({}, {}, {}, {})
    }
}

/** „Angeheftet: …“ under the notices: tapping it jumps to the message until its expiry. */
@Composable
internal fun PinBarView(bar: PinBar, onJump: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
            .border(1.dp, OasisColors.DuneLine, RoundedCornerShape(OasisRadius.S.dp))
            .pressable(onJump, enabled = !bar.gone)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("chat.pin"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Angeheftet:", style = OasisType.BodyBold, color = OasisColors.Night)
        Text(bar.shown, style = OasisType.Body, color = OasisColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The poll form above the field: „Frage“, „Antwort 1“ …, „Antwort hinzufügen“, „Entfernen“, the switch, send and cancel. */
@Composable
internal fun PollFormView(form: PollForm, actions: PollFormActions) {
    Column(
        // Up to six answers stay reachable on a small screen with the keyboard open: the form scrolls in itself.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.CHIP.dp))
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("chat.poll.form"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OasisTextField("Frage", form.question, { value ->
            actions.onEdit { it.withQuestion(value) }
        }, Modifier.testTag("chat.poll.question"))
        form.options.forEachIndexed { index, option ->
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OasisTextField(
                    "Antwort ${index + 1}",
                    option,
                    { value -> actions.onEdit { it.withOption(index, value) } },
                    Modifier.weight(1f).testTag("chat.poll.option.$index")
                )
                if (form.canRemove) {
                    SmallAction("Entfernen", {
                        actions.onEdit { it.removing(index) }
                    }, Modifier.testTag("chat.poll.remove.$index"), underline = true)
                }
            }
        }
        if (form.canAdd) {
            OasisButton(
                "Antwort hinzufügen",
                { actions.onEdit { it.adding() } },
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                modifier = Modifier.testTag("chat.poll.add")
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OasisCheckbox(form.multiple, { value -> actions.onEdit { it.withMultiple(value) } }, Modifier.testTag("chat.poll.multiple"))
            Text("Mehrere Antworten erlaubt", style = OasisType.Body, color = OasisColors.Ink)
        }
        form.error?.let { FieldError(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncOasisButton("Umfrage senden", actions.onSend, fullWidth = false, modifier = Modifier.testTag("chat.poll.send"))
            OasisButton(
                "Abbrechen",
                actions.onCancel,
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                modifier = Modifier.testTag("chat.poll.cancel")
            )
        }
    }
}

/** A poll in its bubble: the question, one row per answer with its count (tap: who voted), the note and „Umfrage beenden“. */
@Composable
internal fun PollBubble(poll: PollView, mine: Boolean, actions: PollActions) {
    val color = if (mine) OasisColors.OnNight else OasisColors.Ink
    val tint = color.copy(alpha = 0.15f)
    var voters by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("chat.poll")) {
        Text(poll.question, style = OasisType.BodyBold, color = color)
        poll.answers.forEachIndexed { index, answer ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    answer.text,
                    style = OasisType.Body.copy(fontWeight = if (answer.mine) FontWeight.Bold else FontWeight.Normal),
                    color = color,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .background(if (answer.mine) tint else Color.Transparent, RoundedCornerShape(OasisRadius.XS.dp))
                        .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(OasisRadius.XS.dp))
                        .pressable({ actions.onVote(index) }, enabled = poll.open)
                        .semantics { selected = answer.mine }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("chat.poll.answer.$index")
                )
                Text(
                    answer.count.toString(),
                    style = OasisType.SmallBold,
                    color = color,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .background(tint.copy(alpha = 0.1f), CircleShape)
                        .pressable({ voters = answer.voters })
                        .semantics {
                            role = Role.Button
                            contentDescription = answer.description
                        }
                        .padding(12.dp)
                        .testTag("chat.poll.count.$index")
                )
            }
        }
        poll.note?.let { Text(it, style = OasisType.Caption, color = color.copy(alpha = 0.8f)) }
        voters?.let { Text(it, style = OasisType.Caption, color = color, modifier = Modifier.testTag("chat.poll.voters")) }
        if (poll.canClose) {
            SmallAction("Umfrage beenden", {
                confirmClose = true
            }, Modifier.testTag("chat.poll.close"), color = color, underline = true)
        }
    }
    if (confirmClose) {
        ConfirmDialog("Umfrage beenden? Danach kann niemand mehr abstimmen.", "Beenden", actions.onClose) { confirmClose = false }
    }
}
