package app.readylytics.health.feature.vitals.overview

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.padToRange
import app.readylytics.health.core.ui.model.Baselines
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.ZoneBand
import app.readylytics.health.domain.model.hrvZoneBands
import app.readylytics.health.domain.model.rhrZoneBands
import app.readylytics.health.domain.model.spo2ZoneBands
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Immutable
data class VitalsChartSeries(
    val hrv: List<DailyDataPoint>,
    val rhr: List<DailyDataPoint>,
    val spo2: List<DailyDataPoint>,
)

@Immutable
data class VitalsPresentationState(
    val baselineHrv: Float?,
    val baselineRhr: Int?,
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

internal fun buildVitalsChartSeries(
    summaries: List<DailySummary>,
    startDate: LocalDate,
    rangeDays: Int,
): VitalsChartSeries {
    fun points(value: (DailySummary) -> Float?): List<DailyDataPoint> =
        summaries
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
    )
}

internal fun buildVitalsPresentationState(
    baselines: Baselines,
    hrvOptimalThreshold: Float,
    hrvWarningThreshold: Float,
    rhrOptimalThreshold: Float,
    rhrWarningThreshold: Float,
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
        hrvZoneBands = hrvBands,
        rhrZoneBands = rhrBands,
        spo2ZoneBands = spo2ZoneBands(),
        hrvOptimalThreshold = hrvOptimalThreshold,
        hrvWarningThreshold = hrvWarningThreshold,
        rhrOptimalThreshold = rhrOptimalThreshold,
        rhrWarningThreshold = rhrWarningThreshold,
    )
}
