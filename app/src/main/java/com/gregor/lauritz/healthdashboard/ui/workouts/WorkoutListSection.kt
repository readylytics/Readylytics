package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    onWorkoutClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(title = "History")
        workouts.forEach { item ->
            WorkoutHistoryItem(
                item = item,
                onClick = { onWorkoutClick(item.workout.id) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
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
                Text(
                    text =
                        "${workout.durationMinutes} min  ·  Strain ${String.format(java.util.Locale.US, "%.2f", item.gainedStrain)}  ·  " +
                            if (workout.avgHr > 0) "${workout.avgHr.roundToInt()} bpm" else "-- bpm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IntensityBadge(
                label = workout.intensityLabel(),
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

private fun WorkoutData.intensityLabel(): String =
    when {
        trimp > 200 -> "Very Hard"
        trimp > 150 -> "Hard"
        trimp > 100 -> "Moderate"
        trimp > 50 -> "Light"
        else -> "Very Light"
    }

private fun WorkoutData.intensityStatus(): MetricStatus =
    when {
        trimp > 200 -> MetricStatus.POOR
        trimp > 150 -> MetricStatus.WARNING
        trimp > 50 -> MetricStatus.OPTIMAL
        else -> MetricStatus.CALIBRATING
    }
