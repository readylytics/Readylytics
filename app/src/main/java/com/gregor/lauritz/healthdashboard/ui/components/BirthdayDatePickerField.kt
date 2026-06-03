package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayDatePickerField(
    birthDate: LocalDate?,
    onFieldClick: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    showDialog: Boolean,
    onDialogDismiss: () -> Unit,
) {
    val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    val displayText = birthDate?.format(dateFormatter) ?: "Not set"

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("Date of Birth") },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .clickable(onClick = onFieldClick),
        )
    }

    if (showDialog) {
        val datePickerState =
            rememberDatePickerState(
                initialSelectedDateMillis = birthDate?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli(),
                yearRange = 1900..LocalDate.now().year,
                selectableDates =
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                            utcTimeMillis <= System.currentTimeMillis()

                        override fun isSelectableYear(year: Int): Boolean = year in 1900..LocalDate.now().year
                    },
            )

        DatePickerDialog(
            onDismissRequest = onDialogDismiss,
            confirmButton = {
                Button(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val instant = Instant.ofEpochMilli(millis)
                            val date = instant.atZone(ZoneId.of("UTC")).toLocalDate()
                            onDateSelected(date)
                            onDialogDismiss()
                        }
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = onDialogDismiss) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
