package de.velwebsolutions.oasis.staff.ui.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisCard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.RoleUser
import de.velwebsolutions.oasis.staff.ui.components.OptionPicker
import de.velwebsolutions.oasis.staff.ui.components.StaffLoadable
import de.velwebsolutions.oasis.staff.ui.components.StaffPage
import de.velwebsolutions.oasis.staff.ui.components.StaffTitle

/** Role management for admins (web: livewire.moderation.roles). Only the operator account appoints admins. */
@Composable
fun RolesScreen(viewModel: RolesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OasisScreen(
        "Rollen",
        message = state.message,
        error = state.error,
        onDismissMessage = viewModel::dismissMessage,
        onDismissError = viewModel::dismissError
    ) {
        StaffLoadable(state.users, viewModel::load) { users ->
            StaffPage(viewModel::load, spacing = 12) {
                StaffTitle("Rollen")
                Text(
                    "Moderation und Admin brauchen eine eingeschaltete Zwei-Faktor-Anmeldung, bevor sie den Moderationsbereich öffnen " +
                        "können. Admins ernennen und ändern kann nur das Betreiberkonto.",
                    style = OasisType.Small,
                    color = OasisColors.Ink2
                )
                if (users.any(RoleUser::accessManageable)) {
                    Text(
                        "Als Betreiberkonto kannst du E-Mail-Adressen bestätigen und Passwörter zurücksetzen. " +
                            "Schick möglichst den Link – dann kennt nur das Mitglied das neue Passwort. " +
                            "Ein direkt gesetztes Passwort meldet das Konto überall ab, und das Mitglied bekommt sofort eine E-Mail.",
                        style = OasisType.Small,
                        color = OasisColors.Ink2
                    )
                }
                users.forEach { user -> RoleRow(user, state, viewModel) }
                if (state.hasMore) {
                    LaunchedEffect(users.size) { viewModel.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = OasisColors.Night)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleRow(user: RoleUser, state: RolesUiState, viewModel: RolesViewModel) {
    OasisCard(padding = 14) {
        Text(user.displayName, style = OasisType.BodyBold, color = OasisColors.Ink)
        Text(user.email, style = OasisType.Small, color = OasisColors.Ink2)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            OptionPicker(
                "Rolle von ${user.displayName}",
                state.roleNames,
                state.chosen[user.id] ?: user.role.value,
                { viewModel.choose(user.id, it) },
                Modifier.weight(1f).testTag("roles.picker.${user.id}"),
                enabled = user.editable,
                isEnabled = { it.value in user.choosableRoles }
            )
            val note = user.note
            if (note != null) {
                Text(note, style = OasisType.Caption, color = OasisColors.Muted, modifier = Modifier.padding(bottom = 12.dp))
            } else {
                AsyncOasisButton("Speichern", {
                    viewModel.save(user)
                }, Modifier.testTag("roles.save.${user.id}"), kind = OasisButtonKind.Secondary, fullWidth = false)
            }
        }
        if (user.accessManageable) AccessSection(user, state.passwords[user.id].orEmpty(), viewModel)
        state.errors[user.id]?.let { FieldError(it, Modifier.padding(top = 8.dp)) }
    }
}

/** Operator account only: confirm the address, send the password link or set a password. */
@Composable
private fun AccessSection(user: RoleUser, password: String, viewModel: RolesViewModel) {
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = OasisColors.PaperLine)
        if (user.emailVerified) {
            Text("E-Mail bestätigt", style = OasisType.Small, color = OasisColors.Ink2)
        } else {
            AsyncOasisButton(
                "E-Mail bestätigen",
                { viewModel.confirmEmail(user) },
                Modifier.testTag("roles.confirmEmail.${user.id}"),
                kind = OasisButtonKind.Secondary,
                fullWidth = false
            )
        }
        AsyncOasisButton(
            "Link für neues Passwort senden",
            { viewModel.sendPasswordLink(user) },
            Modifier.testTag("roles.passwordLink.${user.id}"),
            kind = OasisButtonKind.Secondary,
            fullWidth = false
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            OasisTextField(
                "Neues Passwort",
                password,
                { viewModel.typePassword(user.id, it) },
                Modifier.weight(1f).testTag("roles.password.${user.id}"),
                isSecure = true
            )
            AsyncOasisButton(
                "Setzen",
                { viewModel.setPassword(user) },
                Modifier.testTag("roles.setPassword.${user.id}"),
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                enabled = password.isNotEmpty()
            )
        }
    }
}
