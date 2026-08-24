package app.readylytics.health.core.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    enabled: Boolean = true,
) {
    val canGoForward = selectedDate < today
    val canGoBack = earliestDate == null || selectedDate > earliestDate
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.small,
                    vertical = MaterialTheme.spacing.extraSmall,
                ).graphicsLayer {
                    alpha = if (enabled) 1.0f else 0.5f
                },
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIconButton(
            onClick = onPreviousDay,
            enabled = canGoBack && enabled,
            isNext = false,
        )
        DatePill(
            selectedDate = selectedDate,
            today = today,
            enabled = enabled,
            onClick = { showDatePicker = true },
            modifier = Modifier.weight(1f),
        )
        NavIconButton(
            onClick = onNextDay,
            enabled = canGoForward && enabled,
            isNext = true,
        )
    }

    if (showDatePicker) {
        DateSwitcherPickerDialog(
            selectedDate = selectedDate,
            today = today,
            earliestDate = earliestDate,
            onDateSelected = onDateSelected,
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
private fun NavIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    isNext: Boolean,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
        modifier = modifier.size(MaterialTheme.dimens.avatarMedium),
    ) {
        Icon(
            imageVector =
                if (isNext) {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                },
            contentDescription =
                stringResource(
                    if (isNext) R.string.accessibility_next_day else R.string.accessibility_prev_day,
                ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSwitcherPickerDialog(
    selectedDate: LocalDate,
    today: LocalDate,
    earliestDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val todayMs = today.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    val earliestMs = earliestDate?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli()

    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        utcTimeMillis <= todayMs && (earliestMs == null || utcTimeMillis >= earliestMs)

                    override fun isSelectableYear(year: Int): Boolean {
                        val earliestYear = earliestDate?.year ?: 1900
                        return year in earliestYear..today.year
                    }
                },
        )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        onDateSelected(date)
                    }
                    onDismiss()
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}

@Composable
private fun DatePill(
    selectedDate: LocalDate,
    today: LocalDate,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEE, MMM d", locale) }
    val pillDescription =
        stringResource(
            R.string.accessibility_date_pill,
            qualifierLabelFor(selectedDate, today),
            selectedDate.format(dateFormatter),
        )

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp, max = 72.dp)
                .semantics { contentDescription = pillDescription }
                .testTag("date_pill"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = selectedDate,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
                },
                label = "date_pill_content",
            ) { date ->
                Column {
                    Text(
                        text = qualifierLabelFor(date, today),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = date.format(dateFormatter),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = null,
                modifier =
                    Modifier
                        .padding(start = MaterialTheme.spacing.small)
                        .size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun qualifierLabelFor(
    date: LocalDate,
    today: LocalDate,
): String =
    when (date) {
        today -> stringResource(R.string.date_switcher_label_today)
        today.minusDays(1) -> stringResource(R.string.date_switcher_label_yesterday)
        else -> stringResource(R.string.date_switcher_label_selected)
    }
