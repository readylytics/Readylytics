package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutData
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.containerColor
import com.gregor.lauritz.healthdashboard.ui.components.onContainerColor
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WorkoutListSection(
    workouts: List<WorkoutDisplayItem>,
    currentPage: Int,
    totalPages: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onWorkoutClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(title = stringResource(R.string.workout_stats_history_title))
        workouts.forEach { item ->
            WorkoutHistoryItem(
                item = item,
                onClick = { onWorkoutClick(item.workout.id) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (totalPages > 1) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedIconButton(
                    onClick = onPreviousPage,
                    enabled = currentPage > 1,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.workout_history_button_prev),
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text =
                        stringResource(
                            R.string.workout_history_page_info,
                            currentPage,
                            totalPages,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                OutlinedIconButton(
                    onClick = onNextPage,
                    enabled = currentPage < totalPages,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.workout_history_button_next),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutHistoryItem(
    item: WorkoutDisplayItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workout = item.workout
    val displayType = exerciseTypeToDisplayName(workout.exerciseType)
    val dateStr =
        remember(workout.startTime) {
            val fmt =
                java.time.format.DateTimeFormatter
                    .ofPattern("(dd.MM)", Locale.getDefault())
            java.time.Instant
                .ofEpochMilli(workout.startTime)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .format(fmt)
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$displayType $dateStr",
                    style = MaterialTheme.typography.titleSmall,
                )
                val strainStr = String.format(java.util.Locale.US, "%.2f", item.gainedStrain)
                val bpmStr =
                    if (workout.avgHr > 0) {
                        stringResource(R.string.workout_history_bpm_format, workout.avgHr.roundToInt())
                    } else {
                        stringResource(R.string.workout_history_bpm_na)
                    }
                Text(
                    text =
                        stringResource(
                            R.string.workout_history_item_subtitle,
                            workout.durationMinutes,
                            strainStr,
                            bpmStr,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IntensityBadge(
                label = stringResource(workout.intensityLabelResId()),
                status = workout.intensityStatus(),
            )
        }
    }
}

@Composable
private fun IntensityBadge(
    label: String,
    status: MetricStatus,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = status.containerColor(),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = status.onContainerColor(),
        )
    }
}

private fun WorkoutData.intensityLabelResId(): Int =
    when {
        trimp > 200 -> R.string.workout_intensity_very_hard
        trimp > 150 -> R.string.workout_intensity_hard
        trimp > 100 -> R.string.workout_intensity_moderate
        trimp > 50 -> R.string.workout_intensity_light
        else -> R.string.workout_intensity_very_light
    }

private fun WorkoutData.intensityStatus(): MetricStatus =
    when {
        trimp > 200 -> MetricStatus.POOR
        trimp > 150 -> MetricStatus.WARNING
        trimp > 50 -> MetricStatus.OPTIMAL
        else -> MetricStatus.CALIBRATING
    }
