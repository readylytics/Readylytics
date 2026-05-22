package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.model.SleepStage
import com.gregor.lauritz.healthdashboard.domain.model.SleepStageType
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun getStageColor(
    stage: SleepStage,
    colorScheme: androidx.compose.material3.ColorScheme,
): Color =
    when (stage) {
        SleepStage.DEEP -> colorScheme.primary
        SleepStage.LIGHT -> colorScheme.secondary
        SleepStage.REM -> colorScheme.tertiary
        SleepStage.AWAKE -> colorScheme.error
    }

private const val AWAKE_LANE_INDEX = 0
private const val REM_LANE_INDEX = 1
private const val LIGHT_LANE_INDEX = 2
private const val DEEP_LANE_INDEX = 3

private fun getStageLaneIndex(stageType: String): Int =
    when (stageType) {
        SleepStageType.AWAKE.value -> AWAKE_LANE_INDEX
        SleepStageType.REM.value -> REM_LANE_INDEX
        SleepStageType.LIGHT.value -> LIGHT_LANE_INDEX
        SleepStageType.DEEP.value -> DEEP_LANE_INDEX
        else -> LIGHT_LANE_INDEX // Default to Light sleep lane if unknown
    }

private fun mergeConsecutiveStages(stages: List<SleepStageData>): List<SleepStageData> {
    if (stages.isEmpty()) return emptyList()
    val merged = mutableListOf<SleepStageData>()
    var current = stages[0]
    for (i in 1 until stages.size) {
        val next = stages[i]
        if (next.stageType == current.stageType) {
            current =
                current.copy(
                    endTime = next.endTime,
                    durationMinutes = current.durationMinutes + next.durationMinutes,
                )
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
}

private fun getLabelTimestamps(
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
    // Find first "nice" time after start (top of the hour)
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
        // Replace last if too close to endMs
        timestamps[timestamps.size - 1] = endMs
    } else {
        timestamps.add(endMs)
    }

    return timestamps.distinct()
}

@Composable
fun SleepStagesChart(
    session: SleepSessionData?,
    modifier: Modifier = Modifier,
    stageTimeline: List<SleepStageData> = emptyList(),
    labelWidth: Dp = 52.dp,
    horizontalPadding: Dp = 24.dp,
    spacing: Dp = 8.dp,
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var activeTapOffset by remember { mutableStateOf<Offset?>(null) }
    var activeSegmentIndex by remember { mutableStateOf<Int?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val canvasLeftOffsetPx =
        remember(density, horizontalPadding, labelWidth, spacing) {
            with(density) { (horizontalPadding + labelWidth + spacing).roundToPx() }
        }
    val sleepStages = remember { SleepStage.values() }

    if (session == null) {
        CalibrationBar(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceVariant,
            onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val colorScheme = MaterialTheme.colorScheme

    // Selected segment breathing animation
    val infiniteTransition = rememberInfiniteTransition(label = "halo")
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "haloAlpha",
    )

    val timeFormatter =
        remember {
            DateTimeFormatter
                .ofLocalizedTime(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
        }

    val labelTimestamps = getLabelTimestamps(session.startTime, session.endTime)
    val sortedTimeline = remember(stageTimeline) { stageTimeline.sortedBy { it.startTime } }
    val mergedTimeline = remember(sortedTimeline) { mergeConsecutiveStages(sortedTimeline) }
    val sessionDurationMs = (session.endTime - session.startTime).coerceAtLeast(1L)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
    ) {
        // Left Column: Lane Labels
        Column(
            modifier =
                Modifier
                    .width(labelWidth)
                    .height(200.dp)
                    .padding(vertical = 24.dp),
            // Matched with vertical paddings on Canvas
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "Awake",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "REM",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Light",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Deep",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.width(spacing))

        // Right Column: Canvas & Time Labels
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .testTag("SleepStagesChartCanvas")
                        .detectCanvasTap(
                            segments =
                                remember(mergedTimeline, session.startTime, sessionDurationMs) {
                                    mergedTimeline.mapIndexed { index, stageData ->
                                        val startFraction =
                                            (stageData.startTime - session.startTime).toFloat() / sessionDurationMs
                                        val endFraction =
                                            (stageData.endTime - session.startTime).toFloat() / sessionDurationMs
                                        SegmentHitBox(
                                            index = index,
                                            xStart = startFraction,
                                            xEnd = endFraction,
                                            label = stageData.stageType,
                                        )
                                    }
                                },
                            onSegmentTapped = { index, _, tapOffset ->
                                activeSegmentIndex = index
                                activeTapOffset = tapOffset
                                val tappedStage = mergedTimeline[index]
                                val valueText = "${tappedStage.durationMinutes} min"
                                val dateText = timeFormatter.format(Instant.ofEpochMilli(tappedStage.startTime))
                                tooltipState =
                                    DataPointTooltipData(
                                        valueText = valueText,
                                        dateText = dateText,
                                        offset =
                                            androidx.compose.ui.unit.IntOffset(
                                                x = tapOffset.x.toInt() + canvasLeftOffsetPx,
                                                y = tapOffset.y.toInt(),
                                            ),
                                    )
                            },
                        ),
            ) {
                val chartWidth = size.width
                val chartHeight = size.height

                if (session.endTime - session.startTime == 0L) return@Canvas

                // 1. Draw vertical time grid lines
                val gridColor = colorScheme.outlineVariant.copy(alpha = 0.3f)
                labelTimestamps.forEach { ts ->
                    val fraction = (ts - session.startTime).toFloat() / sessionDurationMs
                    val x = fraction * chartWidth
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, chartHeight),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // 2. Draw subtle horizontal guidelines/lanes
                val paddingY = 24.dp.toPx()
                val usableHeight = chartHeight - 2 * paddingY
                val laneSpacing = usableHeight / 3f
                val laneGuidelineColor = colorScheme.onSurface.copy(alpha = 0.05f)

                for (i in 0..3) {
                    val y = paddingY + i * laneSpacing
                    drawLine(
                        color = laneGuidelineColor,
                        start = Offset(0f, y),
                        end = Offset(chartWidth, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // 3. Draw horizontal capsules of consistent thickness for stages
                val capsuleHeight = 12.dp.toPx()
                val radius = capsuleHeight / 2f

                mergedTimeline.forEach { stageData ->
                    val stageIndex = getStageLaneIndex(stageData.stageType)
                    val yOffset = paddingY + stageIndex * laneSpacing

                    val startFraction = (stageData.startTime - session.startTime).toFloat() / sessionDurationMs
                    val endFraction = (stageData.endTime - session.startTime).toFloat() / sessionDurationMs
                    val startX = startFraction * chartWidth
                    val endX = endFraction * chartWidth

                    val rawWidth = endX - startX
                    val width = rawWidth.coerceAtLeast(capsuleHeight)
                    val drawX =
                        if (rawWidth < capsuleHeight) {
                            startX - (capsuleHeight - rawWidth) / 2f
                        } else {
                            startX
                        }

                    val stageEnum =
                        sleepStages.firstOrNull { it.type == stageData.stageType } ?: SleepStage.LIGHT
                    val baseColor = getStageColor(stageEnum, colorScheme)

                    drawRoundRect(
                        color = baseColor,
                        topLeft = Offset(drawX, yOffset - radius),
                        size = Size(width, capsuleHeight),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                }

                // 4. Draw thin semi-transparent transition connectors between stages on top of capsules (prevents gaps)
                mergedTimeline.zipWithNext().forEach { (currentStage, nextStage) ->
                    val currentIndex = getStageLaneIndex(currentStage.stageType)
                    val nextIndex = getStageLaneIndex(nextStage.stageType)

                    val currentY = paddingY + currentIndex * laneSpacing
                    val nextY = paddingY + nextIndex * laneSpacing

                    val currentEndX =
                        ((currentStage.endTime - session.startTime).toFloat() / sessionDurationMs) * chartWidth
                    val nextStartX =
                        ((nextStage.startTime - session.startTime).toFloat() / sessionDurationMs) * chartWidth

                    val midX = (currentEndX + nextStartX) / 2f

                    val nextStageEnum =
                        sleepStages.firstOrNull {
                            it.type == nextStage.stageType
                        } ?: SleepStage.LIGHT
                    val nextColor = getStageColor(nextStageEnum, colorScheme)
                    val connectorColor = nextColor.copy(alpha = 0.3f)
                    val connectorStroke = 2.dp.toPx()

                    val startY = currentY
                    val endY = nextY

                    if (nextStartX > currentEndX) {
                        // Draw horizontal connector on current lane
                        drawLine(
                            color = connectorColor,
                            start = Offset(currentEndX, currentY),
                            end = Offset(midX, currentY),
                            strokeWidth = connectorStroke,
                        )
                        // Draw vertical stem at midX
                        drawLine(
                            color = connectorColor,
                            start = Offset(midX, startY),
                            end = Offset(midX, endY),
                            strokeWidth = connectorStroke,
                        )
                        // Draw horizontal connector on next lane
                        drawLine(
                            color = connectorColor,
                            start = Offset(midX, nextY),
                            end = Offset(nextStartX, nextY),
                            strokeWidth = connectorStroke,
                        )
                    } else {
                        // Standard vertical transition at midX
                        drawLine(
                            color = connectorColor,
                            start = Offset(midX, startY),
                            end = Offset(midX, endY),
                            strokeWidth = connectorStroke,
                        )
                    }
                }

                // 5. Draw glowing selected breathing halo for tapped segment
                activeSegmentIndex?.let { index ->
                    if (index in mergedTimeline.indices) {
                        val stageData = mergedTimeline[index]
                        val stageIndex = getStageLaneIndex(stageData.stageType)
                        val yOffset = paddingY + stageIndex * laneSpacing

                        val startFraction = (stageData.startTime - session.startTime).toFloat() / sessionDurationMs
                        val endFraction = (stageData.endTime - session.startTime).toFloat() / sessionDurationMs
                        val startX = startFraction * chartWidth
                        val endX = endFraction * chartWidth

                        val rawWidth = endX - startX
                        val width = rawWidth.coerceAtLeast(capsuleHeight)
                        val drawX =
                            if (rawWidth < capsuleHeight) {
                                startX - (capsuleHeight - rawWidth) / 2f
                            } else {
                                startX
                            }

                        val stageEnum =
                            sleepStages.firstOrNull { it.type == stageData.stageType } ?: SleepStage.LIGHT
                        val baseColor = getStageColor(stageEnum, colorScheme)

                        val haloPadding = 6.dp.toPx()
                        val haloHeight = capsuleHeight + 2 * haloPadding
                        val haloWidth = width + 2 * haloPadding
                        val haloRadius = haloHeight / 2f

                        drawRoundRect(
                            color = baseColor.copy(alpha = haloAlpha),
                            topLeft = Offset(drawX - haloPadding, yOffset - haloRadius),
                            size = Size(haloWidth, haloHeight),
                            cornerRadius = CornerRadius(haloRadius, haloRadius),
                        )
                    }
                }

                // 6. Draw vertical pointer/highlight indicator line on tap
                if (activeTapOffset != null) {
                    val tapX = activeTapOffset!!.x.coerceIn(0f, chartWidth)

                    drawLine(
                        color = colorScheme.secondary, // Theme-aware color
                        start = Offset(tapX, 0f),
                        end = Offset(tapX, chartHeight),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Time X Axis labels aligned with the Canvas bounds
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp),
            ) {
                val sessionDurationMs = session.endTime - session.startTime
                labelTimestamps.forEach { ts ->
                    val fraction = (ts - session.startTime).toFloat() / sessionDurationMs
                    Text(
                        text = timeFormatter.format(Instant.ofEpochMilli(ts)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(constraints.maxWidth, placeable.height) {
                                    val x = (fraction * constraints.maxWidth).toInt()
                                    placeable.placeRelative(x - placeable.width / 2, 0)
                                }
                            },
                    )
                }
            }
        }
    }

    if (tooltipState != null) {
        DataPointTooltip(
            isVisible = true,
            data = tooltipState!!,
            yOffsetDp = (-28).dp,
            onDismissRequest = {
                tooltipState = null
                activeTapOffset = null
                activeSegmentIndex = null
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SleepStagesChartPreview() {
    val startTime = 1716159600000L // 2024-05-20 01:00:00 UTC
    val endTime = 1716188400000L // 2024-05-20 09:00:00 UTC

    val session =
        SleepSessionData(
            id = "session1",
            startTime = startTime,
            endTime = endTime,
            durationMinutes = 8 * 60,
            efficiency = 0.9f,
            deepSleepMinutes = 60,
            remSleepMinutes = 60,
            lightSleepMinutes = 300,
            awakeMinutes = 60,
            sleepScore = 85f,
            startZoneOffsetSeconds = 0,
            endZoneOffsetSeconds = 0,
            deviceName = "Preview Device",
        )

    val stageTimeline =
        listOf(
            SleepStageData(SleepStageType.AWAKE.value, startTime, startTime + 15 * 60_000L, 15),
            SleepStageData(SleepStageType.LIGHT.value, startTime + 15 * 60_000L, startTime + 60 * 60_000L, 45),
            SleepStageData(SleepStageType.DEEP.value, startTime + 60 * 60_000L, startTime + 120 * 60_000L, 60),
            SleepStageData(SleepStageType.REM.value, startTime + 120 * 60_000L, startTime + 150 * 60_000L, 30),
            SleepStageData(SleepStageType.LIGHT.value, startTime + 150 * 60_000L, startTime + 270 * 60_000L, 120),
            SleepStageData(SleepStageType.DEEP.value, startTime + 270 * 60_000L, startTime + 330 * 60_000L, 60),
            SleepStageData(SleepStageType.REM.value, startTime + 330 * 60_000L, startTime + 360 * 60_000L, 30),
            SleepStageData(SleepStageType.LIGHT.value, startTime + 360 * 60_000L, startTime + 470 * 60_000L, 110),
            SleepStageData(SleepStageType.AWAKE.value, startTime + 470 * 60_000L, startTime + 480 * 60_000L, 10),
        )

    MaterialTheme {
        SleepStagesChart(
            session = session,
            stageTimeline = stageTimeline,
            modifier = Modifier.padding(16.dp),
        )
    }
}
