package de.velwebsolutions.oasis.staff.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle

/** Systemzustand (web: livewire.admin.system): health checks, backups and monitoring, bot refusals, last runs. */
@Composable
fun SystemScreen(viewModel: SystemViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OasisScreen("System", error = state.error, onDismissError = viewModel::dismissError) {
        StaffLoadable(state.status, viewModel::load) { status ->
            StaffPage(viewModel::load) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Admin")
                    StaffTitle("Systemzustand")
                    Text(
                        "Alles, wovon die Versprechen von Oasis abhängen: Löschfristen, Mollie-Abgleich, Mails und Sicherungen. " +
                            "Probleme meldet der Zeitplaner alle 15 Minuten per E-Mail. Was zu tun ist, steht in " +
                            "docs/oasis-operations.md.",
                        style = OasisType.Small,
                        color = OasisColors.Ink2
                    )
                }
                SectionCard(if (status.healthy) "Alles in Ordnung" else "Es gibt Probleme") {
                    AsyncOasisButton(
                        "Jetzt prüfen",
                        viewModel::load,
                        Modifier.testTag("system.check"),
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false
                    )
                    status.checks.forEach { check ->
                        HorizontalDivider(color = OasisColors.PaperLine)
                        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(check.label, style = OasisType.BodyBold, color = OasisColors.Night)
                            Text(
                                "${if (check.ok) "✓" else "✕"} ${check.detail}",
                                style = if (check.ok) OasisType.Small else OasisType.SmallBold,
                                color = if (check.ok) OasisColors.Ink2 else OasisColors.SunText
                            )
                        }
                    }
                }
                SectionCard("Sicherungen und Überwachung") {
                    status.backups.forEach { line -> Text("• $line", style = OasisType.Small, color = OasisColors.Ink) }
                }
                SectionCard("Abgewehrte Bot-Versuche heute") {
                    if (status.botRefusals.isEmpty()) Text("Heute keine.", style = OasisType.Body, color = OasisColors.Ink2)
                    status.botRefusals.forEach { refusal ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(refusal.label, style = OasisType.Small, color = OasisColors.Ink, modifier = Modifier.weight(1f))
                            Text("${refusal.count}", style = OasisType.BodyBold, color = OasisColors.Ink)
                        }
                    }
                    Text(
                        "Nur Zähler, ohne Adressen oder Konten (docs/oasis-bot-protection.md).",
                        style = OasisType.Caption,
                        color = OasisColors.Muted
                    )
                }
                SectionCard("Letzte Läufe") {
                    if (status.heartbeats.isEmpty()) {
                        Text(
                            "Noch keine Läufe aufgezeichnet.",
                            style = OasisType.Body,
                            color = OasisColors.Ink2
                        )
                    }
                    status.heartbeats.forEach { beat ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(beat.key, style = OasisType.BodyBold, color = OasisColors.Ink)
                            Text(
                                "erfolgreich ${StaffDate.short(
                                    beat.lastSuccessAt
                                )} · fehlgeschlagen ${StaffDate.short(beat.lastFailureAt)}",
                                style = OasisType.Caption,
                                color = OasisColors.Ink2
                            )
                        }
                    }
                }
            }
        }
    }
}
