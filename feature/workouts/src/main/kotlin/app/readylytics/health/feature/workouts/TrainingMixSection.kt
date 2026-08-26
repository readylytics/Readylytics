package app.readylytics.health.feature.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.scoring.domain.workouts.weekly.TrainingMixItem
import app.readylytics.health.core.scoring.domain.workouts.weekly.WeeklyTrainingStats
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.components.SectionHeader
import kotlin.math.roundToInt

private const val DONUT_SIZE_DP = 130
private const val DONUT_STROKE_WIDTH_DP = 14
private const val SLICE_GAP_DEGREES = 1.5f
private const val COLOR_DOT_SIZE_DP = 6
private const val ICON_SIZE_DP = 18

@Composable
fun TrainingMixSection(
    stats: WeeklyTrainingStats?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        SectionHeader(
            title = stringResource(R.string.training_mix_title),
            enabled = !isLoading,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        when {
            isLoading || stats == null -> TrainingMixSkeleton()
            stats.trainingMix.isEmpty() -> TrainingMixEmptyCard()
            else -> TrainingMixCard(stats = stats)
        }
    }
}

@Composable
private fun TrainingMixSkeleton() {
    SkeletonCard(
        height = 180.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    )
}

@Composable
private fun TrainingMixEmptyCard() {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(MaterialTheme.spacing.medium),
        ) {
            Icon(
                imageVector = Icons.Outlined.PieChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.smallMedium))
            Text(
                text = stringResource(R.string.training_mix_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TrainingMixCard(stats: WeeklyTrainingStats) {
    val items = stats.trainingMix
    val totalMinutes = stats.currentWeek.totalDurationMinutes
    val sliceColors = resolveTrainingMixColors()
    val totalText = WeeklyTrainingDeltaFormatter.formatDuration(totalMinutes)
    val itemNames = items.map { stringResource(it.activityType.displayNameResId) }
    val itemsSummary =
        remember(items, itemNames) {
            items.mapIndexed { index, item ->
                val name = itemNames[index]
                val duration = WeeklyTrainingDeltaFormatter.formatDuration(item.durationMinutes)
                val percent = item.percentage.roundToInt()
                "$name: $duration, $percent%"
            }.joinToString(", ")
        }
    val accessibilitySummary = stringResource(R.string.training_mix_accessibility_summary, totalText, itemsSummary)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                .semantics { contentDescription = accessibilitySummary },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrainingMixDonut(
                items = items,
                totalMinutes = totalMinutes,
                sliceColors = sliceColors,
            )
            Spacer(Modifier.width(MaterialTheme.spacing.medium))
            TrainingMixBreakdownList(
                items = items,
                sliceColors = sliceColors,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrainingMixDonut(
    items: List<TrainingMixItem>,
    totalMinutes: Int,
    sliceColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val strokeWidthPx = with(LocalDensity.current) { DONUT_STROKE_WIDTH_DP.dp.toPx() }

    Box(
        modifier = modifier.size(DONUT_SIZE_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(DONUT_SIZE_DP.dp)) {
            val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Butt)
            val arcSize = Size(size.width - strokeWidthPx, size.height - strokeWidthPx)
            val topLeft = Offset(strokeWidthPx / 2f, strokeWidthPx / 2f)

            var currentAngle = -90f
            val hasMultipleSlices = items.size > 1

            items.forEachIndexed { index, item ->
                val fullSweep = 360f * (item.percentage / 100f)
                val sweep =
                    if (hasMultipleSlices && fullSweep > SLICE_GAP_DEGREES) {
                        fullSweep - SLICE_GAP_DEGREES
                    } else {
                        fullSweep
                    }
                val start =
                    if (hasMultipleSlices) {
                        currentAngle + (SLICE_GAP_DEGREES / 2f)
                    } else {
                        currentAngle
                    }
                val color = sliceColors.getOrElse(index) { sliceColors.last() }

                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
                currentAngle += fullSweep
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = WeeklyTrainingDeltaFormatter.formatDuration(totalMinutes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.training_mix_total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrainingMixBreakdownList(
    items: List<TrainingMixItem>,
    sliceColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        items.forEachIndexed { index, item ->
            val color = sliceColors.getOrElse(index) { sliceColors.last() }
            TrainingMixRow(
                item = item,
                color = color,
            )
        }
    }
}

@Composable
private fun TrainingMixRow(
    item: TrainingMixItem,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(COLOR_DOT_SIZE_DP.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
        Icon(
            imageVector = item.activityType.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ICON_SIZE_DP.dp),
        )
        Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
        Text(
            text = stringResource(item.activityType.displayNameResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
        Text(
            text = WeeklyTrainingDeltaFormatter.formatDuration(item.durationMinutes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(MaterialTheme.spacing.small))
        Text(
            text = "${item.percentage.roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun resolveTrainingMixColors(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return listOf(
        scheme.primary,
        scheme.tertiary,
        scheme.secondary,
        scheme.primaryContainer,
        scheme.outline,
    )
}
