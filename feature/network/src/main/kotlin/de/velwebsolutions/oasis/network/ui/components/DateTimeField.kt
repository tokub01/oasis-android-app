package de.velwebsolutions.oasis.network.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** "24. Dezember 2026 um 18:00" in the device's time. */
fun capsuleTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    GermanTime.format(instant, "d. MMMM yyyy 'um' HH:mm", zone)

/**
 * A day and a time in the device's time, chosen with the Material date picker and then the time picker (iOS
 * `DatePicker` with date and time). The server checks the bounds (Zeitkapseln: one hour to ten years ahead).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(
    label: String,
    value: Instant,
    onChange: (Instant) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    zone: ZoneId = ZoneId.systemDefault()
) {
    var pickingDate by remember { mutableStateOf(false) }
    var chosenDay by remember { mutableStateOf<LocalDate?>(null) }
    val local = value.atZone(zone)
    val shape = RoundedCornerShape(OasisRadius.XS.dp)
    val shown = capsuleTime(value, zone)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = OasisType.SmallBold, color = OasisColors.Ink)
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .border(if (error == null) 1.dp else 2.dp, if (error == null) OasisColors.FieldLine else OasisColors.SunText, shape)
                .pressable({ pickingDate = true })
                .padding(horizontal = 14.dp)
                .testTag("datetime.field")
                .semantics { contentDescription = "$label: $shown" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(shown, style = OasisType.Body, color = OasisColors.Ink, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Event, contentDescription = null, tint = OasisColors.Night)
        }
        if (error != null) FieldError(error)
    }
    if (pickingDate) {
        val dateState =
            rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                OasisTextButton("Weiter", {
                    // Material's date picker cannot clear the day it started with.
                    chosenDay = Instant.ofEpochMilli(dateState.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                    pickingDate = false
                }, Modifier.testTag("datetime.next"))
            },
            dismissButton = { OasisTextButton("Abbrechen", { pickingDate = false }) }
        ) { DatePicker(dateState) }
    }
    val day = chosenDay
    if (day != null) {
        val timeState = rememberTimePickerState(local.hour, local.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { chosenDay = null },
            confirmButton = {
                OasisTextButton("Fertig", {
                    onChange(day.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant())
                    chosenDay = null
                }, Modifier.testTag("datetime.done"))
            },
            dismissButton = { OasisTextButton("Abbrechen", { chosenDay = null }) },
            text = { TimePicker(timeState) }
        )
    }
}
