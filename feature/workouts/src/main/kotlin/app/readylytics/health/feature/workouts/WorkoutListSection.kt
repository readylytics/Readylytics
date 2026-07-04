package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.containerColor
import app.readylytics.health.core.ui.components.onContainerColor
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.repository.WorkoutData
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

        if (totalPages > 1) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical = MaterialTheme.spacing.pageSectionGap,
                            horizontal = MaterialTheme.spacing.pageHorizontal,
                        ),
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
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.medium))
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
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.medium))
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
        modifier = modifier.fillMaxWidth().graphicsLayer { },
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
            headlineContent = {
                Text(
                    text = "$displayType $dateStr",
                    style = MaterialTheme.typography.titleSmall,
                )
            },
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
                IntensityBadge(
                    label = stringResource(workout.intensityLabelResId()),
                    status = workout.intensityStatus(),
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
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
