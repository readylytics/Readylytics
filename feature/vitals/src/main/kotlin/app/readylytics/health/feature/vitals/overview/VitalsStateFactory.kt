package app.readylytics.health.feature.vitals.overview

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.domain.model.BodyTemperatureAssessment
import app.readylytics.health.core.model.domain.model.BucketZoneBands
import app.readylytics.health.core.model.domain.model.DailyMetrics
import app.readylytics.health.core.model.domain.model.DailyMetricsMapper
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.PersonalBaselineAssessment
import app.readylytics.health.core.model.domain.model.Spo2Assessment
import app.readylytics.health.core.model.domain.model.ZoneBand
import app.readylytics.health.core.model.domain.model.assessBodyTemperature
import app.readylytics.health.core.model.domain.model.assessHrv
import app.readylytics.health.core.model.domain.model.assessRhr
import app.readylytics.health.core.model.domain.model.assessSpo2
import app.readylytics.health.core.model.domain.model.hrvZoneBandsForBaseline
import app.readylytics.health.core.model.domain.model.rhrZoneBandsForBaseline
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.UnitConverter
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.aggregateByRange
import app.readylytics.health.core.ui.common.bucketBy
import app.readylytics.health.core.ui.common.bucketByFixedSize
import app.readylytics.health.core.ui.common.bucketLengthDays
import app.readylytics.health.core.ui.common.bucketStartForDate
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
    val hrvPeriodSummary: PeriodAverageSummary? = null,
    val rhrPeriodSummary: PeriodAverageSummary? = null,
    val spo2PeriodSummary: PeriodAverageSummary? = null,
    val bodyTempPeriodSummary: PeriodAverageSummary? = null,
    val historicalRhrBaseline: List<DailyDataPoint> = emptyList(),
    val historicalHrvBaseline: List<DailyDataPoint> = emptyList(),
    val historicalRhrBaselineAverage: Int? = null,
    val historicalHrvBaselineAverage: Int? = null,
    val historicalRhrZoneBands: List<ZoneBand> = emptyList(),
    val historicalHrvZoneBands: List<ZoneBand> = emptyList(),
    val historicalRhrBucketZoneBands: List<BucketZoneBands> = emptyList(),
    val historicalHrvBucketZoneBands: List<BucketZoneBands> = emptyList(),
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
    val bodyTemp: BodyTemperatureAssessment,
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
    bodyTemp: BodyTemperatureAssessment,
    unitSystem: UnitSystem,
): VitalsPresentationState =
    VitalsPresentationState(
        hrv = hrv,
        rhr = rhr,
        spo2 = spo2,
        bodyTemp = bodyTemp,
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
    range: TimeRange,
    unitSystem: UnitSystem,
    rhrBaselineOverride: Float? = null,
    hrvBaselineOverride: Float? = null,
    rhrOptimalThreshold: Float = 1.1f,
    rhrWarningThreshold: Float = 1.3f,
    hrvOptimalThreshold: Float = 1.1f,
    hrvWarningThreshold: Float = 1.1f,
    endDate: LocalDate = startDate.plusDays(range.days.toLong() - 1),
): VitalsChartSeries {
    fun realPoints(value: (DailySummary) -> Float?): List<DailyDataPoint> =
        summaries
            .filter { it.date in startDate..endDate }
            .mapNotNull { summary ->
                value(summary)?.let {
                    DailyDataPoint(ChronoUnit.DAYS.between(startDate, summary.date).toInt(), it)
                }
            }.sortedBy(DailyDataPoint::dayOffset)

    val (hrvPoints, hrvSummary) =
        realPoints { it.nocturnalHrv?.toFloat() }
            .aggregateByRange(range.granularity, startDate, endDate, range.days)
    val (rhrPoints, rhrSummary) =
        realPoints { it.restingHeartRate?.toFloat() }
            .aggregateByRange(range.granularity, startDate, endDate, range.days)
    val (spo2Points, spo2Summary) =
        realPoints { it.avgSleepingSpo2 }
            .aggregateByRange(range.granularity, startDate, endDate, range.days)
    val (bodyTempPoints, bodyTempSummary) =
        realPoints {
            it.avgSleepingBodyTemp?.let { celsius ->
                UnitConverter.celsiusToDisplayTemperature(celsius, unitSystem)
            }
        }.aggregateByRange(range.granularity, startDate, endDate, range.days, valueDecimalPlaces = 1)

    val rawRhrBaseline: List<DailyDataPoint> =
        realPoints { summary ->
            DailyMetricsMapper.rhrBaselineRounded(summary, rhrBaselineOverride)?.toFloat()
        }
    val rawHrvBaseline: List<DailyDataPoint> =
        realPoints { summary ->
            DailyMetricsMapper.hrvBaselineRounded(summary, hrvBaselineOverride)?.toFloat()
        }

    val rangeEndOffsetExclusive = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
    val overlayBucketSizeDays = baselineOverlayBucketSizeDays(range)

    val historicalRhrBaselineAverage: Int? =
        rawRhrBaseline
            .mapNotNull { it.value }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.roundToInt()
    val historicalHrvBaselineAverage: Int? =
        rawHrvBaseline
            .mapNotNull { it.value }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.roundToInt()
    val historicalRhrZoneBands: List<ZoneBand> =
        historicalRhrBaselineAverage?.let {
            rhrZoneBandsForBaseline(it, rhrOptimalThreshold, rhrWarningThreshold)
        } ?: emptyList()
    val historicalHrvZoneBands: List<ZoneBand> =
        historicalHrvBaselineAverage?.let {
            hrvZoneBandsForBaseline(it, hrvOptimalThreshold, hrvWarningThreshold)
        } ?: emptyList()

    val historicalRhrBaseline: List<DailyDataPoint>
    val historicalRhrBucketZoneBands: List<BucketZoneBands>
    if (overlayBucketSizeDays != null) {
        val rhrBuckets = rawRhrBaseline.bucketByFixedSize(overlayBucketSizeDays, rangeEndOffsetExclusive)
        historicalRhrBaseline = rhrBuckets.map { DailyDataPoint(it.lastDayOffset, it.value) }
        historicalRhrBucketZoneBands =
            rhrBuckets.map { bucket ->
                BucketZoneBands(
                    startDayOffset = bucket.startDayOffset,
                    endDayOffset = bucket.endDayOffsetExclusive,
                    bands =
                        rhrZoneBandsForBaseline(
                            bucket.value.roundToInt(),
                            rhrOptimalThreshold,
                            rhrWarningThreshold,
                        ),
                )
            }
    } else {
        // bucket.dayOffset is the bucket MIDPOINT (see bucketMidpointOffset), not the bucket start,
        // so the true bucket boundary is re-derived via bucketStartForDate rather than used directly.
        historicalRhrBaseline = rawRhrBaseline.bucketBy(range.granularity, startDate, endDate)
        historicalRhrBucketZoneBands =
            historicalRhrBaseline.mapNotNull { bucket ->
                bucket.value?.roundToInt()?.let { baseline ->
                    val bucketStart =
                        bucketStartForDate(startDate.plusDays(bucket.dayOffset.toLong()), range.granularity)
                    val startOffset =
                        ChronoUnit.DAYS
                            .between(startDate, bucketStart)
                            .toInt()
                            .coerceAtLeast(0)
                    val endOffset =
                        (startOffset + bucketLengthDays(bucketStart, range.granularity))
                            .coerceAtMost(rangeEndOffsetExclusive)
                    BucketZoneBands(
                        startDayOffset = startOffset,
                        endDayOffset = endOffset,
                        bands = rhrZoneBandsForBaseline(baseline, rhrOptimalThreshold, rhrWarningThreshold),
                    )
                }
            }
    }

    val historicalHrvBaseline: List<DailyDataPoint>
    val historicalHrvBucketZoneBands: List<BucketZoneBands>
    if (overlayBucketSizeDays != null) {
        val hrvBuckets = rawHrvBaseline.bucketByFixedSize(overlayBucketSizeDays, rangeEndOffsetExclusive)
        historicalHrvBaseline = hrvBuckets.map { DailyDataPoint(it.lastDayOffset, it.value) }
        historicalHrvBucketZoneBands =
            hrvBuckets.map { bucket ->
                BucketZoneBands(
                    startDayOffset = bucket.startDayOffset,
                    endDayOffset = bucket.endDayOffsetExclusive,
                    bands =
                        hrvZoneBandsForBaseline(
                            bucket.value.roundToInt(),
                            hrvOptimalThreshold,
                            hrvWarningThreshold,
                        ),
                )
            }
    } else {
        historicalHrvBaseline = rawHrvBaseline.bucketBy(range.granularity, startDate, endDate)
        historicalHrvBucketZoneBands =
            historicalHrvBaseline.mapNotNull { bucket ->
                bucket.value?.roundToInt()?.let { baseline ->
                    val bucketStart =
                        bucketStartForDate(startDate.plusDays(bucket.dayOffset.toLong()), range.granularity)
                    val startOffset =
                        ChronoUnit.DAYS
                            .between(startDate, bucketStart)
                            .toInt()
                            .coerceAtLeast(0)
                    val endOffset =
                        (startOffset + bucketLengthDays(bucketStart, range.granularity))
                            .coerceAtMost(rangeEndOffsetExclusive)
                    BucketZoneBands(
                        startDayOffset = startOffset,
                        endDayOffset = endOffset,
                        bands = hrvZoneBandsForBaseline(baseline, hrvOptimalThreshold, hrvWarningThreshold),
                    )
                }
            }
    }

    return VitalsChartSeries(
        hrv = hrvPoints,
        rhr = rhrPoints,
        spo2 = spo2Points,
        bodyTemp = bodyTempPoints,
        hrvPeriodSummary = hrvSummary,
        rhrPeriodSummary = rhrSummary,
        spo2PeriodSummary = spo2Summary,
        bodyTempPeriodSummary = bodyTempSummary,
        historicalRhrBaseline = historicalRhrBaseline,
        historicalHrvBaseline = historicalHrvBaseline,
        historicalRhrBaselineAverage = historicalRhrBaselineAverage,
        historicalHrvBaselineAverage = historicalHrvBaselineAverage,
        historicalRhrZoneBands = historicalRhrZoneBands,
        historicalHrvZoneBands = historicalHrvZoneBands,
        historicalRhrBucketZoneBands = historicalRhrBucketZoneBands,
        historicalHrvBucketZoneBands = historicalHrvBucketZoneBands,
    )
}

/**
 * Fixed-size day bucketing for the baseline overlay at 7D/30D: 1 day (unaveraged) for 7D,
 * non-overlapping 2-day pairs for 30D. Returns null for 180D/360D, which keep the existing
 * calendar-anchored [bucketBy] path unchanged.
 */
private fun baselineOverlayBucketSizeDays(range: TimeRange): Int? =
    when (range) {
        TimeRange.SEVEN_DAYS -> 1
        TimeRange.THIRTY_DAYS -> 2
        TimeRange.SIX_MONTHS, TimeRange.TWELVE_MONTHS -> null
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
    val bodyTempAssessment =
        assessBodyTemperature(
            valueCelsius = summary?.avgSleepingBodyTemp,
            baselineCelsius = bodyTemperatureBaselineCelsius,
            thresholdCelsius = prefs.bodyTempElevatedThresholdCelsius,
            unitSystem = prefs.unitSystem,
        )

    return presentationStateFromAssessments(
        hrv = hrvAssessment,
        rhr = rhrAssessment,
        spo2 = spo2Assessment,
        bodyTemp = bodyTempAssessment,
        unitSystem = prefs.unitSystem,
    )
}
