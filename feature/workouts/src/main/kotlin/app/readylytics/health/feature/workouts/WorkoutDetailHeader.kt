package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.workouts.detail.ExerciseTypeMapper
import app.readylytics.health.core.ui.common.DateFormatUtils
import java.time.Instant
import java.time.ZoneId

@Composable
fun WorkoutDetailHeader(
    workout: WorkoutData,
    modifier: Modifier = Modifier,
) {
    val exerciseType = remember(workout.exerciseType) { ExerciseTypeMapper.fromRaw(workout.exerciseType) }

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

    Column(modifier) {
        Text(
            text = stringResource(exerciseType.displayNameResId),
            style = MaterialTheme.typography.headlineMedium,
        )
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
