package com.aistock.assistant.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.aistock.assistant.R
import com.aistock.assistant.ui.fmtDate
import com.aistock.assistant.ui.millisToPickerDate
import com.aistock.assistant.ui.pickedDateToMillis

/** A date shown as text; tapping it opens the calendar. Values are normal local timestamps. */
@Composable
fun DateField(label: String, millis: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fmtDate(millis), onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
        Box(Modifier.matchParentSize().chooser(label, fmtDate(millis)).clickable(onClickLabel = label) { open = true })
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = millisToPickerDate(millis))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(pickedDateToMillis(it)) }
                    open = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}
