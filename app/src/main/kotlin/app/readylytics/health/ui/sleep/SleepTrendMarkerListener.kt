package app.readylytics.health.ui.sleep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.readylytics.health.ui.common.DailyDataPoint
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.ColumnCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget

/**
 * Remembers a [CartesianMarkerVisibilityListener] tailored for the sleep trend chart.
 */
@Composable
fun rememberSleepTrendMarkerVisibilityListener(
    startOffsetPoints: List<DailyDataPoint>,
    durationSpanPoints: List<DailyDataPoint>,
    actualDurationPoints: List<DailyDataPoint>,
    onStateChanged: (SleepTrendSelectedState) -> Unit,
): CartesianMarkerVisibilityListener {
    val startOffsetMap = remember(startOffsetPoints) { startOffsetPoints.associateBy { it.dayOffset } }
    val durationSpanMap = remember(durationSpanPoints) { durationSpanPoints.associateBy { it.dayOffset } }
    val actualDurationMap = remember(actualDurationPoints) { actualDurationPoints.associateBy { it.dayOffset } }

    val currentOnStateChanged = rememberUpdatedState(onStateChanged)

    return remember(startOffsetMap, durationSpanMap, actualDurationMap) {
        object : CartesianMarkerVisibilityListener {
            override fun onShown(
                marker: CartesianMarker,
                targets: List<CartesianMarker.Target>,
            ) {
                handleTargets(targets)
            }

            override fun onUpdated(
                marker: CartesianMarker,
                targets: List<CartesianMarker.Target>,
            ) {
                handleTargets(targets)
            }

            override fun onHidden(marker: CartesianMarker) {
                // Intentionally empty: tooltip stays until explicitly dismissed.
            }

            private fun handleTargets(targets: List<CartesianMarker.Target>) {
                var canvasX: Float? = null
                var dayOffset: Int? = null
                var barCanvasYBottom: Float? = null
                var barCanvasYTop: Float? = null
                var lineCanvasY: Float? = null

                for (target in targets) {
                    when (target) {
                        is ColumnCartesianLayerMarkerTarget -> {
                            if (canvasX == null) canvasX = target.canvasX
                            if (dayOffset == null) dayOffset = target.x.toInt()
                            // Series 0 is transparent bottom, Series 1 is the colored sleep bar
                            barCanvasYBottom = target.columns.getOrNull(0)?.canvasY
                            barCanvasYTop = target.columns.getOrNull(1)?.canvasY
                        }
                        is LineCartesianLayerMarkerTarget -> {
                            if (canvasX == null) canvasX = target.canvasX
                            if (dayOffset == null) dayOffset = target.x.toInt()
                            lineCanvasY = target.points.firstOrNull()?.canvasY
                        }
                    }
                }

                val resolvedX = canvasX ?: return
                val resolvedOffset = dayOffset ?: return

                val startVal = startOffsetMap[resolvedOffset]?.value
                val spanVal = durationSpanMap[resolvedOffset]?.value
                val actualVal = actualDurationMap[resolvedOffset]?.value

                currentOnStateChanged.value(
                    SleepTrendSelectedState(
                        dayOffset = resolvedOffset,
                        startOffsetValue = startVal,
                        durationSpanValue = spanVal,
                        actualDurationValue = actualVal,
                        canvasX = resolvedX,
                        barCanvasYTop = barCanvasYTop,
                        barCanvasYBottom = barCanvasYBottom,
                        lineCanvasY = lineCanvasY,
                    ),
                )
            }
        }
    }
}
