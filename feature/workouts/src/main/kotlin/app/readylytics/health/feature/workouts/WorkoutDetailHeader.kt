package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.feature.workouts.R
import java.time.Instant
import java.time.ZoneId

@Composable
fun WorkoutDetailHeader(
    workout: WorkoutData,
    modifier: Modifier = Modifier,
) {
    val type = remember(workout.exerciseType) { exerciseTypeToDisplayName(workout.exerciseType).trim() }

    val (start, end, date) =
        remember(workout.startTime, workout.endTime) {
            val startInstant = Instant.ofEpochMilli(workout.startTime).atZone(ZoneId.systemDefault())
            val endInstant = Instant.ofEpochMilli(workout.endTime).atZone(ZoneId.systemDefault())
            Triple(
                startInstant.format(DateFormatUtils.getWorkoutTimeFormatter()),
                endInstant.format(DateFormatUtils.getWorkoutTimeFormatter()),
                startInstant.format(DateFormatUtils.getWorkoutDateFormatter()),
            )
        }
    val headerTime = remember(start, end, workout.durationMinutes) { "$start - $end (${workout.durationMinutes} min)" }

    val hasHeaderContent = date.isNotBlank() && headerTime.isNotBlank()

    if (!hasHeaderContent) return

    val displayType = type.ifBlank { stringResource(R.string.workout_header_type_fallback) }

    Column(modifier) {
        Text(text = displayType, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = date,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = headerTime,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
