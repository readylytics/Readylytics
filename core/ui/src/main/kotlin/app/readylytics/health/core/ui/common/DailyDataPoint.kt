package app.readylytics.health.core.ui.common

import androidx.compose.runtime.Immutable

@Immutable
data class DailyDataPoint(
    val dayOffset: Int,
    val value: Float?,
)

fun List<DailyDataPoint>.padToRange(days: Int): List<DailyDataPoint> {
    val byOffset = associateBy { it.dayOffset }
    return (0 until days).map { offset -> byOffset[offset] ?: DailyDataPoint(offset, null) }
}
