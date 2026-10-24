package de.velwebsolutions.oasis.staff.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.BreachRegister
import de.velwebsolutions.oasis.staff.api.DataBreach
import de.velwebsolutions.oasis.staff.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.staff.ui.components.DateTimeField
import de.velwebsolutions.oasis.staff.ui.components.Disclosure
import de.velwebsolutions.oasis.staff.ui.components.RadioList
import de.velwebsolutions.oasis.staff.ui.components.Sharing
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.components.StaffHeading
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle
import de.velwebsolutions.oasis.staff.ui.components.staffBox
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Register of data breaches (web: livewire.admin.data-breaches): record, 72-hour deadline, drafted Art. 33 report,
 * report, notice to the people affected, addenda and closing. Oasis sends nothing by itself.
 */
@Composable
fun DataBreachesScreen(viewModel: DataBreachesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmNotified by remember { mutableStateOf<DataBreach?>(null) }
    var confirmClose by remember { mutableStateOf<DataBreach?>(null) }
    OasisScreen(
        "Datenpannen",
        message = state.message,
        error = state.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        StaffLoadable(state.register, viewModel::load) { register ->
            StaffPage(viewModel::load) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Admin")
                    StaffTitle("Datenpannen")
                    Text(
                        "Jede Verletzung des Schutzes personenbezogener Daten wird hier dokumentiert – auch wenn sie nicht gemeldet " +
                            "werden muss (Art. 33 Abs. 5 DSGVO). Besteht ein Risiko, muss die Aufsichtsbehörde binnen 72 Stunden ab " +
                            "Bekanntwerden informiert werden; bei hohem Risiko auch die Betroffenen. Oasis meldet nichts selbst: Die " +
                            "Seite bereitet die Meldung vor, du schickst sie ab. Der Notfallplan steht in " +
                            "docs/oasis-incident-response.md.",
                        style = OasisType.Small,
                        color = OasisColors.Ink2
                    )
                }
                BreachForm(register, state, viewModel)
                StaffHeading("Register (${register.data.size})")
                if (register.data.isEmpty()) Text("Noch keine Datenpannen erfasst.", style = OasisType.Body, color = OasisColors.Ink2)
                register.data.forEach { breach ->
                    BreachCard(breach, register.authority, state, viewModel, { confirmNotified = breach }, { confirmClose = breach })
                }
            }
        }
    }
    confirmNotified?.let { breach ->
        ConfirmDialog(
            "Sind alle Betroffenen in klarer Sprache benachrichtigt (Art. 34 DSGVO)?",
            "Benachrichtigung vermerken",
            { scope.launch { viewModel.recordNotified(breach) } },
            { confirmNotified = null }
        )
    }
    confirmClose?.let { breach ->
        ConfirmDialog("Datenpanne abschließen?", "Abschließen", { scope.launch { viewModel.close(breach) } }, { confirmClose = null })
    }
}

@Composable
private fun BreachForm(register: BreachRegister, state: DataBreachesUiState, viewModel: DataBreachesViewModel) {
    val form = state.form
    val errors = state.fieldErrors
    SectionCard("Neue Datenpanne erfassen") {
        OasisTextField(
            "Kurze Bezeichnung",
            form.title,
            { value -> viewModel.edit { it.copy(form = it.form.copy(title = value)) } },
            Modifier.testTag("breach.title"),
            error = errors["title"],
            hint = "z. B. „Rechnungs-E-Mails an falsche Empfänger“"
        )
        OasisTextField(
            "Was ist passiert?",
            form.description,
            { value -> viewModel.edit { it.copy(form = it.form.copy(description = value)) } },
            error = errors["description"],
            hint = "Ablauf, Ursache, seit wann, wie entdeckt.",
            lines = 3..10
        )
        OasisTextField(
            "Welche Daten sind betroffen?",
            form.dataCategories,
            { value -> viewModel.edit { it.copy(form = it.form.copy(dataCategories = value)) } },
            error = errors["data_categories"],
            hint = "z. B. Name, E-Mail-Adresse, Zahlungsdaten.",
            lines = 3..10
        )
        OasisTextField(
            "Wahrscheinliche Folgen für die Betroffenen",
            form.consequences,
            { value -> viewModel.edit { it.copy(form = it.form.copy(consequences = value)) } },
            error = errors["consequences"],
            hint = "z. B. Spam, Identitätsmissbrauch, Bloßstellung.",
            lines = 3..10
        )
        OasisTextField(
            "Ergriffene oder geplante Maßnahmen",
            form.measures,
            { value -> viewModel.edit { it.copy(form = it.form.copy(measures = value)) } },
            error = errors["measures"],
            hint = "z. B. Lücke geschlossen, Passwörter zurückgesetzt.",
            lines = 3..10
        )
        DateTimeField(
            "Bekannt geworden am",
            state.discoveredAt,
            { value -> viewModel.edit { it.copy(discoveredAt = value) } },
            error = errors["discovered_at"],
            latest = Instant.now()
        )
        OasisTextField(
            "Ungefähre Zahl Betroffener (optional)",
            form.affectedPeople,
            { value -> viewModel.edit { it.copy(form = it.form.copy(affectedPeople = value)) } },
            error = errors["affected_people"]
        )
        RadioList(
            "Risiko für die Betroffenen",
            register.risks,
            form.risk,
            { value -> viewModel.edit { it.copy(form = it.form.copy(risk = value)) } },
            "breach.risk",
            errors["risk"]
        )
        AsyncOasisButton("Datenpanne erfassen", viewModel::record, Modifier.testTag("breach.record"), fullWidth = false)
    }
}

/** One breach of the register. */
@Composable
private fun BreachCard(
    breach: DataBreach,
    authority: String?,
    state: DataBreachesUiState,
    viewModel: DataBreachesViewModel,
    onConfirmNotified: () -> Unit,
    onConfirmClose: () -> Unit
) {
    val open = breach.closedAt == null
    val context = LocalContext.current
    Column(
        Modifier
            .staffBox(if (breach.reportOutstanding) OasisColors.Sun else Color.Transparent, width = 2, radius = OasisRadius.M)
            .padding(16.dp)
            .testTag("breach.${breach.id}"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(breach.title, style = OasisType.BodyBold, color = OasisColors.Night)
                Text(
                    "Bekannt seit ${StaffDate.withUhr(breach.discoveredAt)} · ${breach.riskLabel}",
                    style = OasisType.Caption,
                    color = OasisColors.Ink2
                )
            }
            Pill(if (open) "Offen" else "Abgeschlossen", tone = if (open) PillTone.Sun else PillTone.Sand)
        }
        if (breach.reportOutstanding) {
            Text(
                if (breach.hoursLeft >= 0) {
                    "Meldung an die Aufsichtsbehörde fällig in ${breach.hoursLeft} Stunden (${StaffDate.withUhr(breach.reportDueAt)})."
                } else {
                    "Die 72 Stunden sind abgelaufen (${StaffDate.withUhr(
                        breach.reportDueAt
                    )}). Sofort melden und die Verzögerung begründen."
                },
                style = OasisType.BodyBold,
                color = OasisColors.SunText
            )
        }
        breach.reportText?.let { report ->
            Disclosure("Vorbereitete Meldung an die Aufsichtsbehörde") {
                SelectionContainer {
                    Text(
                        report,
                        style = OasisType.Digits.copy(fontSize = OasisType.Caption.fontSize),
                        color = OasisColors.Ink,
                        modifier = Modifier.fillMaxWidth().background(
                            OasisColors.Field,
                            RoundedCornerShape(OasisRadius.CHIP.dp)
                        ).padding(10.dp)
                    )
                }
                Row(
                    Modifier
                        .defaultMinSize(minHeight = 44.dp)
                        .pressable({ Sharing.shareText(context, report, "Text teilen oder kopieren") }),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(18.dp))
                    Text("Text teilen oder kopieren", style = OasisType.SmallBold, color = OasisColors.Night)
                }
                Text(
                    "Zuständig: ${authority ?: "–"}. Bitte prüfen und dort über das offizielle Meldeformular einreichen.",
                    style = OasisType.Caption,
                    color = OasisColors.Muted
                )
            }
        }
        Text(
            "Meldung an die Behörde: " + (
                breach.authorityReportedAt?.let { "${StaffDate.withUhr(it)} – ${breach.authorityReference.orEmpty()}" }
                    ?: if (breach.mustReport) "noch offen" else "nicht nötig"
                ),
            style = OasisType.Small,
            color = OasisColors.Ink
        )
        Text(
            "Betroffene informiert: " +
                (breach.peopleNotifiedAt?.let(StaffDate::withUhr) ?: if (breach.mustNotifyPeople) "noch offen" else "nicht nötig"),
            style = OasisType.Small,
            color = OasisColors.Ink
        )
        if (open) {
            HorizontalDivider(color = OasisColors.PaperLine)
            if (breach.canRecordReport) {
                OasisTextField(
                    "Meldung abgeschickt? Behörde und Aktenzeichen",
                    state.references[breach.id].orEmpty(),
                    { value -> viewModel.edit { it.copy(references = it.references + (breach.id to value)) } }
                )
                AsyncOasisButton("Meldung vermerken", { viewModel.recordReport(breach) }, fullWidth = false)
            }
            if (breach.canRecordNotified) {
                OasisButton(
                    "Benachrichtigung der Betroffenen vermerken",
                    onConfirmNotified,
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false
                )
            }
            OasisTextField(
                "Nachtrag",
                state.notes[breach.id].orEmpty(),
                { value -> viewModel.edit { it.copy(notes = it.notes + (breach.id to value)) } },
                lines = 3..10
            )
            AsyncOasisButton("Nachtrag speichern", { viewModel.addNote(breach) }, kind = OasisButtonKind.Secondary, fullWidth = false)
            OasisButton("Abschließen", onConfirmClose, kind = OasisButtonKind.Secondary, fullWidth = false)
        }
        state.stepErrors[breach.id]?.let { FieldError(it) }
        Disclosure("Verlauf (${breach.log.size})") {
            breach.log.forEach { entry ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = OasisColors.Ink2)) { append("${StaffDate.withUhr(entry.at)} · ${entry.by}: ") }
                        append(entry.text)
                    },
                    style = OasisType.Caption,
                    color = OasisColors.Ink
                )
            }
        }
    }
}
