package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutData
import com.gregor.lauritz.healthdashboard.ui.common.DateFormatUtils
import com.gregor.lauritz.healthdashboard.ui.components.MetricCard
import com.gregor.lauritz.healthdashboard.ui.theme.LocalStatusColors
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun WorkoutMetricsDisplay(
    workout: WorkoutData,
    computedTrimp: Int?,
    gainedStrain: Float?,
    pai: Float?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        WorkoutHeader(workout)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricCard(
                    title = "Training Load",
                    value = (computedTrimp ?: workout.trimp.roundToInt()).toString(),
                    secondaryText = "TRIMP",
                    status = MetricStatus.NEUTRAL,
                    tooltip = "Total training impulse - measures training intensity and duration.",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    title = "Avg Pulse",
                    value = if (workout.avgHr > 0) workout.avgHr.roundToInt().toString() else "--",
                    secondaryText = "bpm",
                    status = MetricStatus.NEUTRAL,
                    tooltip = "Average heart rate during the workout.",
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricCard(
                    title = "Gained Strain",
                    value = (gainedStrain ?: workout.trimp).roundToInt().toString(),
                    secondaryText = "Strain",
                    status = MetricStatus.NEUTRAL,
                    tooltip = "Total strain gained from this workout.",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    title = "PAI",
                    value = pai?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "--",
                    secondaryText = "points",
                    status = MetricStatus.NEUTRAL,
                    tooltip = "Personal Activity Intelligence points gained from this workout.",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ZoneBreakdownCard(workout)
    }
}

@Composable
private fun WorkoutHeader(workout: WorkoutData) {
    val type = remember(workout.exerciseType) { exerciseTypeToDisplayName(workout.exerciseType) }

    val (start, end, date) =
        remember(workout.startTime, workout.endTime) {
            val startInstant = Instant.ofEpochMilli(workout.startTime).atZone(ZoneId.systemDefault())
            val endInstant = Instant.ofEpochMilli(workout.endTime).atZone(ZoneId.systemDefault())
            Triple(
                startInstant.format(DateFormatUtils.WORKOUT_TIME_FORMATTER),
                endInstant.format(DateFormatUtils.WORKOUT_TIME_FORMATTER),
                startInstant.format(DateFormatUtils.WORKOUT_DATE_FORMATTER),
            )
        }

    Column {
        Text(text = type, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = date,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$start - $end (${workout.durationMinutes} min)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ZoneBreakdownCard(workout: WorkoutData) {
    val totalMinutes = workout.durationMinutes.toFloat().coerceAtLeast(1f)
    val statusColors = LocalStatusColors.current
    val zones =
        listOf(
            Triple("Zone 5", workout.zone5Minutes, statusColors.poor),
            Triple("Zone 4", workout.zone4Minutes, statusColors.warning),
            Triple("Zone 3", workout.zone3Minutes, statusColors.optimal),
            Triple("Zone 2", workout.zone2Minutes, statusColors.neutral),
            Triple("Zone 1", workout.zone1Minutes, MaterialTheme.colorScheme.onSurfaceVariant),
        )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Heart Rate Zones", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            zones.forEach { (label, minutes, color) ->
                ZoneRow(label, minutes, totalMinutes, color)
            }
        }
    }
}

@Composable
private fun ZoneRow(
    label: String,
    minutes: Float,
    totalMinutes: Float,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(52.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { (minutes / totalMinutes).coerceIn(0f, 1f) },
            modifier =
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.15f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "%.0f min".format(minutes),
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}
