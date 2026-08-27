package app.readylytics.health.feature.sleep

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.model.DailyMetrics
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepStageData
import app.readylytics.health.core.model.domain.sleep.SleepChartConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepTrendDay
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TimeRange
import java.time.LocalDate
import java.time.ZoneId

@Immutable
data class SleepUiState(
    val latestSummary: DailySummary? = null,
    val latestMetrics: DailyMetrics? = null,
    val latestSession: SleepSessionData? = null,
    val stageTimeline: List<SleepStageData> = emptyList(),
    val sleepHrSamples: List<HeartRateRecordData> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedTrendRange: TimeRange = TimeRange.SEVEN_DAYS,
    val trendStartOffsetPoints: List<DailyDataPoint> = emptyList(),
    val trendDurationSpanPoints: List<DailyDataPoint> = emptyList(),
    val trendActualDurationPoints: List<DailyDataPoint> = emptyList(),
    val trendDays: List<SleepTrendDay> = emptyList(),
    val trendRangeStartMs: Long = 0,
    val trendScoringZoneId: ZoneId = ZoneId.systemDefault(),
    val trendStartOffsetSummary: PeriodAverageSummary? = null,
    val trendDurationSpanSummary: PeriodAverageSummary? = null,
    val trendActualDurationSummary: PeriodAverageSummary? = null,
    val goalSleepHours: Float = SettingsDefaults.GOAL_SLEEP_HOURS,
    val sleepTimeGaugeData: SleepTimeGaugeData =
        buildSleepTimeGaugeData(
            session = null,
            summary = null,
            goalSleepHours = SettingsDefaults.GOAL_SLEEP_HOURS,
        ),
    val yesterdaySleepScoreRounded: Int? = null,
    val sleepTopCardConfigurations: List<SleepTopCardConfiguration> = SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS,
    val isManagingSleepTopCards: Boolean = false,
    val sleepChartConfigurations: List<SleepChartConfiguration> = SettingsDefaults.DEFAULT_SLEEP_CHARTS,
    val isManagingSleepCharts: Boolean = false,
    val sleepMetricCardConfigurations: List<SleepMetricCardConfiguration> = SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS,
    val isManagingSleepMetricCards: Boolean = false,
) {
    val isManagingSleepLayout: Boolean
        get() = isManagingSleepTopCards || isManagingSleepCharts || isManagingSleepMetricCards
}
