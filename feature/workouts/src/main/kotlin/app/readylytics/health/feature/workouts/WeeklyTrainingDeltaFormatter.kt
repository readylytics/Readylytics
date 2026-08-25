package app.readylytics.health.feature.workouts

import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure formatting for the Weekly training cards' values and comparisons. Duration units follow
 *  the app-wide h/m convention ("42m", "3h 42m"); deltas always carry an explicit sign. */
internal object WeeklyTrainingDeltaFormatter {
    fun formatDuration(minutes: Int): String =
        if (minutes < 60) {
            "${minutes}m"
        } else {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
        }

    /** "+24m (+12%)"; percent omitted when [percentChange] is null (previous week had no data). */
    fun formatDurationDelta(
        deltaMinutes: Int,
        percentChange: Float?,
    ): String {
        val percent =
            percentChange
                ?.roundToInt()
                ?.let { if (it > 0) "+$it" else "$it" }
                ?.let { " ($it%)" }
                .orEmpty()
        return signed(deltaMinutes, formatDuration(abs(deltaMinutes))) + percent
    }

    fun formatCountDelta(delta: Int): String = signed(delta, abs(delta).toString())

    private fun signed(
        delta: Int,
        formattedAbsolute: String,
    ): String =
        when {
            delta > 0 -> "+$formattedAbsolute"
            delta < 0 -> "-$formattedAbsolute"
            else -> "0"
        }
}
