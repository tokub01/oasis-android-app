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
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
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
            else -> null
        }
    }
}

private val StepsSaver = listSaver<SnapshotStateList<SignedOutStep>, String>(
    save = { steps -> steps.map { it.code } },
    restore = { saved -> mutableStateListOf<SignedOutStep>().apply { addAll(saved.mapNotNull(SignedOutStep::decode)) } }
)

/**
 * Screens for signed-out visitors: sign-in (/anmelden), two-factor step (/zwei-faktor-anmeldung), the lost second
 * factor (/zwei-faktor-hilfe), registration (/testen) and forgot password (/passwort-vergessen), on a small stack of
 * their own. Signing in replaces the whole flow (the session state changes), and its forms are cleared with it.
 */
@Composable
internal fun SignedOutFlow() {
    val navigator = LocalNavigator.current
    val steps = rememberSaveable(saver = StepsSaver) { mutableStateListOf() }
    val pop: () -> Unit = { if (steps.isNotEmpty()) steps.removeAt(steps.lastIndex) }
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
                    onRegister = { steps.add(SignedOutStep.Register) }
                )

                SignedOutStep.Register -> RegisterScreen(onSignIn = { steps.clear() }, onLegal = { path -> legal(baseUrl + path) })

                SignedOutStep.ForgotPassword -> ForgotPasswordScreen(onBackToSignIn = { steps.clear() })

                SignedOutStep.TwoFactorRecovery -> TwoFactorRecoveryScreen()

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
        VerifyEmailContent(viewModel.email, ui, viewModel::check, viewModel::resend, onSettings, viewModel::signOut)
    }
}

@Composable
internal fun VerifyEmailContent(
    email: String?,
    ui: VerifyEmailUiState,
    onCheck: suspend () -> Unit,
    onResend: suspend () -> Unit,
    onSettings: () -> Unit,
    onSignOut: suspend () -> Unit
) {
    AuthCard("Fast geschafft.", "Bitte bestätige deine E-Mail-Adresse.") {
        Text(
            buildAnnotatedString {
                append("Wir haben dir einen Link an ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(email ?: "deine Adresse") }
                append(" geschickt. Sobald du ihn öffnest, startet dein Test.")
            },
            style = OasisType.Body,
            color = OasisColors.Ink2
        )
        if (ui.sent) Banner("Wir haben dir einen neuen Link geschickt.", kind = BannerKind.Success)
        if (ui.checked) {
            Banner(
                "Deine Adresse ist noch nicht bestätigt. Öffne den Link in der E-Mail und versuch es dann noch einmal.",
                kind = BannerKind.Info
            )
        }
        ui.error?.let { Banner(it.userMessage) }
        AsyncOasisButton("Ich habe bestätigt", onCheck, modifier = Modifier.testTag("verify.check"))
        AsyncOasisButton(
            "Link noch einmal schicken",
            onResend,
            modifier = Modifier.testTag("verify.resend"),
            kind = OasisButtonKind.Secondary
        )
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LinkText("Adresse ändern oder Konto löschen", onSettings)
            SignOutText(onSignOut)
        }
    }
}

@Composable
private fun SignOutText(onSignOut: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    OasisTextButton("Abmelden", { scope.launch { onSignOut() } }, Modifier.testTag("verify.signout"))
}
