package de.velwebsolutions.oasis.account.ui.releases

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.api.Release
import de.velwebsolutions.oasis.account.ui.components.AccountPage
import de.velwebsolutions.oasis.account.ui.components.SubHeading
import de.velwebsolutions.oasis.account.ui.retry
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/** Was ist neu (/neuigkeiten): every version of Oasis with its notes for this app, newest first, and how Oasis is built. */
@Composable
internal fun ReleasesScreen(viewModel: ReleasesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OasisScreen(title = "Was ist neu") {
        AccountPage(spacing = 16) {
            PageHeader(
                "Was ist neu",
                eyebrow = "Deine App: Mildcrowd ${viewModel.version}",
                lead = "Jede Version von Mildcrowd, die neueste zuerst. Web, iPhone und Android tragen dieselbe Nummer."
            )
            LoadableView(state, onRetry = viewModel.retry { viewModel.reload() }) { releases ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (releases.about.isNotBlank()) AboutCard(releases.about)
                    releases.data.forEach { release -> ReleaseCard(release, current = release.version == releases.current) }
                }
            }
        }
    }
}

/** How Oasis is built, with AI: paragraphs separated by an empty line. */
@Composable
private fun AboutCard(about: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
            .padding(18.dp)
            .testTag("releases.about"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SubHeading("Wie Mildcrowd entsteht", size = 17)
        about.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.forEach { paragraph ->
            Text(paragraph.trim(), style = OasisType.Body, color = OasisColors.Ink2)
        }
    }
}

@Composable
private fun ReleaseCard(release: Release, current: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
            .padding(18.dp)
            .testTag("release.${release.version}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp).semantics { heading() },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mildcrowd ${release.version}", style = OasisType.CardTitle, color = OasisColors.Night, modifier = Modifier.weight(1f))
            if (current) Pill("Aktuell")
        }
        Text(release.day, style = OasisType.Small, color = OasisColors.Muted)
        ReleaseNotes(release)
    }
}

/** Title, summary and the notes for this app, grouped by kind. */
@Composable
private fun ReleaseNotes(release: Release) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(release.title, style = OasisType.BodyBold, color = OasisColors.Night)
        if (release.summary.isNotBlank()) Text(release.summary, style = OasisType.Body, color = OasisColors.Ink2)
        release.notesFor().forEach { group ->
            Text(group.label, style = OasisType.SmallBold, color = OasisColors.Night, modifier = Modifier.padding(top = 6.dp))
            group.texts.forEach { text ->
                Text("• $text", style = OasisType.Body, color = OasisColors.Ink)
            }
        }
    }
}

/** „Neu in Oasis 0.24.0“ once after an update, with „Verstanden“. */
@Composable
internal fun UpdateNotesDialog(viewModel: UpdateNotesViewModel = hiltViewModel()) {
    LaunchedEffect(viewModel) { viewModel.check() }
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    notes?.let { release ->
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Neu in Mildcrowd ${release.version}", style = OasisType.CardTitle, color = OasisColors.Night) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("updatenotes")) {
                    ReleaseNotes(release)
                }
            },
            confirmButton = { OasisTextButton("Verstanden", viewModel::dismiss) },
            containerColor = OasisColors.Paper
        )
    }
}
