package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget

/** Series index the current-week line is registered under in the chart's [LineCartesianLayer]. */
internal const val CURRENT_WEEK_SERIES_INDEX = 0

/** Series index the previous-week line is registered under in the chart's [LineCartesianLayer]. */
internal const val PREVIOUS_WEEK_SERIES_INDEX = 1

/**
 * Remembers a [CartesianMarkerVisibilityListener] for the "This week vs last week" chart.
 *
 * Both lines share a single [com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer]
 * and y-axis, so Vico reports one [LineCartesianLayerMarkerTarget] per tapped day offset, whose
 * `points` list holds one entry per series that has data at that offset (a future offset only
 * ever has the previous-week entry, since the current-week series omits points past today).
 * Each point's `entry.seriesIndex` disambiguates which line it belongs to, and `entry.y` is the
 * exact value fed into the model — no separate lookup map is needed.
 *
 * `onHidden` is intentionally a no-op: the tooltip remains visible until explicitly dismissed,
 * consistent with [AcwrChart]/[TrendChart] behaviour.
 */
@Composable
internal fun rememberWeeklyVolumeMarkerVisibilityListener(
    onStateChanged: (WeeklyVolumeSelectedState) -> Unit,
): CartesianMarkerVisibilityListener {
    val currentOnStateChanged = rememberUpdatedState(onStateChanged)
    return remember {
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
                val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget ?: return
                val currentPoint = target.points.firstOrNull { it.entry.seriesIndex == CURRENT_WEEK_SERIES_INDEX }
                val previousPoint =
                    target.points.firstOrNull { it.entry.seriesIndex == PREVIOUS_WEEK_SERIES_INDEX } ?: return

                currentOnStateChanged.value(
                    WeeklyVolumeSelectedState(
                        dayOffset = target.x.toInt(),
                        currentMinutes = currentPoint?.entry?.y?.toInt(),
                        previousMinutes = previousPoint.entry.y.toInt(),
                        canvasX = target.canvasX,
                        canvasY = currentPoint?.canvasY ?: previousPoint.canvasY,
                    ),
                )
            }
        }
    }
}
