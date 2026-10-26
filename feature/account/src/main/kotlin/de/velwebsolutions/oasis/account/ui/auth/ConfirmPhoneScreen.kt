package de.velwebsolutions.oasis.account.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.account.api.PhoneNumberStatus
import de.velwebsolutions.oasis.account.platform.SmsCodeConsent
import de.velwebsolutions.oasis.account.ui.components.AuthCard
import de.velwebsolutions.oasis.account.ui.components.LinkText
import de.velwebsolutions.oasis.account.ui.components.OneTimeCodeField
import de.velwebsolutions.oasis.account.ui.settings.PhoneNumberUiState
import de.velwebsolutions.oasis.account.ui.settings.PhoneNumberViewModel
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/** „Handynummer bestätigen“ (/app/handynummer): the step between the e-mail confirmation and "Mitglied werden". */
@Composable
internal fun ConfirmPhoneContentScreen(viewModel: PhoneNumberViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    OasisScreen(title = "Handynummer bestätigen") {
        LoadableView(ui.state, onRetry = viewModel::load) { status ->
            ConfirmPhoneContent(
                ui,
                status,
                ConfirmPhoneActions(
                    onNumber = viewModel::setNumber,
                    onCode = viewModel::setCode,
                    onSend = viewModel::sendCode,
                    onConfirm = viewModel::confirm,
                    onSignOut = viewModel::signOut
                )
            )
        }
    }
}

/** What the phone step can do; the screen connects it to the view model. */
internal class ConfirmPhoneActions(
    val onNumber: (String) -> Unit,
    val onCode: (String) -> Unit,
    val onSend: suspend () -> Unit,
    val onConfirm: suspend () -> Unit,
    val onSignOut: suspend () -> Unit
)

/**
 * The web's ConfirmPhone: a number, then the six digits of its SMS. While a code waits, the number field stays for
 * another number or a new code, with an outlined button.
 */
@Composable
internal fun ConfirmPhoneContent(ui: PhoneNumberUiState, status: PhoneNumberStatus, actions: ConfirmPhoneActions) {
    val scope = rememberCoroutineScope()
    val pending = status.pending
    AuthCard("Noch ein Schritt.", "Bitte bestätige deine Handynummer.") {
        Text(
            "Jedes Oasis-Konto gehört zu einer Handynummer. So bleibt Oasis frei von Zweitkonten und Bots. Wir schicken dir einen Code per SMS, deine Nummer sieht niemand außer dir.",
            style = OasisType.Body,
            color = OasisColors.Ink2
        )
        ui.message?.let { Banner(it, kind = BannerKind.Success) }
        ui.error?.let { Banner(it) }
        if (pending != null) {
            OneTimeCodeField(
                "Code aus der SMS an $pending",
                ui.code,
                actions.onCode,
                onDone = { scope.launch { actions.onConfirm() } },
                modifier = Modifier.testTag("confirmphone.code"),
                error = ui.fieldErrors["code"]
            )
            SmsCodeConsent(active = true, onCode = actions.onCode)
            AsyncOasisButton(
                "Bestätigen",
                actions.onConfirm,
                modifier = Modifier.testTag("confirmphone.confirm"),
                enabled = ui.code.isNotBlank()
            )
        }
        if (status.smsAvailable) {
            OasisTextField(
                if (pending != null) "Andere Nummer oder neuer Code" else "Handynummer",
                ui.number,
                actions.onNumber,
                modifier = Modifier.testTag("confirmphone.number"),
                error = ui.fieldErrors["phone_number"],
                hint = "Zum Beispiel 0176 12345678.",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
            )
            AsyncOasisButton(
                "Code per SMS schicken",
                actions.onSend,
                modifier = Modifier.testTag("confirmphone.send"),
                kind = if (pending != null) OasisButtonKind.Secondary else OasisButtonKind.Primary,
                enabled = ui.number.isNotBlank()
            )
        } else {
            Banner("SMS sind gerade nicht verfügbar. Bitte versuche es später noch einmal.", kind = BannerKind.Info)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            LinkText("Abmelden", { scope.launch { actions.onSignOut() } }, Modifier.testTag("confirmphone.signout"), bold = false)
        }
    }
}
