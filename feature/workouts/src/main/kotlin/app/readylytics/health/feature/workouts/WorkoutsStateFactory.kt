package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.core.scoring.domain.scoring.calculateDailyStrainIncrease
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.aggregateByRange
import app.readylytics.health.core.ui.common.padBucketsToRange
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.domain.workouts.WorkoutHistoryConfiguration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class WorkoutDisplayItem(
    val workout: WorkoutData,
    val gainedStrain: Float,
    val computedTrimp: Int,
    val gainedStrainDisplay: String,
    val classification: WorkoutLoadClassification?,
)

@Immutable
data class WorkoutsUiState(
    val latestSummary: DailySummary? = null,
    val latestMetrics: DailyMetrics? = null,
    val dailyTrimp: List<DailyDataPoint> = emptyList(),
    val dailyStrainRatio: List<DailyDataPoint> = emptyList(),
    val recentWorkouts: List<WorkoutDisplayItem> = emptyList(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val rasDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val todayRasScore: Float? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val yesterdayStrainRatio: Float? = null,
    val yesterdayReadiness: Float? = null,
    val todayStrainIncrease: Float? = null,
    val isRangeChanging: Boolean = false,
    val trimpPeriodSummary: PeriodAverageSummary? = null,
    val strainRatioPeriodSummary: PeriodAverageSummary? = null,
    val cardConfigurations: List<CardConfiguration> = emptyList(),
    val isManagingCards: Boolean = false,
    val chartConfigurations: List<WorkoutChartConfiguration> = emptyList(),
    val isManagingCharts: Boolean = false,
    val historyConfigurations: List<WorkoutHistoryConfiguration> = emptyList(),
    val isManagingHistory: Boolean = false,
) {
    val isManagingWorkoutsLayout: Boolean
        get() = isManagingCards || isManagingCharts || isManagingHistory
}

internal data class CombinedParams(
    val range: TimeRange,
    val date: LocalDate,
    val page: Int,
)

internal data class WorkoutsRangeWindow(
    val displayStartDate: LocalDate,
    val displayFromMs: Long,
    val fetchFromMs: Long,
    val selectedMidnightMs: Long,
    val selectedDayEndMs: Long,
    val rasFromMs: Long,
)

internal fun resolveWorkoutsRangeWindow(
    range: TimeRange,
    selectedDate: LocalDate,
    zoneId: ZoneId,
): WorkoutsRangeWindow {
    val displayStartDate = selectedDate.minusDays(range.days.toLong() - 1)
    return WorkoutsRangeWindow(
        displayStartDate = displayStartDate,
        displayFromMs = displayStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        fetchFromMs =
            displayStartDate
                .minusDays(ScoringConstants.CHRONIC_DAYS)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli(),
        selectedMidnightMs = selectedDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        selectedDayEndMs =
            selectedDate
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli(),
        rasFromMs =
            selectedDate
                .minusDays(6)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli(),
    )
}

internal suspend fun resolveEarliestLocalDate(
    prefs: UserPreferences,
    trimpSummaries: List<DailySummary>,
    zoneId: ZoneId,
    getEarliestWorkoutTimestamp: suspend () -> Long?,
): LocalDate? =
    when (prefs.strainLoadSourceMode) {
        LoadSourceMode.WORKOUT_ONLY ->
            getEarliestWorkoutTimestamp()?.let {
                Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
            }
        LoadSourceMode.EVERYDAY_HEART_RATE ->
            LoadSourceSelector.selectEarliestDataDate(trimpSummaries)
    }

internal fun buildRasBreakdown(
    endDate: LocalDate,
    summaries: List<DailySummary>,
    prefs: UserPreferences,
): List<Pair<String, Float>> {
    val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    return (6 downTo 0).map { daysBack ->
        val day = endDate.minusDays(daysBack.toLong())
        val entry = summaries.firstOrNull { it.date == day }
        val ras = entry?.let { LoadSourceSelector.selectDailyRas(it, prefs.rasSourceMode) }
        day.format(fmt) to (ras ?: 0f)
    }
}

/** Inputs for [buildWorkoutsState]. Bundled rather than passed as 14 positional parameters:
 *  the function is a wide UI-state assembler, and a record keeps the call sites readable and
 *  keeps detekt's LongParameterList/LongMethod honest about real complexity. */
internal data class WorkoutsStateInputs(
    val scoringCalculator: ScoringCalculator,
    val latestSummary: DailySummary?,
    val trimpSummaries: List<DailySummary>,
    val rasSummaries: List<DailySummary>,
    val prefs: UserPreferences,
    val range: TimeRange,
    val selectedDate: LocalDate,
    val zoneId: ZoneId,
    val recentWorkouts: List<WorkoutDisplayItem>,
    val currentPage: Int,
    val totalPages: Int,
    val earliestLocalDate: LocalDate?,
    val workoutOnlyGains: List<Float> = emptyList(),
    val todayStrainIncrease: Float? = null,
)

internal fun buildWorkoutsState(inputs: WorkoutsStateInputs): WorkoutsUiState =
    with(inputs) {
        val ctx =
            buildRangeContext(
                scoringCalculator = scoringCalculator,
                trimpSummaries = trimpSummaries,
                prefs = prefs,
                range = range,
                selectedDate = selectedDate,
                zoneId = zoneId,
            )

        val (dailyTrimp, dailyStrainRatio) =
            buildDailySeries(
                scoringCalculator = scoringCalculator,
                displayDayMidnights = ctx.displayDayMidnights,
                trimpByDay = ctx.trimpByDay,
                ctlSeries = ctx.ctlSeries,
                atlSeries = ctx.atlSeries,
                earliestLocalDate = earliestLocalDate,
                zoneId = zoneId,
            )

        val series =
            buildPaddedSeries(
                dailyTrimp = dailyTrimp,
                dailyStrainRatio = dailyStrainRatio,
                range = range,
                displayStartDayDate = ctx.displayStartDayDate,
                selectedDate = selectedDate,
            )

        val yesterdayMetrics =
            rasSummaries
                .firstOrNull { it.date == selectedDate.minusDays(1) }
                ?.let { DailyMetricsMapper.toMetrics(it, prefs) }
        val dataTenureDaysForDate =
            earliestLocalDate
                ?.let { ChronoUnit.DAYS.between(it, selectedDate).toInt() + 1 }
                ?: 0

        val resolvedTodayStrainIncrease =
            resolveTodayStrainIncrease(
                todayStrainIncrease = todayStrainIncrease,
                scoringCalculator = scoringCalculator,
                prefs = prefs,
                ctx = ctx,
                selectedDate = selectedDate,
                dataTenureDaysForDate = dataTenureDaysForDate,
                workoutOnlyGains = workoutOnlyGains,
            )

        assembleWorkoutsUiState(
            inputs = inputs,
            series = series,
            displayStartDayMs = ctx.displayStartDayMs,
            yesterdayMetrics = yesterdayMetrics,
            resolvedTodayStrainIncrease = resolvedTodayStrainIncrease,
        )
    }

/** Daily TRIMP and strain-ratio series for the displayed range. Extracted verbatim from
 *  buildWorkoutsState; no expression changed. */
private fun buildDailySeries(
    scoringCalculator: ScoringCalculator,
    displayDayMidnights: List<Long>,
    trimpByDay: Map<Long, Float>,
    ctlSeries: Map<LocalDate, Float>,
    atlSeries: Map<LocalDate, Float>,
    earliestLocalDate: LocalDate?,
    zoneId: ZoneId,
): Pair<List<DailyDataPoint>, List<DailyDataPoint>> {
    val dailyTrimp = mutableListOf<DailyDataPoint>()
    val dailyStrainRatio = mutableListOf<DailyDataPoint>()

    displayDayMidnights.forEachIndexed { i, dayMidnight ->
        val trimp = trimpByDay[dayMidnight]
        dailyTrimp.add(
            DailyDataPoint(
                dayOffset = i,
                value =
                    if (trimp != null &&
                        trimp > 0f
                    ) {
                        trimp
                    } else {
                        null
                    },
            ),
        )

        val currentDayDate = Instant.ofEpochMilli(dayMidnight).atZone(zoneId).toLocalDate()

        val dataTenureDays =
            if (earliestLocalDate != null) {
                ChronoUnit.DAYS.between(earliestLocalDate, currentDayDate).toInt() + 1
            } else {
                0
            }

        val sr =
            if (dataTenureDays >= 7) {
                val ctl = ctlSeries[currentDayDate] ?: ScoringConstants.DEFAULT_FITNESS_LEVEL
                val atl = atlSeries[currentDayDate] ?: ScoringConstants.DEFAULT_FITNESS_LEVEL
                scoringCalculator.computeStrainRatio(atl, ctl)
            } else {
                null
            }
        dailyStrainRatio.add(DailyDataPoint(dayOffset = i, value = sr))
    }
    return dailyTrimp to dailyStrainRatio
}

/** Today's strain increase, falling back to a per-load-source computation when the caller
 *  did not supply one. Extracted verbatim from buildWorkoutsState; no expression changed. */
private fun resolveTodayStrainIncrease(
    todayStrainIncrease: Float?,
    scoringCalculator: ScoringCalculator,
    prefs: UserPreferences,
    ctx: RangeContext,
    selectedDate: LocalDate,
    dataTenureDaysForDate: Int,
    workoutOnlyGains: List<Float>,
): Float? =
    todayStrainIncrease ?: run {
        when (prefs.strainLoadSourceMode) {
            LoadSourceMode.WORKOUT_ONLY -> {
                calculateDailyStrainIncrease(
                    dataTenureDays = dataTenureDaysForDate,
                    loadSourceMode = prefs.strainLoadSourceMode,
                    workoutOnlyGains = workoutOnlyGains,
                    strainRatioWithDay = null,
                    strainRatioWithoutDay = null,
                )
            }

            LoadSourceMode.EVERYDAY_HEART_RATE -> {
                val trimpByDateWithout =
                    ctx.trimpByDate.toMutableMap().apply { put(selectedDate, 0f) }
                val ctlWith =
                    ctx.ctlSeries[selectedDate] ?: ScoringConstants.DEFAULT_FITNESS_LEVEL
                val atlWith =
                    ctx.atlSeries[selectedDate] ?: ScoringConstants.DEFAULT_FITNESS_LEVEL
                val strainRatioWithDay =
                    scoringCalculator.computeStrainRatio(atlWith, ctlWith)
                val ctlWithout =
                    scoringCalculator.computeCtlEmaWithDecay(trimpByDateWithout, selectedDate)
                val atlWithout =
                    scoringCalculator.computeAtlEmaWithDecay(trimpByDateWithout, selectedDate)
                val strainRatioWithoutDay =
                    scoringCalculator.computeStrainRatio(atlWithout, ctlWithout)
                calculateDailyStrainIncrease(
                    dataTenureDays = dataTenureDaysForDate,
                    loadSourceMode = prefs.strainLoadSourceMode,
                    workoutOnlyGains = emptyList(),
                    strainRatioWithDay = strainRatioWithDay,
                    strainRatioWithoutDay = strainRatioWithoutDay,
                )
            }
        }
    }

/** Buckets and pads both daily series for the selected range. Extracted verbatim from
 *  buildWorkoutsState; no expression changed. */
private fun buildPaddedSeries(
    dailyTrimp: List<DailyDataPoint>,
    dailyStrainRatio: List<DailyDataPoint>,
    range: TimeRange,
    displayStartDayDate: LocalDate,
    selectedDate: LocalDate,
): PaddedSeries {
    val trimpForAggregation = dailyTrimp.map { it.copy(value = it.value ?: 0f) }
    val (bucketedTrimp, trimpSummary) =
        trimpForAggregation
            .aggregateByRange(range.granularity, displayStartDayDate, selectedDate, range.days)
    val (bucketedStrainRatio, strainSummary) =
        dailyStrainRatio
            .aggregateByRange(
                range.granularity,
                displayStartDayDate,
                selectedDate,
                range.days,
                valueDecimalPlaces = 2,
            )

    val paddedTrimp =
        bucketedTrimp.padBucketsToRange(
            range.granularity,
            displayStartDayDate,
            selectedDate,
        )
    val paddedStrain =
        bucketedStrainRatio.padBucketsToRange(
            range.granularity,
            displayStartDayDate,
            selectedDate,
        )
    return PaddedSeries(paddedTrimp, paddedStrain, trimpSummary, strainSummary)
}

private data class PaddedSeries(
    val paddedTrimp: List<DailyDataPoint>,
    val paddedStrain: List<DailyDataPoint>,
    val trimpSummary: PeriodAverageSummary?,
    val strainSummary: PeriodAverageSummary?,
)

/** Per-day TRIMP maps, the displayed day midnights, and the CTL/ATL EMA series.
 *  Extracted verbatim from buildWorkoutsState; no expression changed. */
private fun buildRangeContext(
    scoringCalculator: ScoringCalculator,
    trimpSummaries: List<DailySummary>,
    prefs: UserPreferences,
    range: TimeRange,
    selectedDate: LocalDate,
    zoneId: ZoneId,
): RangeContext {
    val displayStartDayDate = selectedDate.minusDays(range.days.toLong() - 1)
    val displayStartDayMs =
        displayStartDayDate
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    val trimpByDate: Map<LocalDate, Float> =
        trimpSummaries.associate { summary ->
            summary.date to
                (LoadSourceSelector.selectTrimp(summary, prefs.strainLoadSourceMode) ?: 0f)
        }

    val trimpByDay: Map<Long, Float> =
        trimpSummaries.associate { summary ->
            val dayMs =
                summary.date
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            dayMs to (trimpByDate[summary.date] ?: 0f)
        }

    val displayDayMidnights =
        buildList<Long> {
            var current = displayStartDayDate
            val end = selectedDate
            while (!current.isAfter(end)) {
                add(current.atStartOfDay(zoneId).toInstant().toEpochMilli())
                current = current.plusDays(1)
            }
        }

    val ctlSeries =
        scoringCalculator.computeCtlEmaSeries(
            trimpByDate,
            displayStartDayDate,
            selectedDate,
        )
    val atlSeries =
        scoringCalculator.computeAtlEmaSeries(
            trimpByDate,
            displayStartDayDate,
            selectedDate,
        )
    return RangeContext(
        displayStartDayDate,
        displayStartDayMs,
        trimpByDate,
        trimpByDay,
        displayDayMidnights,
        ctlSeries,
        atlSeries,
    )
}

private data class RangeContext(
    val displayStartDayDate: LocalDate,
    val displayStartDayMs: Long,
    val trimpByDate: Map<LocalDate, Float>,
    val trimpByDay: Map<Long, Float>,
    val displayDayMidnights: List<Long>,
    val ctlSeries: Map<LocalDate, Float>,
    val atlSeries: Map<LocalDate, Float>,
)

/** Final [WorkoutsUiState] assembly. Extracted verbatim from buildWorkoutsState; no
 *  expression changed. */
private fun assembleWorkoutsUiState(
    inputs: WorkoutsStateInputs,
    series: PaddedSeries,
    displayStartDayMs: Long,
    yesterdayMetrics: DailyMetrics?,
    resolvedTodayStrainIncrease: Float?,
): WorkoutsUiState =
    with(inputs) {
        WorkoutsUiState(
            latestSummary = latestSummary,
            latestMetrics = latestSummary?.let { DailyMetricsMapper.toMetrics(it, prefs) },
            dailyTrimp = series.paddedTrimp,
            dailyStrainRatio = series.paddedStrain,
            recentWorkouts = recentWorkouts,
            selectedRange = range,
            selectedDate = selectedDate,
            rangeStartMs = displayStartDayMs,
            rasDailyBreakdown = buildRasBreakdown(selectedDate, rasSummaries, prefs),
            todayRasScore =
                latestSummary?.let {
                    LoadSourceSelector.selectDailyRas(
                        it,
                        prefs.rasSourceMode,
                    )
                },
            currentPage = currentPage,
            totalPages = totalPages,
            yesterdayStrainRatio = yesterdayMetrics?.strainRatioRaw,
            yesterdayReadiness = yesterdayMetrics?.readinessRounded?.toFloat(),
            todayStrainIncrease = resolvedTodayStrainIncrease,
            trimpPeriodSummary = series.trimpSummary,
            strainRatioPeriodSummary = series.strainSummary,
        )
    }
