package de.velwebsolutions.oasis.account.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.core.auth.Registration
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.ConsentCheck
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val Steps = listOf(
    "Konto anlegen",
    "E-Mail-Adresse mit dem Code bestätigen",
    "Beitrag wählen – bezahlt wird über Google Play"
)

private val GermanDay = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Registration, step 1 of 3 of /testen. */
@Composable
internal fun RegisterScreen(onSignIn: () -> Unit, onLegal: (String) -> Unit) {
    val viewModel: RegisterViewModel = hiltViewModel()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OasisScreen(title = "Konto erstellen") {
        RegisterContent(ui, viewModel::edit, viewModel::setBirthDate, viewModel::submit, onSignIn, onLegal)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegisterContent(
    ui: RegisterUiState,
    onEdit: ((Registration) -> Registration) -> Unit,
    onBirthDate: (LocalDate) -> Unit,
    onSubmit: suspend () -> Unit,
    onSignIn: () -> Unit,
    onLegal: (String) -> Unit
) {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var pickingBirthDate by remember { mutableStateOf(false) }
    val error = ui.error
    val registration = ui.registration
    val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val shape = RoundedCornerShape(OasisRadius.XL.dp)
            Column(
                Modifier.fillMaxWidth().background(OasisColors.Paper, shape).border(1.dp, OasisColors.PaperLine, shape).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Schritt 1 von 3 · Konto", style = OasisType.SmallBold, color = OasisColors.Ink2)
                    Row(Modifier.widthIn(max = 280.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(OasisColors.Night, OasisColors.DuneLine, OasisColors.DuneLine).forEach { color ->
                            Box(Modifier.weight(1f).height(6.dp).background(color, CircleShape))
                        }
                    }
                }
                Text(
                    "Werde Teil der Oase.",
                    style = OasisType.display(34),
                    color = OasisColors.Night,
                    modifier = Modifier.semantics { heading() }
                )
                Text("Werbefrei und ohne Algorithmus, getragen von den Mitgliedern.", style = OasisType.Body, color = OasisColors.Ink2)
                val formError = error?.message("form")
                when {
                    formError != null -> Banner(formError)

                    // Also for the birth date checked here: its message sits far below, next to the button.
                    ui.birthDateMissing || error?.fieldMessages?.isNotEmpty() == true -> Banner("Bitte prüfe die markierten Felder.")

                    error != null -> Banner(error.userMessage)
                }
                OasisTextField(
                    "Wie sollen andere dich nennen?",
                    registration.displayName,
                    { value -> onEdit { it.copy(displayName = value) } },
                    modifier = Modifier.testTag("register.displayName"),
                    prompt = "z. B. Lena Berger",
                    error = error?.message("display_name"),
                    keyboardOptions = OasisKeyboard.Sentences.copy(imeAction = ImeAction.Next),
                    keyboardActions = next
                )
                OasisTextField(
                    "Benutzername",
                    registration.username,
                    { value -> onEdit { it.copy(username = value) } },
                    modifier = Modifier.testTag("register.username"),
                    prompt = "@lena",
                    error = error?.message("username"),
                    hint = "3 bis 30 Zeichen: Kleinbuchstaben, Ziffern und Unterstrich (_)",
                    keyboardOptions = OasisKeyboard.Username.copy(imeAction = ImeAction.Next),
                    keyboardActions = next
                )
                OasisTextField(
                    "E-Mail-Adresse",
                    registration.email,
                    { value -> onEdit { it.copy(email = value) } },
                    modifier = Modifier.testTag("register.email"),
                    prompt = "name@beispiel.tld",
                    error = error?.message("email"),
                    keyboardOptions = OasisKeyboard.Email.copy(imeAction = ImeAction.Next),
                    keyboardActions = next
                )
                OasisTextField(
                    "Passwort",
                    registration.password,
                    { value -> onEdit { it.copy(password = value) } },
                    modifier = Modifier.testTag("register.password"),
                    error = error?.message("password"),
                    hint = "Mindestens 10 Zeichen",
                    isSecure = true,
                    keyboardOptions = OasisKeyboard.Password.copy(imeAction = ImeAction.Next),
                    keyboardActions = next
                )
                OasisTextField(
                    "Passwort wiederholen",
                    registration.passwordConfirmation,
                    { value -> onEdit { it.copy(passwordConfirmation = value) } },
                    modifier = Modifier.testTag("register.passwordConfirmation"),
                    isSecure = true,
                    keyboardOptions = OasisKeyboard.Password.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { scope.launch { onSubmit() } })
                )
                BirthDateField(ui, error?.message("birth_date")) {
                    focus.clearFocus()
                    pickingBirthDate = true
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConsentCheck(
                        registration.acceptsTerms,
                        { checked -> onEdit { it.copy(acceptsTerms = checked) } },
                        Modifier.testTag("register.terms")
                    ) {
                        Text(
                            "Ich akzeptiere die Community-Regeln und die AGB. Wie wir mit deinen Daten umgehen, steht in der Datenschutzerklärung.",
                            style = OasisType.Small,
                            color = OasisColors.Ink
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LinkText("Community-Regeln", { onLegal("community-regeln") })
                        LinkText("AGB", { onLegal("agb") })
                        LinkText("Datenschutz", { onLegal("datenschutz") })
                    }
                    error?.message("terms")?.let { FieldError(it) }
                }
                AsyncOasisButton(
                    "Konto erstellen",
                    onSubmit,
                    modifier = Modifier.testTag("register.submit"),
                    kind = OasisButtonKind.Accent,
                    enabled = ui.form != null
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = OasisColors.Ink2, modifier = Modifier.size(18.dp))
                    Text(
                        "Oasis gibt es nur mit Mitgliedschaft – ohne Werbung, ohne Verkauf deiner Daten. Den Beitrag wählst du nach der " +
                            "Bestätigung deiner Adresse.",
                        style = OasisType.Small,
                        color = OasisColors.Ink2
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Schon dabei?", style = OasisType.Body, color = OasisColors.Ink2)
                    LinkText("Anmelden", onSignIn, bold = false)
                }
            }
            NightCard()
        }
    }
    if (pickingBirthDate) {
        BirthDateDialog(ui.birthDate, onDismiss = { pickingBirthDate = false }) {
            pickingBirthDate = false
            onBirthDate(it)
        }
    }
}

/** The date as a field that opens the calendar; empty until chosen. */
@Composable
private fun BirthDateField(ui: RegisterUiState, serverError: String?, onPick: () -> Unit) {
    val message = if (ui.birthDateMissing) RegisterViewModel.BIRTH_DATE_REQUIRED else serverError
    val shape = RoundedCornerShape(OasisRadius.XS.dp)
    val text = ui.birthDate?.format(GermanDay)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Geburtsdatum", style = OasisType.SmallBold, color = OasisColors.Ink)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(OasisColors.Field, shape)
                .border(if (message == null) 1.dp else 2.dp, if (message == null) OasisColors.FieldLine else OasisColors.SunText, shape)
                .clickable(role = Role.Button, onClick = onPick)
                .semantics {
                    contentDescription = "Geburtsdatum"
                    stateDescription = text ?: "nicht gewählt"
                }
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("register.birthDate"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text ?: "Geburtsdatum wählen",
                style = OasisType.Body,
                color = if (text ==
                    null
                ) {
                    OasisColors.Muted
                } else {
                    OasisColors.Ink
                },
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = OasisColors.Night)
        }
        if (message != null) {
            FieldError(message)
        } else {
            Text(
                "Oasis ist ab 16. Für eine Mitgliedschaft unter 18 brauchst du die Zustimmung deiner Eltern.",
                style = OasisType.Caption,
                color = OasisColors.Muted
            )
        }
    }
}

/** Days up to [today] (UTC midnight in milliseconds): a birth date lies in the past. */
@OptIn(ExperimentalMaterial3Api::class)
internal class PastDates(private val today: Long) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today
}

/** The calendar for the birth date; "Übernehmen" sets it. It starts at a plausible age, never after today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDateDialog(current: LocalDate?, onDismiss: () -> Unit, onTake: (LocalDate) -> Unit) {
    val start = current ?: LocalDate.now().minusYears(20)
    val today = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = PastDates(today)
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            OasisTextButton("Übernehmen", {
                // The picker starts with a date selected and cannot deselect it.
                onTake(Instant.ofEpochMilli(state.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate())
            }, Modifier.testTag("register.birthDate.take"))
        },
        dismissButton = { OasisTextButton("Abbrechen", onDismiss) },
        colors = DatePickerDefaults.colors(containerColor = OasisColors.Paper)
    ) {
        DatePicker(
            state,
            title = {
                Text(
                    "Geburtsdatum",
                    style = OasisType.heading(),
                    color = OasisColors.Night,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp)
                )
            },
            colors = DatePickerDefaults.colors(
                containerColor = OasisColors.Paper,
                selectedDayContainerColor = OasisColors.Night,
                todayDateBorderColor = OasisColors.Night,
                todayContentColor = OasisColors.Night
            )
        )
    }
}

/** How joining works, on Nachtwasser. */
@Composable
private fun NightCard() {
    Column(
        Modifier.fillMaxWidth().background(OasisColors.Night, RoundedCornerShape(OasisRadius.XL.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("So geht es los", style = OasisType.display(26), color = OasisColors.OnNight)
        Steps.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).border(2.dp, OasisColors.OnNight, CircleShape).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", style = OasisType.Caption.copy(fontWeight = FontWeight.Bold), color = OasisColors.OnNight)
                }
                Text(item, style = OasisType.Body, color = OasisColors.OnNight)
            }
        }
        HorizontalDivider(color = OasisColors.NightLine)
        Text("DANACH", style = OasisType.Eyebrow, color = OasisColors.OnNightMuted)
        Text(
            "Schritt 2: Interessen und Oasen wählen\nSchritt 3: Leute finden oder deine Gruppe einladen",
            style = OasisType.Body,
            color = OasisColors.OnNight2
        )
    }
}
