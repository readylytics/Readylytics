package app.readylytics.health.feature.vitals.overview

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.padToRange
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.PersonalBaselineAssessment
import app.readylytics.health.domain.model.Spo2Assessment
import app.readylytics.health.domain.model.assessHrv
import app.readylytics.health.domain.model.assessRhr
import app.readylytics.health.domain.model.assessSpo2
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.util.UnitConverter
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Immutable
data class VitalsChartSeries(
    val hrv: List<DailyDataPoint>,
    val rhr: List<DailyDataPoint>,
    val spo2: List<DailyDataPoint>,
    val bodyTemp: List<DailyDataPoint>,
)

internal data class VitalsRangeWindow(
    val fromMs: Long,
    val startDate: LocalDate,
    val selectedMidnightMs: Long,
    val isToday: Boolean,
)

internal fun resolveVitalsRangeWindow(
    range: TimeRange,
    selectedDate: LocalDate,
    scoringZone: ZoneId,
    today: LocalDate = LocalDate.now(scoringZone),
): VitalsRangeWindow {
    val startDate = selectedDate.minusDays(range.days.toLong() - 1)
    return VitalsRangeWindow(
        fromMs = startDate.atStartOfDay(scoringZone).toInstant().toEpochMilli(),
        startDate = startDate,
        selectedMidnightMs = selectedDate.atStartOfDay(scoringZone).toInstant().toEpochMilli(),
        isToday = selectedDate == today,
    )
}

@Immutable
data class VitalsPresentationState(
    val hrv: PersonalBaselineAssessment,
    val rhr: PersonalBaselineAssessment,
    val spo2: Spo2Assessment,
    val baselineBodyTemp: Float?,
    val bodyTempUnitSystem: UnitSystem,
) {
    companion object {
        fun empty(): VitalsPresentationState =
            buildVitalsPresentationState(
                metrics = null,
                summary = null,
                prefs = UserPreferences(),
                bodyTemperatureBaselineCelsius = null,
            )
    }
}

private fun presentationStateFromAssessments(
    hrv: PersonalBaselineAssessment,
    rhr: PersonalBaselineAssessment,
    spo2: Spo2Assessment,
    bodyTemperatureBaselineCelsius: Float?,
    unitSystem: UnitSystem,
): VitalsPresentationState =
    VitalsPresentationState(
        hrv = hrv,
        rhr = rhr,
        spo2 = spo2,
        baselineBodyTemp =
            bodyTemperatureBaselineCelsius?.let {
                UnitConverter.celsiusToDisplayTemperature(it, unitSystem)
            },
        bodyTempUnitSystem = unitSystem,
    )

/**
 * The subset of [VitalsUiState] the four trend charts read. Passing only this into
 * [VitalsTrendSection] means gauge-only or refresh-only state changes never recompose the chart
 * subtree — mirrors [app.readylytics.health.feature.dashboard.DashboardUiState.cardInputs].
 */
@Immutable
data class VitalsChartInputs(
    val chartSeries: VitalsChartSeries,
    val rangeStartMs: Long,
    val selectedRange: TimeRange,
    val presentation: VitalsPresentationState,
    val isLoading: Boolean,
)

fun VitalsUiState.chartInputs(): VitalsChartInputs =
    VitalsChartInputs(
        chartSeries = chartSeries,
        rangeStartMs = rangeStartMs,
        selectedRange = selectedRange,
        presentation = presentation,
        isLoading = isLoading,
    )

internal fun buildVitalsChartSeries(
    summaries: List<DailySummary>,
    startDate: LocalDate,
    rangeDays: Int,
    unitSystem: UnitSystem,
    endDate: LocalDate = startDate.plusDays(rangeDays.toLong() - 1),
): VitalsChartSeries {
    fun points(value: (DailySummary) -> Float?): List<DailyDataPoint> =
        summaries
            .filter { it.date in startDate..endDate }
            .mapNotNull { summary ->
                value(summary)?.let {
                    DailyDataPoint(ChronoUnit.DAYS.between(startDate, summary.date).toInt(), it)
                }
            }.sortedBy(DailyDataPoint::dayOffset)
            .padToRange(rangeDays)

    return VitalsChartSeries(
        hrv = points { it.nocturnalHrv?.toFloat() },
        rhr = points { it.restingHeartRate?.toFloat() },
        spo2 = points { it.avgSleepingSpo2 },
        bodyTemp =
            points {
                it.avgSleepingBodyTemp?.let { celsius ->
                    UnitConverter.celsiusToDisplayTemperature(celsius, unitSystem)
                }
            },
    )
}

internal fun buildVitalsPresentationState(
    metrics: DailyMetrics?,
    summary: DailySummary?,
    prefs: UserPreferences,
    bodyTemperatureBaselineCelsius: Float? = null,
): VitalsPresentationState {
    val selectedMetrics = metrics ?: summary?.let { DailyMetricsMapper.toMetrics(it, prefs) }
    val hrvAssessment =
        assessHrv(
            value = selectedMetrics?.nocturnalHrvRounded ?: summary?.nocturnalHrv,
            baseline = selectedMetrics?.hrvBaselineRounded,
            optimalRatio = prefs.hrvOptimalThreshold,
            warningRatio = prefs.hrvWarningThreshold,
        )
    val rhrAssessment =
        assessRhr(
            value = selectedMetrics?.nocturnalRhrRounded ?: summary?.restingHeartRate,
            baseline = selectedMetrics?.rhrBaselineRounded,
            optimalRatio = prefs.rhrOptimalThreshold,
            warningRatio = prefs.rhrWarningThreshold,
        )
    val spo2Assessment = assessSpo2(summary?.avgSleepingSpo2)

    return presentationStateFromAssessments(
        hrv = hrvAssessment,
        rhr = rhrAssessment,
        spo2 = spo2Assessment,
        bodyTemperatureBaselineCelsius = bodyTemperatureBaselineCelsius,
        unitSystem = prefs.unitSystem,
    )
}
