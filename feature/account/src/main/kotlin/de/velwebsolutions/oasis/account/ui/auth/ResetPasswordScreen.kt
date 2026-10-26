package de.velwebsolutions.oasis.account.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.ui.components.AuthCard
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.core.auth.AuthApi
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** „Neues Passwort“: the address from the link, the new password twice; [done] holds the server's answer. */
data class ResetPasswordUiState(
    val email: String,
    val password: String = "",
    val confirmation: String = "",
    val done: String? = null,
    val error: ApiError? = null
) {
    val canSubmit: Boolean get() = email.isNotBlank() && password.isNotEmpty() && confirmation.isNotEmpty()
}

/**
 * The link of the reset mail, opened in the app (/passwort-zuruecksetzen/{token}?email=…): sets the new password
 * without a sign-in, with the web's rules and answers (Fortify's reset form); afterwards the member signs in.
 */
@HiltViewModel(assistedFactory = ResetPasswordViewModel.Factory::class)
class ResetPasswordViewModel @AssistedInject constructor(
    @Assisted("token") private val token: String,
    @Assisted("email") email: String,
    private val auth: AuthApi
) : ViewModel() {
    private val mutableUi = MutableStateFlow(ResetPasswordUiState(email))
    val ui: StateFlow<ResetPasswordUiState> = mutableUi.asStateFlow()

    fun setEmail(value: String) = mutableUi.update { it.copy(email = value) }

    fun setPassword(value: String) = mutableUi.update { it.copy(password = value) }

    fun setConfirmation(value: String) = mutableUi.update { it.copy(confirmation = value) }

    /** „Passwort speichern“; refusals stay at `email` or `password`. */
    suspend fun save() {
        val current = ui.value
        if (!current.canSubmit) return
        mutableUi.update { it.copy(error = null) }
        val error = attempt {
            val message = auth.resetPassword(token, current.email.trim(), current.password, current.confirmation)
            mutableUi.update { it.copy(done = message, password = "", confirmation = "") }
        }
        mutableUi.update { it.copy(error = error) }
    }

    /** Creates the view model for the token and the address of the link. */
    @AssistedFactory
    interface Factory {
        fun create(@Assisted("token") token: String, @Assisted("email") email: String): ResetPasswordViewModel
    }
}

/** „Neues Passwort“ (Fortify's reset-password page). */
@Composable
internal fun ResetPasswordScreen(token: String, email: String, onSignIn: () -> Unit) {
    val viewModel = hiltViewModel<ResetPasswordViewModel, ResetPasswordViewModel.Factory>(
        key = "reset:$token",
        creationCallback = resetCreation(token, email)
    )
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OasisScreen(title = "Passwort") {
        ResetPasswordContent(
            ui,
            ResetPasswordActions(viewModel::setEmail, viewModel::setPassword, viewModel::setConfirmation, viewModel::save, onSignIn)
        )
    }
}

/** Creates the screen's view model for the link's [token] and [email] (only asked when there is none yet). */
private fun resetCreation(token: String, email: String): (ResetPasswordViewModel.Factory) -> ResetPasswordViewModel =
    { factory -> factory.create(token, email) }

/** What the reset form can do; the screen connects it to the view model. */
internal class ResetPasswordActions(
    val onEmail: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onConfirmation: (String) -> Unit,
    val onSave: suspend () -> Unit,
    val onSignIn: () -> Unit
)

@Composable
internal fun ResetPasswordContent(ui: ResetPasswordUiState, actions: ResetPasswordActions) {
    val error = ui.error
    AuthCard("Neues Passwort", "Wähle ein Passwort mit mindestens 10 Zeichen.") {
        val done = ui.done
        if (done != null) {
            Banner(done, kind = BannerKind.Success)
            AsyncOasisButton("Zur Anmeldung", { actions.onSignIn() }, modifier = Modifier.testTag("reset.signin"))
        } else {
            ResetPasswordForm(ui, error, actions)
        }
    }
}

@Composable
private fun ResetPasswordForm(ui: ResetPasswordUiState, error: ApiError?, actions: ResetPasswordActions) {
    if (error != null && error.message("email") == null && error.message("password") == null) Banner(error.userMessage)
    OasisTextField(
        "E-Mail-Adresse",
        ui.email,
        actions.onEmail,
        modifier = Modifier.testTag("reset.email"),
        error = error?.message("email"),
        keyboardOptions = OasisKeyboard.Email
    )
    OasisTextField(
        "Neues Passwort",
        ui.password,
        actions.onPassword,
        modifier = Modifier.testTag("reset.password"),
        error = error?.message("password"),
        isSecure = true
    )
    OasisTextField(
        "Neues Passwort wiederholen",
        ui.confirmation,
        actions.onConfirmation,
        modifier = Modifier.testTag("reset.confirmation"),
        isSecure = true
    )
    AsyncOasisButton("Passwort speichern", actions.onSave, modifier = Modifier.testTag("reset.submit"), enabled = ui.canSubmit)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LinkText("Zurück zur Anmeldung", actions.onSignIn, Modifier.testTag("reset.back"))
    }
}
