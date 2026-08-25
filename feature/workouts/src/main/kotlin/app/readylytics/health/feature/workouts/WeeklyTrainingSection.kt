package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.LocalStatusColors
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.scoring.domain.workouts.weekly.WeeklyTrainingStats
import app.readylytics.health.core.ui.common.DeltaDirection
import app.readylytics.health.core.ui.common.DeltaOutcome
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.assessDeltaOutcome
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun WeeklyTrainingSection(
    stats: WeeklyTrainingStats?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        SectionHeader(
            title = stringResource(R.string.workout_stats_weekly_title),
            enabled = !isLoading,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        if (isLoading || stats == null) {
            WeeklyTrainingSkeleton()
        } else {
            WeeklyTrainingCards(stats)
        }
    }
}

@Composable
private fun WeeklyTrainingCards(stats: WeeklyTrainingStats) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        WeeklyStatCard(
            icon = Icons.Filled.Timer,
            label = stringResource(R.string.workout_stats_weekly_training_time),
            value = WeeklyTrainingDeltaFormatter.formatDuration(stats.currentWeek.totalDurationMinutes),
            delta =
                weeklyDeltaDisplay(
                    current = stats.currentWeek.totalDurationMinutes,
                    previous = stats.previousWeek.totalDurationMinutes,
                    detail =
                        WeeklyTrainingDeltaFormatter.formatDurationDelta(
                            stats.comparison.durationDeltaMinutes,
                            stats.comparison.durationPercentChange,
                        ),
                ),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        WeeklyStatCard(
            icon = Icons.Filled.EventAvailable,
            label = stringResource(R.string.workout_stats_weekly_workouts),
            value = stats.currentWeek.workoutCount.toString(),
            delta =
                weeklyDeltaDisplay(
                    current = stats.currentWeek.workoutCount,
                    previous = stats.previousWeek.workoutCount,
                    detail = WeeklyTrainingDeltaFormatter.formatCountDelta(stats.comparison.workoutCountDelta),
                ),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        WeeklyStatCard(
            icon = Icons.Filled.CalendarMonth,
            label = stringResource(R.string.workout_stats_weekly_active_days),
            value = stringResource(R.string.workout_stats_weekly_active_days_value, stats.currentWeek.activeDays),
            delta =
                weeklyDeltaDisplay(
                    current = stats.currentWeek.activeDays,
                    previous = stats.previousWeek.activeDays,
                    detail = WeeklyTrainingDeltaFormatter.formatCountDelta(stats.comparison.activeDaysDelta),
                ),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun WeeklyStatCard(
    icon: ImageVector,
    label: String,
    value: String,
    delta: WeeklyDeltaDisplay,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.workout_stats_weekly_vs_last_week),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = delta.text, style = MaterialTheme.typography.labelMedium, color = delta.color)
        }
    }
}

private data class WeeklyDeltaDisplay(
    val text: String,
    val color: Color,
)

/** Arrow + signed detail for an improved/worsened comparison; neutral no-change text otherwise.
 *  All Weekly training metrics are HIGHER_IS_BETTER. */
@Composable
private fun weeklyDeltaDisplay(
    current: Int,
    previous: Int,
    detail: String,
): WeeklyDeltaDisplay {
    val statusColors = LocalStatusColors.current
    val outcome = assessDeltaOutcome(current, previous, DeltaDirection.HIGHER_IS_BETTER)
    val color =
        when (outcome) {
            DeltaOutcome.IMPROVED -> statusColors.optimal
            DeltaOutcome.WORSENED -> statusColors.warning
            DeltaOutcome.NEUTRAL, null -> statusColors.neutral
        }
    val text =
        when (outcome) {
            DeltaOutcome.IMPROVED -> stringResource(CoreUiR.string.delta_up) + " $detail"
            DeltaOutcome.WORSENED -> stringResource(CoreUiR.string.delta_down) + " $detail"
            DeltaOutcome.NEUTRAL, null -> stringResource(CoreUiR.string.delta_no_change)
        }
    return WeeklyDeltaDisplay(text, color)
}

@Composable
private fun WeeklyTrainingSkeleton() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        repeat(3) {
            SkeletonCard(height = 148.dp, modifier = Modifier.weight(1f))
        }
    }
}
