package de.velwebsolutions.oasis.account.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.platform.SmsCodeConsent
import de.velwebsolutions.oasis.account.ui.components.AuthCard
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.account.ui.components.OneTimeCodeField
import de.velwebsolutions.oasis.core.auth.LoginCodeChannel
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/** „Mit Code anmelden“ (/anmelden/code): the login, then the six digits from the e-mail or SMS. */
@Composable
internal fun LoginCodeScreen(channel: LoginCodeChannel, login: String, onTwoFactor: (String) -> Unit) {
    val viewModel = hiltViewModel<LoginCodeViewModel, LoginCodeViewModel.Factory>(
        key = "login-code:${channel.wire}:$login",
        creationCallback = creation(channel, login)
    )
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OasisScreen(title = "Anmelden") {
        LoginCodeContent(
            ui,
            LoginCodeActions(
                onLogin = viewModel::setLogin,
                onCode = viewModel::setCode,
                onSend = viewModel::send,
                onSignIn = { viewModel.signIn()?.let(onTwoFactor) },
                onChangeLogin = viewModel::changeLogin
            )
        )
    }
}

/** Creates the screen's view model for [channel] and [login] (only asked when there is none yet). */
private fun creation(channel: LoginCodeChannel, login: String): (LoginCodeViewModel.Factory) -> LoginCodeViewModel =
    { factory -> factory.create(channel, login) }

/** What the code sign-in can do; the screen connects it to the view model. */
internal class LoginCodeActions(
    val onLogin: (String) -> Unit,
    val onCode: (String) -> Unit,
    val onSend: suspend () -> Unit,
    val onSignIn: suspend () -> Unit,
    val onChangeLogin: () -> Unit
)

@Composable
internal fun LoginCodeContent(ui: LoginCodeUiState, actions: LoginCodeActions) {
    val scope = rememberCoroutineScope()
    val error = ui.error
    val bySms = ui.channel == LoginCodeChannel.Sms
    AuthCard(
        if (ui.sent) "Code eingeben" else "Mit Code anmelden",
        when {
            !ui.sent -> "Kein Passwort nötig: Wir schicken dir einen Code."
            bySms -> "Schau auf dein Handy, der Code gilt 10 Minuten."
            else -> "Schau in dein Postfach, der Code gilt 10 Minuten."
        }
    ) {
        if (!ui.sent) {
            if (error != null && error.message("login") == null && error.message("channel") == null) Banner(error.userMessage)
            OasisTextField(
                "E-Mail oder Benutzername",
                ui.login,
                actions.onLogin,
                modifier = Modifier.testTag("logincode.login"),
                error = error?.message("login"),
                keyboardOptions = OasisKeyboard.Username.copy(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { scope.launch { actions.onSend() } })
            )
            error?.message("channel")?.let { FieldError(it) }
            AsyncOasisButton("Code schicken", actions.onSend, modifier = Modifier.testTag("logincode.send"), enabled = ui.canSend)
        } else {
            Banner(if (bySms) SENT_BY_SMS else SENT_BY_MAIL, kind = BannerKind.Success)
            Text(
                buildAnnotatedString {
                    append("Anmeldung für ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = OasisColors.Night)) { append(ui.login.trim()) }
                },
                style = OasisType.Body,
                color = OasisColors.Ink2
            )
            if (error != null && error.message("code") == null) Banner(error.userMessage)
            OneTimeCodeField(
                "Sechsstelliger Code",
                ui.code,
                actions.onCode,
                onDone = { scope.launch { actions.onSignIn() } },
                modifier = Modifier.testTag("logincode.code"),
                error = error?.message("code")
            )
            SmsCodeConsent(active = bySms, onCode = actions.onCode)
            AsyncOasisButton("Anmelden", actions.onSignIn, modifier = Modifier.testTag("logincode.signin"), enabled = ui.canSignIn)
            AsyncOasisButton(
                "Neuen Code schicken",
                actions.onSend,
                modifier = Modifier.testTag("logincode.resend"),
                kind = OasisButtonKind.Secondary
            )
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                LinkText(
                    "Andere E-Mail-Adresse oder anderen Benutzernamen eingeben",
                    actions.onChangeLogin,
                    Modifier.testTag("logincode.change")
                )
            }
        }
    }
}

/** The neutral answers after sending, as on the web (LoginCode::SENT, LoginCode::SENT_SMS). */
internal const val SENT_BY_MAIL =
    "Wenn es ein Konto mit diesen Angaben gibt, haben wir dir gerade einen Code per E-Mail geschickt. Er gilt 10 Minuten."

internal const val SENT_BY_SMS =
    "Wenn es ein Konto mit diesen Angaben und einer bestätigten Handynummer gibt, haben wir dir gerade einen Code per SMS geschickt. Er gilt 10 Minuten."
