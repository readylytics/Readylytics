package app.readylytics.health.feature.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
internal fun stageDisplayName(stageType: String): String {
    val labelResId = LANES.firstOrNull { it.stageType == stageType }?.labelResId
    return if (labelResId != null) stringResource(labelResId) else stageType
}

@Composable
internal fun formatStageDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) {
        stringResource(CoreUiR.string.sleep_duration_hours_minutes, h, m)
    } else {
        stringResource(CoreUiR.string.sleep_duration_minutes_only, m)
    }
}

internal fun getLabelTimestamps(
    startMs: Long,
    endMs: Long,
): List<Long> {
    val sessionDurationMinutes = (endMs - startMs) / 60_000L
    if (sessionDurationMinutes <= 0L) return emptyList()

    val durationHours = sessionDurationMinutes / 60f
    val intervalMinutes =
        when {
            durationHours <= 4 -> 60
            durationHours <= 8 -> 120
            else -> 180
        }

    val timestamps = mutableListOf<Long>()
    timestamps.add(startMs)

    val zoneId = ZoneId.systemDefault()
    val startZDT = Instant.ofEpochMilli(startMs).atZone(zoneId)
    var currentZDT =
        startZDT
            .plusHours(1)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)

    while (currentZDT.toInstant().toEpochMilli() < endMs - (intervalMinutes * 30_000L)) {
        val ts = currentZDT.toInstant().toEpochMilli()
        if (ts > startMs + (intervalMinutes * 30_000L)) {
            timestamps.add(ts)
        }
        currentZDT = currentZDT.plusMinutes(intervalMinutes.toLong())
    }

    if (timestamps.last() < endMs - (15 * 60_000L)) {
        timestamps.add(endMs)
    } else if (timestamps.size > 1) {
        timestamps[timestamps.size - 1] = endMs
    } else {
        timestamps.add(endMs)
    }

    return timestamps.distinct()
}

internal fun resolveNonOverlappingLabels(
    labelTimestamps: List<Long>,
    startTime: Long,
    sessionDurationMs: Long,
    totalWidthPx: Int,
    labelWidthsPx: List<Int>,
    spacingPx: Float,
): List<Int> {
    if (labelTimestamps.isEmpty() || sessionDurationMs <= 0) return emptyList()

    val lefts =
        labelTimestamps.mapIndexed { i, ts ->
            val fraction = (ts - startTime).toFloat() / sessionDurationMs.toFloat()
            val centerX = fraction * totalWidthPx
            val width = labelWidthsPx.getOrElse(i) { 0 }
            (centerX - width / 2f)
                .roundToInt()
                .coerceIn(0, maxOf(0, totalWidthPx - width))
                .toFloat()
        }

    return resolveNonOverlappingLabelsByBounds(lefts, labelWidthsPx, spacingPx)
}

@Composable
internal fun SleepStagesLaneLabels(
    stageDurations: Map<String, Int>,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(LABEL_WIDTH)) {
        LANES.forEach { lane ->
            Column(
                modifier = Modifier.height(LANE_HEIGHT).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(lane.labelResId),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant,
                )
                val durationMinutes = stageDurations[lane.stageType] ?: 0
                if (durationMinutes > 0) {
                    Text(
                        text = formatStageDuration(durationMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SleepStagesXAxis(
    labelTimestamps: List<Long>,
    timeFormatter: DateTimeFormatter,
    sessionStartTime: Long,
    sessionDurationMs: Long,
    chartWidth: Dp,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            labelTimestamps.forEach { ts ->
                Text(
                    text = timeFormatter.format(Instant.ofEpochMilli(ts)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        modifier = modifier.width(chartWidth).height(24.dp),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val totalWidth = constraints.maxWidth
            val spacingPx = 8.dp.toPx()
            val labelWidths = placeables.map { it.width }
            val acceptedIndices =
                resolveNonOverlappingLabels(
                    labelTimestamps = labelTimestamps,
                    startTime = sessionStartTime,
                    sessionDurationMs = sessionDurationMs,
                    totalWidthPx = totalWidth,
                    labelWidthsPx = labelWidths,
                    spacingPx = spacingPx,
                ).toSet()

            placeables.forEachIndexed { index, placeable ->
                if (index in acceptedIndices) {
                    val ts = labelTimestamps[index]
                    val fraction = (ts - sessionStartTime).toFloat() / sessionDurationMs
                    val centerX = fraction * totalWidth
                    val left =
                        (centerX - placeable.width / 2f)
                            .roundToInt()
                            .coerceIn(0, maxOf(0, totalWidth - placeable.width))
                    placeable.placeRelative(left, 0)
                }
            }
        }
    }
}
