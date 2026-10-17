package com.aistock.assistant.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.aistock.assistant.R
import com.aistock.assistant.ui.fmtDateTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A date and time, or nothing. Tapping opens the calendar, then the clock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(label: String, millis: Long?, onChange: (Long?) -> Unit, modifier: Modifier = Modifier, defaultHour: Int = 18) {
    var step by remember { mutableStateOf(0) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val zone = ZoneId.systemDefault()
    val text = millis?.let { fmtDateTime(it) }.orEmpty()
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text, onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
        Box(Modifier.matchParentSize().chooser(label, text).clickable(onClickLabel = label) { step = 1 })
    }
    if (step == 1) {
        val initial = (millis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now(zone)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton(onClick = {
                    pickedDate = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    step = if (pickedDate != null) 2 else 0
                }) { Text(stringResource(R.string.next)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (millis != null) TextButton(onClick = { step = 0; onChange(null) }) { Text(stringResource(R.string.clear)) }
                    TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.cancel)) }
                }
            },
        ) { DatePicker(state) }
    }
    if (step == 2) {
        val t = millis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(defaultHour, 0)
        val state = rememberTimePickerState(initialHour = t.hour, initialMinute = t.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { step = 0 },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val d = pickedDate ?: LocalDate.now(zone)
                    onChange(d.atTime(state.hour, state.minute).atZone(zone).toInstant().toEpochMilli())
                    step = 0
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
