package app.readylytics.health.feature.vitals.overview

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.padToRange
import app.readylytics.health.core.ui.model.Baselines
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.ZoneBand
import app.readylytics.health.domain.model.hrvZoneBands
import app.readylytics.health.domain.model.rhrZoneBands
import app.readylytics.health.domain.model.spo2ZoneBands
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.util.UnitConverter
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

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
    val baselineHrv: Float?,
    val baselineRhr: Int?,
    val baselineBodyTemp: Float?,
    val bodyTempUnitSystem: UnitSystem,
    val hrvZoneBands: List<ZoneBand>?,
    val rhrZoneBands: List<ZoneBand>?,
    val spo2ZoneBands: List<ZoneBand>,
    val hrvOptimalThreshold: Float,
    val hrvWarningThreshold: Float,
    val rhrOptimalThreshold: Float,
    val rhrWarningThreshold: Float,
) {
    companion object {
        fun empty(): VitalsPresentationState =
            VitalsPresentationState(
                baselineHrv = null,
                baselineRhr = null,
                baselineBodyTemp = null,
                bodyTempUnitSystem = UnitSystem.METRIC,
                hrvZoneBands = null,
                rhrZoneBands = null,
                spo2ZoneBands = spo2ZoneBands(),
                hrvOptimalThreshold = 0.9f,
                hrvWarningThreshold = 0.8f,
                rhrOptimalThreshold = 1.05f,
                rhrWarningThreshold = 1.15f,
            )
    }
}

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
    val isCalibrating: Boolean,
    val isLoading: Boolean,
)

fun VitalsUiState.chartInputs(): VitalsChartInputs =
    VitalsChartInputs(
        chartSeries = chartSeries,
        rangeStartMs = rangeStartMs,
        selectedRange = selectedRange,
        presentation = presentation,
        isCalibrating = latestSummary?.isCalibrating ?: false,
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
        spo2 = points { it.avgSleepingSpo2?.roundToInt()?.toFloat() },
        bodyTemp =
            points {
                it.avgSleepingBodyTemp?.let { celsius ->
                    UnitConverter.celsiusToDisplayTemperature(celsius, unitSystem)
                }
            },
    )
}

internal fun buildVitalsPresentationState(
    baselines: Baselines,
    hrvOptimalThreshold: Float,
    hrvWarningThreshold: Float,
    rhrOptimalThreshold: Float,
    rhrWarningThreshold: Float,
    unitSystem: UnitSystem,
): VitalsPresentationState {
    val hrvBands =
        baselines.hrv?.let { baseline ->
            hrvZoneBands(
                optimalMin = hrvOptimalThreshold * baseline,
                neutralMin = hrvWarningThreshold * baseline,
                warningMin = (2f * hrvWarningThreshold - 1f) * baseline,
            )
        }
    val rhrBands =
        baselines.rhr?.toFloat()?.let { baseline ->
            rhrZoneBands(
                optimalMax = rhrOptimalThreshold * baseline,
                neutralMax = rhrWarningThreshold * baseline,
                warningMax = rhrWarningThreshold * 1.3f * baseline,
            )
        }

    return VitalsPresentationState(
        baselineHrv = baselines.hrv,
        baselineRhr = baselines.rhr,
        baselineBodyTemp = baselines.bodyTemp?.let { UnitConverter.celsiusToDisplayTemperature(it, unitSystem) },
        bodyTempUnitSystem = unitSystem,
        hrvZoneBands = hrvBands,
        rhrZoneBands = rhrBands,
        spo2ZoneBands = spo2ZoneBands(),
        hrvOptimalThreshold = hrvOptimalThreshold,
        hrvWarningThreshold = hrvWarningThreshold,
        rhrOptimalThreshold = rhrOptimalThreshold,
        rhrWarningThreshold = rhrWarningThreshold,
    )
}
