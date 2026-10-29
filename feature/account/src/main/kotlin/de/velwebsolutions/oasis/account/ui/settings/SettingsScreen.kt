package de.velwebsolutions.oasis.account.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import java.util.UUID

/**
 * Einstellungen (/app/einstellungen): Profil, Passwort, Zwei-Faktor-Anmeldung, Passkeys, Handynummer, Deine Daten,
 * Konto löschen – plus „Hilfe & Rechtliches“ with the website's pages and „Abmelden“. Reachable without a verified
 * address and while locked, like the web route.
 */
@Composable
internal fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    passkeys: PasskeysViewModel = hiltViewModel(),
    phone: PhoneNumberViewModel = hiltViewModel()
) {
    val navigator = LocalNavigator.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val passkeysUi by passkeys.ui.collectAsStateWithLifecycle()
    val phoneUi by phone.ui.collectAsStateWithLifecycle()
    val passkeysPrompt by passkeys.prompt.collectAsStateWithLifecycle()
    val phonePrompt by phone.prompt.collectAsStateWithLifecycle()
    LaunchedEffect(ui.accountDeleted) { if (ui.accountDeleted) viewModel.signOut() }
    OasisScreen(
        title = "Einstellungen",
        message = ui.message ?: passkeysUi.message ?: phoneUi.message,
        error = ui.error ?: passkeysUi.error ?: phoneUi.error,
        onDismissMessage = {
            viewModel.dismissMessage()
            passkeys.dismissMessage()
            phone.dismissMessage()
        },
        onDismissError = {
            viewModel.dismissError()
            passkeys.dismissError()
            phone.dismissError()
        }
    ) {
        SettingsContent(
            ui = ui,
            canSeeWelcome = viewModel.canSeeWelcome,
            viewModel = viewModel,
            open = { navigator.open(it) }
        ) {
            PasskeysSection(passkeysUi, passkeys)
            PhoneNumberSection(phoneUi, phone)
        }
        if (ui.pendingAction != null) {
            PasswordConfirmSheet(ui.fieldErrors["password"], viewModel::confirmPassword, viewModel::cancelPasswordConfirmation)
        }
        passkeysPrompt?.let { PasswordConfirmSheet(it.error, passkeys::confirmPassword, passkeys::cancelPasswordConfirmation) }
        phonePrompt?.let { PasswordConfirmSheet(it.error, phone::confirmPassword, phone::cancelPasswordConfirmation) }
    }
}

/** The settings page; [signInWays] are the blocks for passkeys and the mobile number below the two-factor block. */
@Composable
internal fun SettingsContent(
    ui: SettingsUiState,
    canSeeWelcome: Boolean,
    viewModel: SettingsViewModel,
    open: (AppRoute) -> Unit,
    signInWays: @Composable () -> Unit
) {
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
                signInWays()
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
 * (shown once), new codes, switch off. While the shared secret or the codes are on screen, the window is kept out of
 * screenshots, screen recordings and the recent apps, and they are copied only as sensitive and for a minute
 * ([copySecret]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TwoFactorSection(ui: SettingsUiState, settings: AccountSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    if (settings.twoFactor.state == TwoFactorState.Phase.Pending || !ui.recoveryCodes.isNullOrEmpty()) SecureWindow()
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            SecretAction("Codes sichern", Icons.Outlined.Share) {
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, codes.joinToString("\n"))
                                runCatching {
                                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                            SecretAction("Codes kopieren", Icons.Outlined.ContentCopy, Modifier.testTag("settings.copyCodes")) {
                                copySecret(context, codes.joinToString("\n"))
                                viewModel.edit { it.copy(message = CODES_COPIED) }
                            }
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
                        Text(
                            key,
                            style = OasisType.Digits.copy(fontSize = OasisType.Small.fontSize),
                            modifier = Modifier.testTag("settings.setupKey")
                        )
                        SecretAction("Schlüssel kopieren", Icons.Outlined.ContentCopy, Modifier.testTag("settings.copySetupKey")) {
                            copySecret(context, key)
                            viewModel.edit { it.copy(message = KEY_COPIED) }
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

/** ClipDescription.EXTRA_IS_SENSITIVE of Android 13, by its name: older versions ignore it. */
internal const val SENSITIVE_CLIP = "android.content.extra.IS_SENSITIVE"

/** The mark of one copied secret in its clip's description, so the app finds it again without reading the clipboard. */
private const val SECRET_CLIP_ID = "de.velwebsolutions.oasis.extra.SECRET_CLIP_ID"

/** How long a copied secret stays on the clipboard: enough to paste it into the authenticator or password manager. */
internal const val SECRET_CLIP_MILLIS = 60_000L

/** The confirmation of „Schlüssel kopieren“ ([SECRET_CLIP_MILLIS]). */
internal const val KEY_COPIED = "Der Schlüssel ist kopiert – nach einer Minute löschen wir ihn aus der Zwischenablage."

/** The confirmation of „Codes kopieren“ ([SECRET_CLIP_MILLIS]). */
internal const val CODES_COPIED = "Die Codes sind kopiert – nach einer Minute löschen wir sie aus der Zwischenablage."

/**
 * Copies a secret (the setup key, the recovery codes) marked as sensitive, so Android shows it masked in the clipboard
 * preview and keyboards keep it out of their clipboard history. After [SECRET_CLIP_MILLIS] it is taken off the clipboard
 * again, unless something else was copied since. The app tells that by a mark in the clip's description, never by
 * reading the clipboard's text: that would read what the member copied in another app meanwhile (Android 12 and later
 * even say so in a notice). Android 9 lets every app read the clipboard; from Android 10 on an app in the background
 * sees nothing of it, so the clipboard is emptied then. The text itself is not selectable, so nothing copies it without
 * the sensitive flag.
 */
internal fun copySecret(context: Context, text: String) {
    val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
    val id = UUID.randomUUID().toString()
    val clip = ClipData.newPlainText("Oasis", text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(SENSITIVE_CLIP, true)
        putString(SECRET_CLIP_ID, id)
    }
    clipboard.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
            val current = clipboard.primaryClipDescription
            if (current == null || current.extras?.getString(SECRET_CLIP_ID) == id) clipboard.clearPrimaryClip()
        }
    }, SECRET_CLIP_MILLIS)
}

/** A small action with an icon below a secret: „Codes sichern“, „Codes kopieren“, „Schlüssel kopieren“. */
@Composable
private fun SecretAction(text: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = OasisColors.Night)
        Text(text, style = OasisType.SmallBold, color = OasisColors.Night)
    }
}

/**
 * Keeps the window out of screenshots, screen recordings, screen sharing and the preview in the recent apps while it is
 * shown (FLAG_SECURE), like the view-once files of the chat. A window that was secure already stays so.
 */
@Composable
private fun SecureWindow() {
    val window = checkNotNull(LocalActivity.current).window
    DisposableEffect(window) {
        val wasSecure = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
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

/**
 * „Kurz bestätigen“ (/passwort-bestaetigen) before two-factor changes, the deletion, passkeys and the mobile number;
 * valid three hours on this device. [onConfirm] answers false on a wrong password ([error] then says why).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordConfirmSheet(error: String?, onConfirm: suspend (String) -> Boolean, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OasisColors.Sand
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kurz bestätigen", style = OasisType.display(30), color = OasisColors.Night, modifier = Modifier.weight(1f))
                OasisTextButton("Abbrechen", onCancel)
            }
            Text("Bitte gib zur Sicherheit dein Passwort ein.", style = OasisType.Body, color = OasisColors.Ink2)
            OasisTextField(
                "Passwort",
                password,
                { password = it },
                modifier = Modifier.testTag("confirm.password"),
                error = error,
                isSecure = true
            )
            AsyncOasisButton(
                "Bestätigen",
                { if (onConfirm(password)) password = "" },
                modifier = Modifier.testTag("confirm.submit"),
                enabled = password.isNotEmpty()
            )
        }
    }
}
