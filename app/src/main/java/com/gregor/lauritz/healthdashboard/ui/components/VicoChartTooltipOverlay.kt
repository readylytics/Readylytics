package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import kotlin.math.abs

@Composable
fun VicoChartTooltipOverlay(
    points: List<DailyDataPoint>,
    rangeDays: Int,
    onDataPointSelected: (dayOffset: Int, value: Float) -> Unit,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
) {
    var containerWidthPx by remember { mutableStateOf(0f) }
    val validPoints = remember(points) { points.filter { it.value != null } }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(chartHeight)
                .onSizeChanged { size ->
                    containerWidthPx = size.width.toFloat()
                }
                .pointerInput(points, rangeDays) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEvent.Type.Press) {
                                val position = event.changes[0].position
                                val tapX = position.x

                                // Map screen x to dayOffset
                                if (containerWidthPx > 0 && rangeDays > 0) {
                                    val relativeX = tapX / containerWidthPx
                                    val maxDayOffset = (rangeDays - 1).coerceAtLeast(0)
                                    val dayOffset =
                                        (relativeX * maxDayOffset).toInt()
                                            .coerceIn(0, maxDayOffset)

                                    // Find the closest point with a non-null value
                                    val nearestPoint =
                                        findNearestPoint(validPoints, dayOffset, tolerance = 2)
                                    if (nearestPoint != null) {
                                        onDataPointSelected(nearestPoint.dayOffset, nearestPoint.value)
                                    }
                                }
                            }
                        }
                    }
                },
    )
}

private fun findNearestPoint(
    validPoints: List<DailyDataPoint>,
    dayOffset: Int,
    tolerance: Int = 2,
): DailyDataPoint? {
    if (validPoints.isEmpty()) return null

    return validPoints.minWithOrNull(
        compareBy { point ->
            val distance = abs(point.dayOffset - dayOffset)
            Pair(if (distance > tolerance) 1 else 0, distance)
        }
    )
}
