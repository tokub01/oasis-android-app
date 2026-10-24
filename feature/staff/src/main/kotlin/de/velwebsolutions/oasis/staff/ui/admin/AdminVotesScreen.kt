package de.velwebsolutions.oasis.staff.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisCard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.ui.components.DateTimeField
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.components.StaffHeading
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle

/** Member votes for admins (web: livewire.admin.votes): create a vote and see participation, results after the end. */
@Composable
fun AdminVotesScreen(viewModel: AdminVotesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OasisScreen(
        "Abstimmungen",
        message = state.message,
        error = state.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        StaffLoadable(state.votes, viewModel::load) { votes ->
            StaffPage(viewModel::load, spacing = 20) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Admin")
                    StaffTitle("Abstimmungen")
                    Text(
                        "Jedes Mitglied mit aktiver Mitgliedschaft hat eine Stimme. Die Abstimmung ist geheim: Wir speichern nur, dass " +
                            "jemand abgestimmt hat, nicht wie. Ergebnisse sieht auch hier niemand vor dem Ende.",
                        style = OasisType.Small,
                        color = OasisColors.Ink2
                    )
                }
                SectionCard("Neue Abstimmung") {
                    OasisTextField(
                        "Frage",
                        state.title,
                        { value -> viewModel.edit { it.copy(title = value) } },
                        Modifier.testTag("vote.title"),
                        error = state.fieldErrors["title"]
                    )
                    OasisTextField(
                        "Erklärung",
                        state.description,
                        { value -> viewModel.edit { it.copy(description = value) } },
                        Modifier.testTag("vote.description"),
                        error = state.fieldErrors["description"],
                        lines = 5..10
                    )
                    DateTimeField("Beginn", state.startsAt, { value ->
                        viewModel.edit { it.copy(startsAt = value) }
                    }, error = state.fieldErrors["starts_at"])
                    DateTimeField("Ende", state.endsAt, { value ->
                        viewModel.edit { it.copy(endsAt = value) }
                    }, error = state.fieldErrors["ends_at"])
                    Text("Antworten (mindestens zwei)", style = OasisType.BodyBold, color = OasisColors.Ink)
                    state.options.forEachIndexed { index, option ->
                        OasisTextField(
                            "Antwort ${index + 1}",
                            option,
                            { value -> viewModel.edit { it.copy(options = it.options.toMutableList().apply { set(index, value) }) } },
                            Modifier.testTag("vote.option.$index")
                        )
                    }
                    state.fieldErrors["options"]?.let { FieldError(it) }
                    AsyncOasisButton("Abstimmung anlegen", viewModel::create, Modifier.testTag("vote.create"), fullWidth = false)
                }
                StaffHeading("Alle Abstimmungen")
                if (votes.isEmpty()) Text("Noch keine Abstimmungen.", style = OasisType.Body, color = OasisColors.Ink2)
                votes.forEach { vote ->
                    OasisCard(Modifier.testTag("vote.${vote.id}"), padding = 16) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(vote.title, style = OasisType.BodyBold, color = OasisColors.Night)
                            Text(
                                "${StaffDate.wallClock(
                                    vote.startsAt
                                )} – ${StaffDate.wallClock(vote.endsAt)} · ${vote.state} · ${vote.participations} Stimmen",
                                style = OasisType.Caption,
                                color = OasisColors.Muted
                            )
                            vote.results.orEmpty().forEach { result ->
                                Text(
                                    buildAnnotatedString {
                                        append("${result.label}: ")
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${result.votes}") }
                                    },
                                    style = OasisType.Small,
                                    color = OasisColors.Ink
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
