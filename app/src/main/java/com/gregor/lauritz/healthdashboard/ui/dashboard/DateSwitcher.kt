package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSwitcher(
    selectedDate: LocalDate,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    onDateSelected: (LocalDate) -> Unit = {},
    earliestDate: LocalDate? = null,
    availableDates: Set<LocalDate>? = null,
) {
    val label = remember(selectedDate) { formatDateLabel(selectedDate, today) }
    val canGoForward = selectedDate < today
    val canGoBack = earliestDate == null || selectedDate > earliestDate
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPreviousDay,
            enabled = canGoBack,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Previous day",
                tint =
                    if (canGoBack) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { showDatePicker = true },
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
            )
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = "Open date picker",
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onNextDay,
            enabled = canGoForward,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Next day",
                tint =
                    if (canGoForward) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
            )
        }
    }

    if (showDatePicker) {
        val todayMs = today.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val earliestMs = earliestDate?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli()

        val datePickerState =
            rememberDatePickerState(
                initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
                selectableDates =
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                            val isWithinBounds = utcTimeMillis <= todayMs && (earliestMs == null || utcTimeMillis >= earliestMs)
                            if (!isWithinBounds) return false
                            if (availableDates.isNullOrEmpty()) return true
                            val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneId.of("UTC")).toLocalDate()
                            return date == today || availableDates.contains(date)
                        }

                        override fun isSelectableYear(year: Int): Boolean {
                            val earliestYear = earliestDate?.year ?: 1900
                            return year in earliestYear..today.year
                        }
                    },
            )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                            onDateSelected(date)
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

private fun formatDateLabel(
    date: LocalDate,
    today: LocalDate,
): String =
    when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault()))
    }
