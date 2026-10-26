package de.velwebsolutions.oasis.account.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.platform.rememberPasskeyAuthenticator
import de.velwebsolutions.oasis.account.ui.components.AuthCard
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.core.auth.LoginCodeChannel
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/** Sign-in with e-mail or username and password, like /anmelden; without a password by code or passkey. */
@Composable
internal fun SignInScreen(
    onTwoFactor: (String) -> Unit,
    onForgotPassword: () -> Unit,
    onRegister: () -> Unit,
    onLoginCode: (LoginCodeChannel, String) -> Unit
) {
    val viewModel: SignInViewModel = hiltViewModel()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val passkeys = rememberPasskeyAuthenticator()
    OasisScreen(title = null, error = ui.notice, onDismissError = viewModel::dismissNotice) {
        SignInContent(
            ui = ui,
            actions = SignInActions(
                onLogin = viewModel::setLogin,
                onPassword = viewModel::setPassword,
                onSignIn = { viewModel.signIn()?.let(onTwoFactor) },
                onForgotPassword = onForgotPassword,
                onRegister = onRegister,
                onLoginCode = { channel -> onLoginCode(channel, ui.login) },
                onPasskey = if (ui.offersPasskeys && passkeys.isAvailable) ({ viewModel.signInWithPasskey(passkeys) }) else null
            )
        )
    }
}

/** What the sign-in form can do; [onPasskey] is null while passkeys are not on offer. */
internal class SignInActions(
    val onLogin: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onSignIn: suspend () -> Unit,
    val onForgotPassword: () -> Unit,
    val onRegister: () -> Unit,
    val onLoginCode: (LoginCodeChannel) -> Unit,
    val onPasskey: (suspend () -> Unit)?
)

@Composable
internal fun SignInContent(ui: SignInUiState, actions: SignInActions) {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val error = ui.error
    AuthCard("Willkommen zurück.", "Schön, dass du wieder da bist.") {
        if (error != null && error.message("login") == null && error.message("password") == null) Banner(error.userMessage)
        OasisTextField(
            "E-Mail oder Benutzername",
            ui.login,
            actions.onLogin,
            modifier = Modifier.testTag("login.field"),
            error = error?.message("login"),
            keyboardOptions = OasisKeyboard.Username.copy(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
        )
        OasisTextField(
            "Passwort",
            ui.password,
            actions.onPassword,
            modifier = Modifier.testTag("password.field"),
            error = error?.message("password"),
            isSecure = true,
            keyboardOptions = OasisKeyboard.Password.copy(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { scope.launch { actions.onSignIn() } })
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = OasisColors.Muted, modifier = Modifier.size(16.dp))
            Text("Du bleibst auf diesem Gerät angemeldet, bis du dich abmeldest.", style = OasisType.Caption, color = OasisColors.Muted)
        }
        AsyncOasisButton("Anmelden", actions.onSignIn, modifier = Modifier.testTag("signin.button"), enabled = ui.canSubmit)
        if (ui.passwordRefused) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.S.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("signin.codeHint")
            ) {
                Text("Passwort vergessen?", style = OasisType.Body, color = OasisColors.Ink)
                LinkText("Melde dich mit einem Code per E-Mail an.", { actions.onLoginCode(LoginCodeChannel.Email) })
            }
        }
        LinkText("Passwort vergessen?", actions.onForgotPassword, Modifier.testTag("forgot.button"))
        PasswordlessWays(ui, actions)
        HorizontalDivider(color = OasisColors.PaperLine)
        Text("Noch kein Konto?", style = OasisType.Body, color = OasisColors.Ink2)
        OasisButton(
            "Mitglied werden",
            actions.onRegister,
            modifier = Modifier.testTag("register.button"),
            kind = OasisButtonKind.Accent
        )
    }
}

/** „Mit Passkey anmelden“, „Ohne Passwort: Code per E-Mail“ and – while SMS is set up – „Code per SMS“. */
@Composable
private fun PasswordlessWays(ui: SignInUiState, actions: SignInActions) {
    HorizontalDivider(color = OasisColors.PaperLine)
    actions.onPasskey?.let { passkey ->
        AsyncOasisButton(
            "Mit Passkey anmelden",
            passkey,
            modifier = Modifier.testTag("signin.passkey"),
            kind = OasisButtonKind.Secondary
        )
    }
    Column {
        LinkText("Ohne Passwort: Code per E-Mail", { actions.onLoginCode(LoginCodeChannel.Email) }, Modifier.testTag("signin.codeEmail"))
        if (ui.offersSms) LinkText("Code per SMS", { actions.onLoginCode(LoginCodeChannel.Sms) }, Modifier.testTag("signin.codeSms"))
    }
}

/** Second factor (/zwei-faktor-anmeldung): the six-digit code from the authenticator app, or one recovery code. */
@Composable
internal fun TwoFactorScreen(challenge: String, onLost: () -> Unit) {
    val viewModel: TwoFactorViewModel = hiltViewModel(key = "two-factor:$challenge")
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OasisScreen(title = "Anmelden") {
        TwoFactorContent(ui, viewModel::setCode, viewModel::setRecoveryCode, { viewModel.submit(challenge) }, onLost)
    }
}

@Composable
internal fun TwoFactorContent(
    ui: TwoFactorUiState,
    onCode: (String) -> Unit,
    onRecoveryCode: (String) -> Unit,
    onSubmit: suspend () -> Unit,
    onLost: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val error = ui.error
    AuthCard("Noch ein Schritt", "Gib den Code aus deiner Authenticator-App ein.") {
        val challengeError = error?.message("challenge")
        if (challengeError != null) {
            Banner(challengeError)
        } else if (error != null && error.message("code") == null && error.message("recovery_code") == null) {
            Banner(error.userMessage)
        }
        OasisTextField(
            "Sechsstelliger Code",
            ui.code,
            onCode,
            modifier = Modifier.testTag("twofactor.code"),
            error = error?.message("code"),
            keyboardOptions = OasisKeyboard.OneTimeCode.copy(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { scope.launch { onSubmit() } })
        )
        OasisTextField(
            "Oder ein Wiederherstellungscode (optional)",
            ui.recoveryCode,
            onRecoveryCode,
            modifier = Modifier.testTag("twofactor.recovery"),
            error = error?.message("recovery_code"),
            keyboardOptions = OasisKeyboard.NoCapitalization.copy(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { scope.launch { onSubmit() } })
        )
        AsyncOasisButton("Anmelden", onSubmit, modifier = Modifier.testTag("twofactor.submit"), enabled = ui.canSubmit)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            LinkText("Kein Zugriff auf deine Codes?", onLost, Modifier.testTag("twofactor.lost"))
        }
    }
}

/** „Zwei-Faktor verloren“ (/zwei-faktor-hilfe), step 1 in the app: e-mail and password. */
@Composable
internal fun TwoFactorRecoveryScreen() {
    val viewModel: TwoFactorRecoveryViewModel = hiltViewModel()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val error = ui.error
    OasisScreen(title = "Zwei-Faktor-Hilfe") {
        AuthCard(
            "Kein Zugriff auf deine Codes?",
            "Du hast dein Handy und deine Wiederherstellungscodes verloren? So bekommst du dein Konto zurück."
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "1. Gib E-Mail-Adresse und Passwort deines Kontos ein.",
                    "2. Wir schicken dir einen Link. Öffne ihn, um zu bestätigen, dass dir die Adresse gehört.",
                    "3. Aus Sicherheitsgründen warten wir danach 72 Stunden. In dieser Zeit kannst du alles abbrechen.",
                    "4. Danach schaltest du die Zwei-Faktor-Anmeldung über den Link ab und richtest sie neu ein."
                ).forEach { Text(it, style = OasisType.Small, color = OasisColors.Ink2) }
            }
            ui.status?.let { Banner(it, kind = BannerKind.Success) }
            val formError = error?.message("form")
            if (formError != null) {
                Banner(formError)
            } else if (error != null && error.message("email") == null && error.message("password") == null) {
                Banner(error.userMessage)
            }
            OasisTextField(
                "E-Mail-Adresse",
                ui.email,
                viewModel::setEmail,
                modifier = Modifier.testTag("recovery.email"),
                error = error?.message("email"),
                keyboardOptions = OasisKeyboard.Email.copy(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
            )
            OasisTextField(
                "Passwort",
                ui.password,
                viewModel::setPassword,
                modifier = Modifier.testTag("recovery.password"),
                error = error?.message("password"),
                isSecure = true,
                keyboardOptions = OasisKeyboard.Password.copy(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { scope.launch { viewModel.submit() } })
            )
            AsyncOasisButton("Link anfordern", viewModel::submit, modifier = Modifier.testTag("recovery.submit"), enabled = ui.canSubmit)
        }
    }
}

/** „Passwort vergessen?“ – always the same neutral answer, like the web. */
@Composable
internal fun ForgotPasswordScreen(onBackToSignIn: () -> Unit) {
    val viewModel: ForgotPasswordViewModel = hiltViewModel()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val error = ui.error
    OasisScreen(title = "Passwort") {
        AuthCard("Passwort vergessen?", "Wir schicken dir einen Link, mit dem du ein neues Passwort setzt.") {
            ui.status?.let { Banner(it, kind = BannerKind.Success) }
            if (error != null && error.message("email") == null) Banner(error.userMessage)
            OasisTextField(
                "E-Mail-Adresse deines Kontos",
                ui.email,
                viewModel::setEmail,
                modifier = Modifier.testTag("forgot.email"),
                error = error?.message("email"),
                keyboardOptions = OasisKeyboard.Email.copy(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { scope.launch { viewModel.send() } })
            )
            AsyncOasisButton(
                "Link schicken",
                viewModel::send,
                modifier = Modifier.testTag("forgot.submit"),
                enabled = ui.email.isNotEmpty()
            )
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                LinkText("Zurück zur Anmeldung", onBackToSignIn)
            }
        }
    }
}
