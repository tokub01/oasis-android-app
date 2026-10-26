package de.velwebsolutions.oasis.account.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.account.api.Passkey
import de.velwebsolutions.oasis.account.api.PhoneNumberStatus
import de.velwebsolutions.oasis.account.platform.SmsCodeConsent
import de.velwebsolutions.oasis.account.platform.rememberPasskeyAuthenticator
import de.velwebsolutions.oasis.account.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.account.ui.components.GermanDate
import de.velwebsolutions.oasis.account.ui.components.OneTimeCodeField
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/** „Passkeys“: the list with rename and remove, and „Passkey hinzufügen“ through Credential Manager. */
@Composable
internal fun PasskeysSection(ui: PasskeysUiState, viewModel: PasskeysViewModel) {
    val passkeys = rememberPasskeyAuthenticator()
    // Outlives the dialogs, so closing one does not cancel the request that closed it.
    val scope = rememberCoroutineScope()
    DisposableEffect(passkeys) {
        viewModel.attach(passkeys)
        onDispose { viewModel.detach(passkeys) }
    }
    SectionCard("Passkeys") {
        Text(
            "Mit einem Passkey meldest du dich per Fingerabdruck, Gesicht oder Displaysperre an – ohne Passwort.",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
        LoadableView(ui.state, onRetry = viewModel::load) { list ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (list.isEmpty()) Text("Du hast noch keinen Passkey.", style = OasisType.Small, color = OasisColors.Muted)
                list.forEach { passkey -> PasskeyRow(passkey, viewModel) }
            }
        }
        if (passkeys.isAvailable) {
            AsyncOasisButton(
                "Passkey hinzufügen",
                viewModel::add,
                modifier = Modifier.testTag("passkeys.add"),
                kind = OasisButtonKind.Secondary,
                fullWidth = false
            )
        } else {
            Banner(
                "Auf diesem Gerät gehen Passkeys nicht. Du brauchst eine Displaysperre und aktuelle Google Play-Dienste.",
                kind = BannerKind.Info
            )
        }
    }
    ui.naming?.let {
        NameDialog(
            "Wie soll der Passkey heißen?",
            viewModel.suggestedName,
            ui.fieldErrors["name"],
            "Ohne Namen",
            onSave = { name -> scope.launch { viewModel.saveNew(name) } },
            onDismiss = { scope.launch { viewModel.saveNew("") } }
        )
    }
    ui.renaming?.let {
        NameDialog(
            "Passkey umbenennen",
            it.name,
            ui.fieldErrors["name"],
            "Abbrechen",
            onSave = { name -> scope.launch { viewModel.rename(name) } },
            onDismiss = viewModel::cancelRename
        )
    }
    ui.removing?.let { passkey ->
        ConfirmDialog(
            "Diesen Passkey entfernen? Du kannst dich dann nicht mehr damit anmelden.",
            "Entfernen",
            onConfirm = { scope.launch { viewModel.remove(passkey) } },
            onDismiss = viewModel::cancelRemove
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PasskeyRow(passkey: Passkey, viewModel: PasskeysViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.S.dp))
            .padding(14.dp)
            .testTag("passkey.${passkey.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(passkey.name, style = OasisType.BodyBold, color = OasisColors.Ink)
        passkey.authenticator?.let { Text(it, style = OasisType.Small, color = OasisColors.Ink2) }
        passkey.createdAt?.let { Text("Erstellt am ${GermanDate.dayLong(it)}", style = OasisType.Caption, color = OasisColors.Muted) }
        Text(
            passkey.lastUsedAt?.let { "Zuletzt genutzt am ${GermanDate.dayLong(it)}" } ?: "Noch nicht genutzt",
            style = OasisType.Caption,
            color = OasisColors.Muted
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            OasisTextButton("Umbenennen", { viewModel.startRename(passkey) }, Modifier.testTag("passkey.${passkey.id}.rename"))
            OasisTextButton("Entfernen", {
                viewModel.askRemove(passkey)
            }, Modifier.testTag("passkey.${passkey.id}.remove"), color = OasisColors.SunText)
        }
    }
}

/**
 * A Material dialog with one name field: naming a new passkey (dismissing saves it without a name, so the passkey on the
 * device is never left without its server side), or renaming one.
 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    error: String?,
    dismissLabel: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = OasisType.CardTitle, color = OasisColors.Night) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Der Name hilft dir, den Passkey später wiederzuerkennen.", style = OasisType.Small, color = OasisColors.Ink2)
                OasisTextField("Name", name, { name = it }, modifier = Modifier.testTag("passkey.name"), error = error)
            }
        },
        confirmButton = { OasisTextButton("Speichern", { onSave(name) }, Modifier.testTag("passkey.name.save")) },
        dismissButton = { OasisTextButton(dismissLabel, onDismiss, Modifier.testTag("passkey.name.dismiss")) },
        containerColor = OasisColors.Paper
    )
}

/**
 * „Handynummer“: the masked number and its state, then number → „Code senden“ → code → „Bestätigen“; „Ändern“ and
 * „Löschen“. Without SMS on the server only a hint (and deleting a saved number) is left.
 */
@Composable
internal fun PhoneNumberSection(ui: PhoneNumberUiState, viewModel: PhoneNumberViewModel) {
    // Outlives the dialog, so closing it does not cancel the deletion.
    val scope = rememberCoroutineScope()
    SectionCard("Handynummer") {
        Text("Optional: Damit kannst du dir Anmeldecodes per SMS schicken lassen.", style = OasisType.Small, color = OasisColors.Ink2)
        LoadableView(ui.state, onRetry = viewModel::load) { status -> PhoneNumberContent(ui, status, viewModel) }
    }
    if (ui.confirmingRemoval) {
        ConfirmDialog(
            "Deine Handynummer löschen? Anmeldecodes bekommst du dann nur noch per E-Mail.",
            "Löschen",
            onConfirm = { scope.launch { viewModel.remove() } },
            onDismiss = viewModel::cancelRemoval
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneNumberContent(ui: PhoneNumberUiState, status: PhoneNumberStatus, viewModel: PhoneNumberViewModel) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!status.smsAvailable) {
            Banner("SMS ist gerade nicht verfügbar. Anmeldecodes bekommst du per E-Mail.", kind = BannerKind.Info)
        }
        status.phoneNumber?.let { number ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(number, style = OasisType.BodyBold, color = OasisColors.Ink, modifier = Modifier.testTag("phone.number"))
                Pill("Bestätigt", tone = PillTone.Spring)
            }
        }
        val pending = status.pending
        if (pending != null && status.smsAvailable && !ui.editing) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pending, style = OasisType.BodyBold, color = OasisColors.Ink, modifier = Modifier.testTag("phone.pending"))
                Pill("Wartet auf Bestätigung", tone = PillTone.Sun)
            }
            OneTimeCodeField(
                "Code aus der SMS an $pending",
                ui.code,
                viewModel::setCode,
                onDone = { scope.launch { viewModel.confirm() } },
                modifier = Modifier.testTag("phone.code"),
                error = ui.fieldErrors["code"]
            )
            SmsCodeConsent(active = true, onCode = viewModel::setCode)
            AsyncOasisButton("Bestätigen", viewModel::confirm, modifier = Modifier.testTag("phone.confirm"), fullWidth = false)
        }
        if (ui.showsNumberField) {
            OasisTextField(
                if (status.phoneNumber == null) "Handynummer" else "Neue Handynummer",
                ui.number,
                viewModel::setNumber,
                modifier = Modifier.testTag("phone.field"),
                error = ui.fieldErrors["phone_number"],
                hint = "Zum Beispiel 0176 12345678. Wir schicken dir einen Code per SMS.",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncOasisButton(
                    "Code senden",
                    viewModel::sendCode,
                    modifier = Modifier.testTag("phone.send"),
                    fullWidth = false,
                    enabled = ui.number.isNotBlank()
                )
                if (ui.editing) {
                    OasisTextButton(
                        "Abbrechen",
                        viewModel::cancelEditing,
                        Modifier.padding(top = 12.dp).testTag("phone.cancel")
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.smsAvailable && !ui.editing && (status.phoneNumber != null || status.pending != null)) {
                OasisButton(
                    "Ändern",
                    viewModel::startEditing,
                    modifier = Modifier.testTag("phone.edit"),
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false
                )
            }
            if (status.phoneNumber != null) {
                OasisButton(
                    "Löschen",
                    viewModel::askRemoval,
                    modifier = Modifier.testTag("phone.remove"),
                    kind = OasisButtonKind.Destructive,
                    fullWidth = false
                )
            }
        }
    }
}
