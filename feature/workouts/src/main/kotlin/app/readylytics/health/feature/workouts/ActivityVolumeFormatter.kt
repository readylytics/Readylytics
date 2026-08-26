package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.util.UnitConverter
import app.readylytics.health.core.scoring.domain.workouts.weekly.ActivityMetricType
import kotlin.math.roundToInt

/** Pure formatting for Activity volume rows. Distance follows the user's unit system; duration
 *  follows the app-wide h/m convention. Percent deltas carry an explicit sign. */
internal object ActivityVolumeFormatter {
    /** Meters for [ActivityMetricType.DISTANCE] ("—" when <= 0), minutes for DURATION ("1h 15m"). */
    fun formatValue(
        value: Float,
        metricType: ActivityMetricType,
        unitSystem: UnitSystem,
    ): String =
        when (metricType) {
            ActivityMetricType.DISTANCE -> UnitConverter.formatDistance(value, unitSystem)
            ActivityMetricType.DURATION -> WeeklyTrainingDeltaFormatter.formatDuration(value.roundToInt())
        }

    /** Signed percent delta ("+24%" / "-18%"), or null when undefined — the UI shows "New" then
     *  because the previous week had zero volume for that type. */
    fun formatPercentDelta(percentChange: Float?): String? =
        percentChange
            ?.roundToInt()
            ?.let { if (it > 0) "+$it%" else "$it%" }
}
