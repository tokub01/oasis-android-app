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
import androidx.compose.material3.SelectableDates
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
 * The day a Material date picker selected (its midnight in UTC), or null while none is: typed in by keyboard (the
 * pencil), an empty or incomplete date selects nothing.
 */
internal fun pickedDay(selectedDateMillis: Long?): LocalDate? =
    selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }

/** [instant] moved into [earliest] … [latest]; a null bound leaves that side open. */
fun clampTime(instant: Instant, earliest: Instant?, latest: Instant?): Instant = when {
    earliest != null && instant.isBefore(earliest) -> earliest
    latest != null && instant.isAfter(latest) -> latest
    else -> instant
}

/** Whether the date picker offers the day of [utcMillis] (its midnight in UTC) between the days of the bounds in [zone]. */
internal fun selectableDay(utcMillis: Long, earliest: Instant?, latest: Instant?, zone: ZoneId): Boolean {
    val day = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val first = earliest?.atZone(zone)?.toLocalDate()
    val last = latest?.atZone(zone)?.toLocalDate()
    return (first == null || !day.isBefore(first)) && (last == null || !day.isAfter(last))
}

/**
 * A day and a time in the device's time, chosen with the Material date picker and then the time picker (iOS
 * `DatePicker` with date and time, `in: earliest...latest`); "Weiter" waits for a whole day when it is typed in. Days
 * outside [earliest] … [latest] cannot be picked, and a time outside them is moved to the nearest bound. The server checks
 * the exact bounds (Zeitkapseln: one hour to ten years ahead).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(
    label: String,
    value: Instant,
    onChange: (Instant) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    earliest: Instant? = null,
    latest: Instant? = null
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
                .border(if (error == null) 1.dp else 2.dp, if (error == null) OasisColors.FieldLine else OasisColors.Danger, shape)
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
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = selectableDay(utcTimeMillis, earliest, latest, zone)
            }
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                OasisTextButton(
                    "Weiter",
                    {
                        chosenDay = pickedDay(dateState.selectedDateMillis)
                        pickingDate = false
                    },
                    Modifier.testTag("datetime.next"),
                    enabled = dateState.selectedDateMillis != null
                )
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
                    onChange(
                        clampTime(day.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant(), earliest, latest)
                    )
                    chosenDay = null
                }, Modifier.testTag("datetime.done"))
            },
            dismissButton = { OasisTextButton("Abbrechen", { chosenDay = null }) },
            text = { TimePicker(timeState) }
        )
    }
}
