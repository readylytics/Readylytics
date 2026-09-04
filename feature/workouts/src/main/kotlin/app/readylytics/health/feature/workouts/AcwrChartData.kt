package app.readylytics.health.feature.workouts

import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TrendGranularity

internal data class AcwrChartData(
    val trimpPoints: List<DailyDataPoint>,
    val ratioPoints: List<DailyDataPoint>,
    val tsbPoints: List<DailyDataPoint>,
    val selectedMetric: TrainingLoadMetric,
    val rangeStartMs: Long,
    val rangeDays: Int,
    val granularity: TrendGranularity = TrendGranularity.DAILY,
)
