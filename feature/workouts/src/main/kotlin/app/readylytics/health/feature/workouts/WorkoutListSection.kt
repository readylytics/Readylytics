package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.ui.components.PaginationControls
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.containerColor
import app.readylytics.health.core.ui.components.onContainerColor
import app.readylytics.health.feature.workouts.R
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
                modifier =
                    Modifier.padding(
                        horizontal = MaterialTheme.spacing.pageHorizontal,
                        vertical = MaterialTheme.spacing.extraSmall,
                    ),
            )
        }

        PaginationControls(
            currentPage = currentPage,
            totalPages = totalPages,
            onPreviousPage = onPreviousPage,
            onNextPage = onNextPage,
        )
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
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
    ) {
        val bpmStr =
            if (workout.avgHr > 0) {
                stringResource(R.string.workout_history_bpm_format, workout.avgHr.roundToInt())
            } else {
                stringResource(R.string.workout_history_bpm_na)
            }
        ListItem(
            supportingContent = {
                Text(
                    text =
                        stringResource(
                            R.string.workout_history_item_subtitle,
                            workout.durationMinutes,
                            item.gainedStrainDisplay,
                            bpmStr,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                item.classification?.let { classification ->
                    IntensityBadge(
                        label = stringResource(classification.finalLoad.labelResId()),
                        status = classification.overallBadgeStatus(),
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) {
            Text(
                text = "$displayType $dateStr",
                style = MaterialTheme.typography.titleSmall,
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
        shape = MaterialTheme.shapes.medium,
        color = status.containerColor(),
    ) {
        Text(
            text = label,
            modifier =
                Modifier.padding(
                    horizontal = MaterialTheme.spacing.smallMedium,
                    vertical = MaterialTheme.spacing.extraSmall,
                ),
            style = MaterialTheme.typography.labelSmall,
            color = status.onContainerColor(),
        )
    }
}
