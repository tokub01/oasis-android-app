package de.velwebsolutions.oasis.staff.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.Labelled
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** A labelled choice from a menu (the web's `<select>`); options outside [enabled] are shown but not choosable. */
@Composable
fun OptionPicker(
    label: String,
    options: List<Labelled>,
    selection: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isEnabled: (Labelled) -> Boolean = { true }
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(OasisRadius.XS.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = OasisType.SmallBold, color = OasisColors.Ink)
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .border(1.dp, OasisColors.FieldLine, shape)
                    .pressable({ open = true }, enabled = enabled)
                    .padding(horizontal = 14.dp)
                    .semantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    options.firstOrNull { it.value == selection }?.label ?: selection,
                    style = OasisType.Body,
                    color = if (enabled) OasisColors.Ink else OasisColors.Muted,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = OasisColors.Night)
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, style = OasisType.Body) },
                        onClick = {
                            open = false
                            onSelect(option.value)
                        },
                        enabled = isEnabled(option)
                    )
                }
            }
        }
    }
}

/** A date and time in German time, chosen with the Material date and time pickers (iOS `DatePicker`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(
    label: String,
    value: Instant,
    onChange: (Instant) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    latest: Instant? = null
) {
    var step by remember { mutableStateOf(0) }
    var chosenDay by remember { mutableStateOf<LocalDate?>(null) }
    val local = value.atZone(GermanTime.zone)
    val shape = RoundedCornerShape(OasisRadius.XS.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = OasisType.SmallBold, color = OasisColors.Ink)
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .border(if (error == null) 1.dp else 2.dp, if (error == null) OasisColors.FieldLine else OasisColors.SunText, shape)
                .pressable({ step = 1 })
                .padding(horizontal = 14.dp)
                .semantics { contentDescription = "$label: ${GermanTime.dayAndTime(value)}" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(GermanTime.dayAndTime(value), style = OasisType.Body, color = OasisColors.Ink, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Event, contentDescription = null, tint = OasisColors.Night)
        }
        if (error != null) FieldError(error)
    }
    if (step == 1) {
        val dateState =
            rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                OasisTextButton("Weiter", {
                    chosenDay =
                        dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                            ?: local.toLocalDate()
                    step = 2
                })
            },
            dismissButton = { OasisTextButton("Abbrechen", { step = 0 }) }
        ) { DatePicker(dateState) }
    }
    if (step == 2) {
        val timeState = rememberTimePickerState(local.hour, local.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                OasisTextButton("Fertig", {
                    val day = chosenDay ?: local.toLocalDate()
                    var chosen = day.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(GermanTime.zone).toInstant()
                    if (latest != null && chosen.isAfter(latest)) chosen = latest
                    onChange(chosen)
                    step = 0
                })
            },
            dismissButton = { OasisTextButton("Abbrechen", { step = 0 }) },
            text = { TimePicker(timeState) }
        )
    }
}

/** A confirmation before an action that cannot be undone or goes out to people (iOS `confirmationDialog`). */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    destructive: Boolean = false,
    confirmTag: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = OasisType.BodyBold, color = OasisColors.Night) },
        text = message?.let { { Text(it, style = OasisType.Small, color = OasisColors.Ink2) } },
        confirmButton = {
            OasisTextButton(
                confirmLabel,
                {
                    onDismiss()
                    onConfirm()
                },
                modifier = if (confirmTag != null) Modifier.testTag(confirmTag) else Modifier,
                color = if (destructive) OasisColors.SunText else OasisColors.Night
            )
        },
        dismissButton = { OasisTextButton("Abbrechen", onDismiss) }
    )
}
