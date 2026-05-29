package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.ColumnCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget

/**
 * Remembers a [CartesianMarkerVisibilityListener] tailored for the dual-layer ACWR chart.
 *
 * On each touch update the listener:
 * 1. Inspects all [CartesianMarker.Target]s in the list.
 * 2. Extracts canvas positions from both [ColumnCartesianLayerMarkerTarget] (TRIMP bar top)
 *    and [LineCartesianLayerMarkerTarget] (Strain Ratio dot).
 * 3. Resolves the day-offset to actual data values from [trimpPoints] / [ratioPoints].
 * 4. Fires [onStateChanged] with the composed [AcwrSelectedState].
 *
 * `onHidden` is intentionally a no-op: the tooltip remains visible until the user explicitly
 * dismisses it (consistent with [TrendChart] and [HrChart] behaviour).
 *
 * @param trimpPoints    Daily TRIMP data used to look up the value for the selected day.
 * @param ratioPoints    Daily Strain Ratio data used to look up the value for the selected day.
 * @param onStateChanged Callback invoked with a fully resolved [AcwrSelectedState].
 */
@Composable
fun rememberAcwrMarkerVisibilityListener(
    trimpPoints: List<DailyDataPoint>,
    ratioPoints: List<DailyDataPoint>,
    onStateChanged: (AcwrSelectedState) -> Unit,
): CartesianMarkerVisibilityListener {
    // Pre-map the data points by dayOffset to avoid O(n) linear searches on every touch frame.
    val trimpMap = remember(trimpPoints) { trimpPoints.associateBy { it.dayOffset } }
    val ratioMap = remember(ratioPoints) { ratioPoints.associateBy { it.dayOffset } }

    // Capture the latest callback without invalidating the remembered listener object.
    // Using rememberUpdatedState ensures the listener always calls the current lambda
    // even if it changes between recompositions (e.g. due to selectedState updates).
    val currentOnStateChanged = rememberUpdatedState(onStateChanged)

    return remember(trimpMap, ratioMap) {
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
                var barCanvasYTop: Float? = null
                var lineCanvasY: Float? = null

                for (target in targets) {
                    when (target) {
                        is ColumnCartesianLayerMarkerTarget -> {
                            if (canvasX == null) canvasX = target.canvasX
                            if (dayOffset == null) dayOffset = target.x.toInt()
                            barCanvasYTop = target.columns.firstOrNull()?.canvasY
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

                val trimpValue = trimpMap[resolvedOffset]?.value
                val ratioValue = ratioMap[resolvedOffset]?.value

                currentOnStateChanged.value(
                    AcwrSelectedState(
                        dayOffset = resolvedOffset,
                        trimpValue = trimpValue,
                        strainRatioValue = ratioValue,
                        canvasX = resolvedX,
                        barCanvasYTop = barCanvasYTop,
                        lineCanvasY = lineCanvasY,
                    ),
                )
            }
        }
    }
}
