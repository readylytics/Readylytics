package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.ui.common.DailyDataPoint

@Immutable
internal data class TrendChartRenderData(
    val validPoints: List<DailyDataPoint>,
    val pointByDayOffset: Map<Int, DailyDataPoint>,
    val calculatedBaseline: Float?,
    val minimum: Float?,
    val maximum: Float?,
)

internal fun buildTrendChartRenderData(points: List<DailyDataPoint>): TrendChartRenderData {
    val validPoints = points.filter { it.value != null }
    val values = validPoints.map { requireNotNull(it.value) }
    val sortedValues = values.sorted()
    val midpoint = sortedValues.size / 2
    val median =
        when {
            sortedValues.isEmpty() -> null
            sortedValues.size % 2 == 0 -> (sortedValues[midpoint - 1] + sortedValues[midpoint]) / 2f
            else -> sortedValues[midpoint]
        }

    return TrendChartRenderData(
        validPoints = validPoints,
        pointByDayOffset = validPoints.associateBy(DailyDataPoint::dayOffset),
        calculatedBaseline = median,
        minimum = values.minOrNull(),
        maximum = values.maxOrNull(),
    )
}
