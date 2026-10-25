package de.velwebsolutions.oasis.account.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.api.AccountSettings
import de.velwebsolutions.oasis.account.api.TwoFactorState
import de.velwebsolutions.oasis.account.ui.components.AccountPage
import de.velwebsolutions.oasis.account.ui.components.DataExportButton
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.account.ui.components.QrCodeView
import de.velwebsolutions.oasis.account.ui.components.webUrl
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.ConsentCheck
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/**
 * Einstellungen (/app/einstellungen): Profil, Passwort, Zwei-Faktor-Anmeldung, Deine Daten, Konto löschen – plus
 * „Hilfe & Rechtliches“ with the website's pages and „Abmelden“. Reachable without a verified address and while
 * locked, like the web route.
 */
@Composable
internal fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.accountDeleted) { if (ui.accountDeleted) viewModel.signOut() }
    OasisScreen(
        title = "Einstellungen",
        message = ui.message,
        error = ui.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        SettingsContent(
            ui = ui,
            canSeeWelcome = viewModel.canSeeWelcome,
            viewModel = viewModel,
            open = { navigator.open(it) }
        )
        if (ui.pendingAction != null) PasswordConfirmSheet(ui, viewModel)
    }
}

@Composable
internal fun SettingsContent(ui: SettingsUiState, canSeeWelcome: Boolean, viewModel: SettingsViewModel, open: (AppRoute) -> Unit) {
    AccountPage(spacing = 24) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageHeader("Einstellungen", eyebrow = "Konto", lead = "Profil, Passwort, Anmeldung und deine Daten an einem Ort.")
            if (canSeeWelcome) LinkText("Neu hier? Einstieg noch einmal ansehen", { open(AccountRoute.Welcome) }, bold = false)
        }
        LoadableView(ui.state, onRetry = viewModel::load) { settings ->
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                ProfileSection(ui, settings, viewModel)
                PasswordSection(ui, viewModel)
                TwoFactorSection(ui, settings, viewModel)
                SectionCard(
                    "Deine Daten",
                    detail =
                        "Lade deine Daten herunter: Konto, Profil, Tropfen mit Bildern, wem du folgst, Oasen und Gemerktes – als ZIP-Datei. Verschlüsselte Chat-Nachrichten können wir nicht lesen und deshalb nicht exportieren."
                ) {
                    DataExportButton(viewModel::export, viewModel::showError)
                }
                DeleteSection(ui, viewModel, open)
                HelpAndLegalSection(open)
                AsyncOasisButton(
                    "Abmelden",
                    viewModel::signOut,
                    modifier = Modifier.testTag("settings.signout"),
                    kind = OasisButtonKind.Secondary
                )
            }
        }
    }
}

@Composable
private fun ProfileSection(ui: SettingsUiState, settings: AccountSettings, viewModel: SettingsViewModel) {
    SectionCard("Profil") {
        OasisTextField(
            "Anzeigename",
            ui.displayName,
            { value -> viewModel.edit { it.copy(displayName = value) } },
            modifier = Modifier.testTag("settings.displayName"),
            error = ui.fieldErrors["display_name"]
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Benutzername", style = OasisType.SmallBold, color = OasisColors.Ink)
            Text(
                "@${settings.username}",
                style = OasisType.BodyBold,
                color = OasisColors.Ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.XS.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
            Text(
                "Dein Benutzername bleibt fest, damit Links zu deinem Profil nicht ins Leere führen.",
                style = OasisType.Caption,
                color = OasisColors.Muted
            )
        }
        OasisTextField(
            "E-Mail-Adresse",
            ui.email,
            { value -> viewModel.edit { it.copy(email = value) } },
            modifier = Modifier.testTag("settings.email"),
            error = ui.fieldErrors["email"],
            hint = "Wenn du sie änderst, bestätigst du die neue Adresse per Link.",
            keyboardOptions = OasisKeyboard.Email
        )
        if (!settings.emailVerified) {
            Banner("Deine E-Mail-Adresse ist noch nicht bestätigt. Öffne den Link, den wir dir geschickt haben.", kind = BannerKind.Info)
        }
        AsyncOasisButton("Profil speichern", viewModel::saveProfile, modifier = Modifier.testTag("settings.saveProfile"), fullWidth = false)
    }
}

@Composable
private fun PasswordSection(ui: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard("Passwort") {
        OasisTextField(
            "Aktuelles Passwort",
            ui.currentPassword,
            { value -> viewModel.edit { it.copy(currentPassword = value) } },
            error = ui.fieldErrors["current_password"],
            isSecure = true
        )
        OasisTextField(
            "Neues Passwort",
            ui.newPassword,
            { value -> viewModel.edit { it.copy(newPassword = value) } },
            error = ui.fieldErrors["password"],
            hint = "Mindestens 10 Zeichen",
            isSecure = true
        )
        OasisTextField(
            "Neues Passwort wiederholen",
            ui.newPasswordConfirmation,
            { value -> viewModel.edit { it.copy(newPasswordConfirmation = value) } },
            isSecure = true
        )
        AsyncOasisButton("Passwort ändern", viewModel::changePassword, fullWidth = false)
    }
}

/**
 * Zwei-Faktor-Anmeldung: off → „Einschalten“; pending → QR code, setup key and the code; active → recovery codes
 * (shown once), new codes, switch off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TwoFactorSection(ui: SettingsUiState, settings: AccountSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    SectionCard("Zwei-Faktor-Anmeldung") {
        when (settings.twoFactor.state) {
            TwoFactorState.Phase.Active -> {
                Text(
                    "Aktiv. Bei der Anmeldung fragen wir zusätzlich nach einem Code aus deiner Authenticator-App.",
                    style = OasisType.Small,
                    color = OasisColors.Ink2
                )
                val codes = ui.recoveryCodes
                if (!codes.isNullOrEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Bewahre diese Wiederherstellungscodes sicher auf. Jeder funktioniert einmal.",
                            style = OasisType.SmallBold,
                            color = OasisColors.Night
                        )
                        SelectionContainer {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.XS.dp))
                                    .padding(14.dp)
                                    .testTag("settings.recoveryCodes"),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                codes.chunked(2).forEach { pair ->
                                    Row {
                                        pair.forEach {
                                            Text(
                                                it,
                                                style = OasisType.Digits.copy(fontSize = OasisType.Small.fontSize),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (pair.size == 1) Text("", modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        Row(
                            Modifier
                                .heightIn(min = 44.dp)
                                .clickable(role = Role.Button) {
                                    val send = Intent(
                                        Intent.ACTION_SEND
                                    ).setType("text/plain").putExtra(Intent.EXTRA_TEXT, codes.joinToString("\n"))
                                    runCatching {
                                        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                },
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null, tint = OasisColors.Night)
                            Text("Codes sichern", style = OasisType.SmallBold, color = OasisColors.Night)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncOasisButton(
                        "Neue Wiederherstellungscodes",
                        { viewModel.run(ProtectedAction.RegenerateCodes) },
                        modifier = Modifier.testTag("settings.regenerateCodes"),
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false
                    )
                    AsyncOasisButton(
                        "Ausschalten",
                        { viewModel.run(ProtectedAction.DisableTwoFactor) },
                        modifier = Modifier.testTag("settings.twoFactorDisable"),
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false
                    )
                }
            }

            TwoFactorState.Phase.Pending -> {
                Text(
                    "Scanne den QR-Code mit deiner Authenticator-App und gib den angezeigten Code ein.",
                    style = OasisType.Small,
                    color = OasisColors.Ink2
                )
                settings.twoFactor.otpauthUrl?.let { url ->
                    QrCodeView(url)
                    OasisTextButton(
                        "In der Authenticator-App öffnen",
                        { openAuthenticator(context, url) { viewModel.showError(it) } },
                        color = OasisColors.SpringText
                    )
                }
                settings.twoFactor.setupKey?.let { key ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Oder gib diesen Schlüssel ein:", style = OasisType.Caption, color = OasisColors.Muted)
                        SelectionContainer {
                            Text(
                                key,
                                style = OasisType.Digits.copy(fontSize = OasisType.Small.fontSize),
                                modifier = Modifier.testTag("settings.setupKey")
                            )
                        }
                    }
                }
                OasisTextField(
                    "Sechsstelliger Code",
                    ui.twoFactorCode,
                    { value -> viewModel.edit { it.copy(twoFactorCode = value) } },
                    modifier = Modifier.testTag("settings.twoFactorCode"),
                    error = ui.fieldErrors["code"],
                    keyboardOptions = OasisKeyboard.OneTimeCode
                )
                AsyncOasisButton(
                    "Bestätigen",
                    { viewModel.run(ProtectedAction.ConfirmTwoFactor) },
                    modifier = Modifier.testTag("settings.twoFactorConfirm"),
                    fullWidth = false
                )
            }

            // The remaining phase: off.
            else -> {
                Text(
                    "Schütze dein Konto zusätzlich mit einem Code aus einer Authenticator-App.",
                    style = OasisType.Small,
                    color = OasisColors.Ink2
                )
                AsyncOasisButton(
                    "Einschalten",
                    { viewModel.run(ProtectedAction.EnableTwoFactor) },
                    modifier = Modifier.testTag("settings.twoFactorEnable"),
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false
                )
            }
        }
    }
}

/**
 * Hands the `otpauth://` link to the authenticator app (iOS: `openURL`). A Custom Tab cannot open it; without an app
 * for it the member hears so and can still scan the code or type the key.
 */
private fun openAuthenticator(context: Context, url: String, failed: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        failed("Auf diesem Gerät ist keine Authenticator-App installiert. Scanne den QR-Code oder gib den Schlüssel ein.")
    }
}

@Composable
private fun DeleteSection(ui: SettingsUiState, viewModel: SettingsViewModel, open: (AppRoute) -> Unit) {
    val cancelUrl = webUrl("vertraege-kuendigen")
    SectionCard("Konto löschen", tone = PanelTone.Warning) {
        Text(
            "Dein Konto und alle Daten dazu werden endgültig gelöscht. Eine laufende Mitgliedschaft kündigst du vorher über „Verträge hier kündigen“.",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
        LinkText("Verträge hier kündigen", { open(WebRoute(cancelUrl)) })
        ConsentCheck(ui.deleteConfirmed, { checked ->
            viewModel.edit { it.copy(deleteConfirmed = checked) }
        }, Modifier.testTag("settings.deleteConfirm")) {
            Text("Ja, ich möchte mein Konto endgültig löschen.", style = OasisType.Small, color = OasisColors.Ink)
        }
        ui.fieldErrors["confirm"]?.let { FieldError(it) }
        AsyncOasisButton(
            "Konto endgültig löschen",
            { viewModel.run(ProtectedAction.DeleteAccount) },
            modifier = Modifier.testTag("settings.delete"),
            kind = OasisButtonKind.Destructive,
            enabled = ui.deleteConfirmed
        )
    }
}

/** „Hilfe & Rechtliches“: the glossary and the website's help, legal and contract pages. */
@Composable
private fun HelpAndLegalSection(open: (AppRoute) -> Unit) {
    val pages: List<Triple<String, String, AppRoute>> = listOf(
        Triple("Hilfe", "hilfe", WebRoute(webUrl("hilfe"))),
        Triple("Begriffe erklärt", "begriffe", AccountRoute.Glossary),
        Triple("Community-Regeln", "community-regeln", WebRoute(webUrl("community-regeln"))),
        Triple("Datenschutz & Sicherheit", "sicherheit", WebRoute(webUrl("sicherheit"))),
        Triple("Datenschutzerklärung", "datenschutz", WebRoute(webUrl("datenschutz"))),
        Triple("AGB", "agb", WebRoute(webUrl("agb"))),
        Triple("Widerrufsbelehrung", "widerrufsbelehrung", WebRoute(webUrl("widerrufsbelehrung"))),
        Triple("Barrierefreiheit", "barrierefreiheit", WebRoute(webUrl("barrierefreiheit"))),
        Triple("Impressum", "impressum", WebRoute(webUrl("impressum"))),
        Triple("Inhalte melden (ohne Konto)", "inhalte-melden", WebRoute(webUrl("inhalte-melden"))),
        Triple("Verträge hier kündigen", "vertraege-kuendigen", WebRoute(webUrl("vertraege-kuendigen"))),
        Triple("Vertrag widerrufen", "vertrag-widerrufen", WebRoute(webUrl("vertrag-widerrufen")))
    )
    SectionCard("Hilfe & Rechtliches") {
        Column {
            pages.forEach { (title, id, route) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { open(route) }.testTag("help.$id"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, style = OasisType.Body, color = OasisColors.Ink, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = OasisColors.Muted)
                }
            }
        }
    }
}

/** „Kurz bestätigen“ (/passwort-bestaetigen) before two-factor changes and the deletion; valid three hours on this device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordConfirmSheet(ui: SettingsUiState, viewModel: SettingsViewModel) {
    var password by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = viewModel::cancelPasswordConfirmation,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OasisColors.Sand
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kurz bestätigen", style = OasisType.display(30), color = OasisColors.Night, modifier = Modifier.weight(1f))
                OasisTextButton("Abbrechen", viewModel::cancelPasswordConfirmation)
            }
            Text("Bitte gib zur Sicherheit dein Passwort ein.", style = OasisType.Body, color = OasisColors.Ink2)
            OasisTextField(
                "Passwort",
                password,
                { password = it },
                modifier = Modifier.testTag("confirm.password"),
                error = ui.fieldErrors["password"],
                isSecure = true
            )
            AsyncOasisButton(
                "Bestätigen",
                { if (viewModel.confirmPassword(password)) password = "" },
                modifier = Modifier.testTag("confirm.submit"),
                enabled = password.isNotEmpty()
            )
        }
    }
}
