package de.velwebsolutions.oasis.account.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.ui.components.AuthCard
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.account.ui.components.ScopedViewModels
import de.velwebsolutions.oasis.account.ui.components.webUrl
import de.velwebsolutions.oasis.core.auth.LoginCodeChannel
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.ScreenChrome
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/** The screens a signed-out visitor moves between (SignedOutView.Step on iOS). Legal pages open in a Custom Tab. */
sealed interface SignedOutStep {
    /** How the step is kept while the activity is recreated. */
    val code: String

    /** Registration (/testen). */
    data object Register : SignedOutStep {
        override val code = "register"
    }

    /** „Passwort vergessen?“. */
    data object ForgotPassword : SignedOutStep {
        override val code = "forgot"
    }

    /** „Kein Zugriff auf deine Codes?“. */
    data object TwoFactorRecovery : SignedOutStep {
        override val code = "recovery"
    }

    /** „Mit Code anmelden“ by [channel], with what the sign-in form had in its login field. */
    data class LoginCode(val channel: LoginCodeChannel, val login: String) : SignedOutStep {
        override val code get() = "login-code:${channel.wire}:$login"
    }

    /** The second factor of a sign-in that asked for it. */
    data class TwoFactor(val challenge: String) : SignedOutStep {
        override val code get() = "two-factor:$challenge"
    }

    companion object {
        /** The step of a kept [code]; unknown codes (an older app) are dropped. */
        fun decode(raw: String): SignedOutStep? = when {
            raw == "register" -> Register
            raw == "forgot" -> ForgotPassword
            raw == "recovery" -> TwoFactorRecovery
            raw.startsWith("two-factor:") -> TwoFactor(raw.removePrefix("two-factor:"))
            raw.startsWith("login-code:") -> loginCode(raw.removePrefix("login-code:"))
            else -> null
        }

        private fun loginCode(kept: String): LoginCode? {
            val channel = LoginCodeChannel.entries.firstOrNull { it.wire == kept.substringBefore(':') } ?: return null
            return LoginCode(channel, kept.substringAfter(':'))
        }
    }
}

private val StepsSaver = listSaver<SnapshotStateList<SignedOutStep>, String>(
    save = { steps -> steps.map { it.code } },
    restore = { saved -> mutableStateListOf<SignedOutStep>().apply { addAll(saved.mapNotNull(SignedOutStep::decode)) } }
)

/**
 * Screens for signed-out visitors: sign-in (/anmelden), the code sign-in (/anmelden/code), two-factor step
 * (/zwei-faktor-anmeldung), the lost second factor (/zwei-faktor-hilfe), registration (/testen) and forgot password
 * (/passwort-vergessen), on a small stack of their own. Signing in replaces the whole flow (the session state changes), and its forms are cleared with it.
 */
@Composable
internal fun SignedOutFlow() {
    val navigator = LocalNavigator.current
    val steps = rememberSaveable(saver = StepsSaver) { mutableStateListOf() }
    // Only offered while a step is open (system back and the arrow of the top bar).
    val pop: () -> Unit = { steps.removeAt(steps.lastIndex) }
    val legal: (String) -> Unit = { url -> navigator.open(WebRoute(url)) }
    BackHandler(enabled = steps.isNotEmpty(), onBack = pop)
    ScopedViewModels {
        val step = steps.lastOrNull()
        CompositionLocalProvider(LocalScreenChrome provides if (step == null) ScreenChrome() else ScreenChrome(onBack = pop)) {
            val baseUrl = webUrl("")
            when (step) {
                null -> SignInScreen(
                    onTwoFactor = { steps.add(SignedOutStep.TwoFactor(it)) },
                    onForgotPassword = { steps.add(SignedOutStep.ForgotPassword) },
                    onRegister = { steps.add(SignedOutStep.Register) },
                    onLoginCode = { channel, login -> steps.add(SignedOutStep.LoginCode(channel, login)) }
                )

                SignedOutStep.Register -> RegisterScreen(onSignIn = { steps.clear() }, onLegal = { path -> legal(baseUrl + path) })

                SignedOutStep.ForgotPassword -> ForgotPasswordScreen(onBackToSignIn = { steps.clear() })

                SignedOutStep.TwoFactorRecovery -> TwoFactorRecoveryScreen()

                is SignedOutStep.LoginCode -> LoginCodeScreen(
                    step.channel,
                    step.login,
                    onTwoFactor = { steps.add(SignedOutStep.TwoFactor(it)) },
                    onPasswordSignIn = { steps.clear() }
                )

                // The remaining step: the second factor.
                else -> {
                    val twoFactor = step as SignedOutStep.TwoFactor
                    TwoFactorScreen(twoFactor.challenge, onLost = { steps.add(SignedOutStep.TwoFactorRecovery) })
                }
            }
        }
    }
}

/**
 * Shown to signed-in members whose e-mail address is not confirmed yet (/email/bestaetigen); member screens require a
 * verified address like the web's `verified` middleware. The settings stay reachable, so a mistyped new address can be
 * corrected or the account deleted, as on the web.
 */
@Composable
internal fun VerifyEmailContentScreen(onSettings: () -> Unit) {
    val viewModel: VerifyEmailViewModel = hiltViewModel()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OasisScreen(title = "E-Mail bestätigen") {
        VerifyEmailContent(
            viewModel.email,
            ui,
            VerifyEmailActions(
                viewModel::setCode,
                viewModel::submitCode,
                viewModel::check,
                viewModel::resend,
                onSettings,
                viewModel::signOut
            )
        )
    }
}

@Composable
internal fun VerifyEmailContent(email: String?, ui: VerifyEmailUiState, actions: VerifyEmailActions) {
    AuthCard("Fast geschafft.", "Bitte bestätige deine E-Mail-Adresse.") {
        Text(
            buildAnnotatedString {
                append("Wir haben dir einen Code und einen Link an ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(email ?: "deine Adresse") }
                append(" geschickt. Gib den Code hier ein oder öffne den Link – dann startet dein Test.")
            },
            style = OasisType.Body,
            color = OasisColors.Ink2
        )
        if (ui.sent) Banner("Wir haben dir einen neuen Code und Link geschickt.", kind = BannerKind.Success)
        val codeError = ui.error?.message("code")
        OasisTextField(
            "Code aus der E-Mail",
            ui.code,
            actions.onCode,
            modifier = Modifier.testTag("verify.code"),
            error = codeError,
            keyboardOptions = OasisKeyboard.OneTimeCode
        )
        AsyncOasisButton("Bestätigen", actions.onSubmitCode, modifier = Modifier.testTag("verify.submit"), enabled = ui.canSubmitCode)
        if (ui.checked) {
            Banner(
                "Deine Adresse ist noch nicht bestätigt. Gib den Code ein oder öffne den Link in der E-Mail und versuch es dann noch einmal.",
                kind = BannerKind.Info
            )
        }
        if (ui.error != null && codeError == null) Banner(ui.error.userMessage)
        AsyncOasisButton(
            "Ich habe den Link geöffnet",
            actions.onCheck,
            modifier = Modifier.testTag("verify.check"),
            kind = OasisButtonKind.Secondary
        )
        AsyncOasisButton(
            "Neuen Code schicken",
            actions.onResend,
            modifier = Modifier.testTag("verify.resend"),
            kind = OasisButtonKind.Secondary
        )
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LinkText("Adresse ändern oder Konto löschen", actions.onSettings)
            SignOutText(actions.onSignOut)
        }
    }
}

/** What the e-mail confirmation can do; the screen connects it to the view model. */
internal class VerifyEmailActions(
    val onCode: (String) -> Unit,
    val onSubmitCode: suspend () -> Unit,
    val onCheck: suspend () -> Unit,
    val onResend: suspend () -> Unit,
    val onSettings: () -> Unit,
    val onSignOut: suspend () -> Unit
)

@Composable
private fun SignOutText(onSignOut: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    OasisTextButton("Abmelden", { scope.launch { onSignOut() } }, Modifier.testTag("verify.signout"))
}
