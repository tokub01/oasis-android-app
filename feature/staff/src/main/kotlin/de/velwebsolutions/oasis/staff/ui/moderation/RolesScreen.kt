package de.velwebsolutions.oasis.staff.ui.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
                users.forEach { user -> RoleRow(user, state, viewModel::choose, viewModel::save) }
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
private fun RoleRow(user: RoleUser, state: RolesUiState, onChoose: (Int, String) -> Unit, onSave: suspend (RoleUser) -> Unit) {
    OasisCard(padding = 14) {
        Text(user.displayName, style = OasisType.BodyBold, color = OasisColors.Ink)
        Text(user.email, style = OasisType.Small, color = OasisColors.Ink2)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            OptionPicker(
                "Rolle von ${user.displayName}",
                state.roleNames,
                state.chosen[user.id] ?: user.role.value,
                { onChoose(user.id, it) },
                Modifier.weight(1f).testTag("roles.picker.${user.id}"),
                enabled = user.editable,
                isEnabled = { it.value in user.choosableRoles }
            )
            val note = user.note
            if (note != null) {
                Text(note, style = OasisType.Caption, color = OasisColors.Muted, modifier = Modifier.padding(bottom = 12.dp))
            } else {
                AsyncOasisButton("Speichern", {
                    onSave(user)
                }, Modifier.testTag("roles.save.${user.id}"), kind = OasisButtonKind.Secondary, fullWidth = false)
            }
        }
        state.errors[user.id]?.let { FieldError(it, Modifier.padding(top = 8.dp)) }
    }
}
