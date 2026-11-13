package de.velwebsolutions.oasis.account.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.core.auth.Registration
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.ConsentCheck
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButton
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

private val Steps = listOf(
    "Konto anlegen",
    "E-Mail-Adresse mit dem Code bestätigen",
    "Handynummer per SMS bestätigen",
    "Beitrag wählen"
)

private val GermanDay = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** What the registration pages ask of their view model. */
internal class RegisterActions(
    val onDisplayName: (String) -> Unit,
    val onUsername: (String) -> Unit,
    val onTakeSuggestion: (String) -> Unit,
    val onEdit: ((Registration) -> Registration) -> Unit,
    val onBirthDate: (LocalDate) -> Unit,
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onSubmit: suspend () -> Unit
) {
    companion object {
        /** Pages without a view model (UI tests). */
        val None = RegisterActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * Registration, the first of the four steps (account, e-mail address, mobile number, contribution) of /testen, in three
 * small pages (docs/oasis-accounts.md, „Registrierung: Schritt 1 in drei kleinen Seiten“).
 */
@Composable
internal fun RegisterScreen(onSignIn: () -> Unit, onLegal: (String) -> Unit) {
    val viewModel: RegisterViewModel = hiltViewModel()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        RegisterActions(
            onDisplayName = viewModel::setDisplayName,
            onUsername = viewModel::setUsername,
            onTakeSuggestion = viewModel::takeSuggestion,
            onEdit = viewModel::edit,
            onBirthDate = viewModel::setBirthDate,
            onNext = viewModel::next,
            onBack = viewModel::back,
            onSubmit = viewModel::submit
        )
    }
    // Back on page 1b or 1c goes one page back, with everything typed kept.
    BackHandler(enabled = ui.page != RegisterPage.Name, onBack = viewModel::back)
    OasisScreen(title = "Konto erstellen") {
        RegisterContent(ui, actions, onSignIn, onLegal)
    }
}

@Composable
internal fun RegisterContent(ui: RegisterUiState, actions: RegisterActions, onSignIn: () -> Unit, onLegal: (String) -> Unit) {
    var pickingBirthDate by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
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
                Progress(ui.page)
                Text(
                    ui.page.title,
                    style = OasisType.display(30),
                    color = OasisColors.Night,
                    modifier = Modifier.semantics { heading() }.testTag("register.title")
                )
                if (ui.page == RegisterPage.Name) {
                    Text("Werbefrei und ohne Algorithmus, getragen von den Mitgliedern.", style = OasisType.Body, color = OasisColors.Ink2)
                }
                FormBanner(ui.error)
                when (ui.page) {
                    RegisterPage.Name -> NamePage(ui, actions)

                    RegisterPage.SignIn -> SignInPage(ui, actions)

                    // RegisterPage.Finish.
                    else -> FinishPage(ui, actions, onLegal) {
                        focus.clearFocus()
                        pickingBirthDate = true
                    }
                }
                PageButtons(ui.page, actions)
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
            actions.onBirthDate(it)
        }
    }
}

/** „Schritt 1 von 4 · Konto“, the bar of the four steps and the three dots of the pages of step 1. */
@Composable
private fun Progress(page: RegisterPage) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Schritt 1 von ${Steps.size} · Konto", style = OasisType.SmallBold, color = OasisColors.Ink2)
        Row(Modifier.widthIn(max = 280.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Steps.indices.forEach { index ->
                val color = if (index == 0) OasisColors.Night else OasisColors.DuneLine
                Box(Modifier.weight(1f).height(6.dp).background(color, CircleShape))
            }
        }
        val pages = RegisterPage.entries.size
        Row(
            Modifier.clearAndSetSemantics { contentDescription = "Seite ${page.ordinal + 1} von $pages" }.testTag("register.page"),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RegisterPage.entries.forEach { dot ->
                val current = dot == page
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (current) OasisColors.Night else Color.Transparent, CircleShape)
                        .border(1.5.dp, OasisColors.Night, CircleShape)
                )
            }
        }
    }
}

/** A refusal that belongs to no field shows on top; refused fields are marked at the field. */
@Composable
private fun FormBanner(error: ApiError?) {
    val formError = error?.message("form")
    when {
        formError != null -> Banner(formError)
        error?.fieldMessages?.isNotEmpty() == true -> Banner("Bitte prüfe die markierten Felder.")
        error != null -> Banner(error.userMessage)
    }
}

/** 1a: the display name, and the username with its suggestion and live check. */
@Composable
private fun NamePage(ui: RegisterUiState, actions: RegisterActions) {
    val focus = LocalFocusManager.current
    val registration = ui.registration
    OasisTextField(
        "Dein Name",
        registration.displayName,
        actions.onDisplayName,
        modifier = Modifier.testTag("register.displayName"),
        prompt = "z. B. Lena Berger",
        error = ui.fieldError("display_name"),
        keyboardOptions = OasisKeyboard.Sentences.copy(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
    )
    val check = ui.usernameCheck
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OasisTextField(
            "Benutzername",
            registration.username,
            actions.onUsername,
            modifier = Modifier.testTag("register.username"),
            prompt = "@lena",
            error = ui.fieldError("username") ?: (check as? UsernameCheck.Invalid)?.message,
            hint = "3 bis 30 Zeichen: Kleinbuchstaben, Ziffern und Unterstrich (_)".takeIf { check == null },
            keyboardOptions = OasisKeyboard.Username.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onNext() })
        )
        if (ui.fieldError("username") == null) UsernameLine(check, actions.onTakeSuggestion)
    }
}

/** „@mira ist frei.“ or „@mira ist schon vergeben – wie wäre es mit @mira2?“ (the suggestion is a link). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsernameLine(check: UsernameCheck?, onTake: (String) -> Unit) {
    when (check) {
        is UsernameCheck.Free -> Text(
            "@${check.username} ist frei.",
            style = OasisType.Caption,
            color = OasisColors.Ink2,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("register.username.free")
        )

        is UsernameCheck.Taken -> {
            val suggestion = check.suggestion
            FlowRow(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("register.username.taken"),
                verticalArrangement = Arrangement.Center
            ) {
                if (suggestion == null) {
                    Text("@${check.username} ist schon vergeben.", style = OasisType.Caption, color = OasisColors.SunText)
                } else {
                    Text("@${check.username} ist schon vergeben – wie wäre es mit ", style = OasisType.Caption, color = OasisColors.SunText)
                    Text(
                        "@$suggestion",
                        style = OasisType.Caption.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
                        color = OasisColors.Night,
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "@$suggestion nehmen") { onTake(suggestion) }
                            .testTag("register.username.suggestion")
                    )
                    Text("?", style = OasisType.Caption, color = OasisColors.SunText)
                }
            }
        }

        // Nothing checked yet, or the format is wrong (shown at the field).
        else -> {}
    }
}

/** 1b: e-mail address and the password, entered once, with the eye and the live checklist. */
@Composable
private fun SignInPage(ui: RegisterUiState, actions: RegisterActions) {
    val focus = LocalFocusManager.current
    val registration = ui.registration
    var shown by rememberSaveable { mutableStateOf(false) }
    OasisTextField(
        "E-Mail-Adresse",
        registration.email,
        { value -> actions.onEdit { it.copy(email = value) } },
        modifier = Modifier.testTag("register.email"),
        prompt = "name@beispiel.tld",
        error = ui.fieldError("email"),
        keyboardOptions = OasisKeyboard.Email.copy(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OasisTextField(
            "Passwort",
            registration.password,
            { value -> actions.onEdit { it.copy(password = value) } },
            modifier = Modifier.testTag("register.password"),
            error = ui.fieldError("password"),
            isSecure = !shown,
            keyboardOptions = OasisKeyboard.Password.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onNext() }),
            trailing = {
                IconButton(onClick = { shown = !shown }, modifier = Modifier.testTag("register.password.toggle")) {
                    Icon(
                        if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (shown) "Passwort verbergen" else "Passwort anzeigen",
                        tint = OasisColors.Night
                    )
                }
            }
        )
        val ticked = ui.passwordLongEnough
        Row(
            Modifier.semantics(mergeDescendants = true) {
                stateDescription = if (ticked) "erfüllt" else "noch nicht erfüllt"
            }.testTag("register.password.rule"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (ticked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (ticked) OasisColors.Night else OasisColors.Muted,
                modifier = Modifier.size(18.dp)
            )
            Text("Mindestens ${RegisterViewModel.PASSWORD_MIN_LENGTH} Zeichen", style = OasisType.Small, color = OasisColors.Ink)
        }
        Text(
            "Später kannst du einen Passkey einrichten und dich ganz ohne Passwort anmelden.",
            style = OasisType.Caption,
            color = OasisColors.Muted
        )
    }
}

/** 1c: birth date (18+) and the consent; „Konto anlegen“ follows. */
@Composable
private fun FinishPage(ui: RegisterUiState, actions: RegisterActions, onLegal: (String) -> Unit, onPickBirthDate: () -> Unit) {
    val registration = ui.registration
    BirthDateField(ui, ui.fieldError("birth_date"), onPickBirthDate)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ConsentCheck(
            registration.acceptsTerms,
            { checked -> actions.onEdit { it.copy(acceptsTerms = checked) } },
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
        ui.fieldError("terms")?.let { FieldError(it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = OasisColors.Ink2, modifier = Modifier.size(18.dp))
        Text(
            "Mildcrowd gibt es nur mit Mitgliedschaft – ohne Werbung, ohne Verkauf deiner Daten. Den Beitrag wählst du, nachdem " +
                "du deine Adresse und deine Handynummer bestätigt hast.",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
    }
}

/** „Weiter“ (1a, 1b) or „Konto anlegen“ (1c), with „Zurück“ from page 1b on. */
@Composable
private fun PageButtons(page: RegisterPage, actions: RegisterActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (page != RegisterPage.Name) {
            OasisButton(
                "Zurück",
                actions.onBack,
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                modifier = Modifier.testTag("register.back")
            )
        }
        if (page == RegisterPage.Finish) {
            // Also without the bot-trap token (offline when the screen opened): the view model fetches one then.
            AsyncOasisButton(
                "Konto anlegen",
                actions.onSubmit,
                modifier = Modifier.testTag("register.submit"),
                kind = OasisButtonKind.Accent
            )
        } else {
            OasisButton("Weiter", actions.onNext, kind = OasisButtonKind.Accent, modifier = Modifier.testTag("register.next"))
        }
    }
}

/** The date as a field that opens the calendar; empty until chosen. */
@Composable
private fun BirthDateField(ui: RegisterUiState, message: String?, onPick: () -> Unit) {
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
                "Mildcrowd ist ab 18.",
                style = OasisType.Caption,
                color = OasisColors.Muted
            )
        }
    }
}

/** Days up to [latest] (UTC midnight in milliseconds): a birth date at least 18 years back (Oasis is 18+). */
@OptIn(ExperimentalMaterial3Api::class)
internal class PastDates(private val latest: Long) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest
}

/**
 * The calendar for the birth date; "Übernehmen" sets it. It starts at a plausible age and offers no day less than 18
 * years back. Typed in by keyboard (the pencil), an empty, incomplete or too recent date selects nothing, so
 * "Übernehmen" waits for a valid one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDateDialog(current: LocalDate?, onDismiss: () -> Unit, onTake: (LocalDate) -> Unit) {
    val start = current ?: LocalDate.now().minusYears(20)
    val latest = LocalDate.now().minusYears(RegisterViewModel.MIN_AGE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = PastDates(latest)
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            OasisTextButton(
                "Übernehmen",
                { state.selectedDateMillis?.let { onTake(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
                Modifier.testTag("register.birthDate.take"),
                enabled = state.selectedDateMillis != null
            )
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
            "Oasen und Menschen finden – wann es dir passt\nDeine Gruppe einladen",
            style = OasisType.Body,
            color = OasisColors.OnNight2
        )
    }
}
