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
                    containerHeightPx = size.height.toFloat()
                }
                .pointerInput(points, rangeDays) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEvent.Type.Press) {
                                val position = event.changes[0].position
                                val tapX = position.x

                                // Map screen x to dayOffset
                                if (containerWidthPx > 0) {
                                    val relativeX = tapX / containerWidthPx
                                    val dayOffset =
                                        (relativeX * (rangeDays - 1)).toInt()
                                            .coerceIn(0, rangeDays - 1)

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

    // First try exact or nearby match within tolerance
    val nearby =
        validPoints.filter {
            abs(it.dayOffset - dayOffset) <= tolerance
        }

    return if (nearby.isNotEmpty()) {
        nearby.minByOrNull { abs(it.dayOffset - dayOffset) }
    } else {
        // If no points within tolerance, return closest
        validPoints.minByOrNull { abs(it.dayOffset - dayOffset) }
    }
}
