package app.readylytics.health.core.model.domain.model

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.util.UnitConverter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The single site for all display rounding, metric string formatting, and resting-HR
 * baseline derivation for the [DailyMetrics] projection.
 *
 * Rounding rule is [kotlin.math.roundToInt] (half toward +∞) for every metric unless the
 * source is already an Int (passthrough). RAS is standardized to [roundToInt] here,
 * replacing the prior inconsistent `toInt()` truncation vs `roundToPercentInt()`.
 *
 * Baseline display fields for date D derive only from D's stored row — frozen baseline
 * columns are passed through verbatim, never recomputed.
 */
object DailyMetricsMapper {
    fun toMetrics(
        summary: DailySummary,
        prefs: UserPreferences,
    ): DailyMetrics {
        val rhrBaselineRaw = deriveRhrBaselineRaw(summary, prefs)
        val rhrBaselineRounded = rhrBaselineRounded(summary, prefs)
        val hrvBaselineRoundedValue = hrvBaselineRounded(summary, prefs)
        val rhrSnapshotRaw = acceptedRhrSnapshotRaw(summary)
        return buildDailyMetrics(
            summary = summary,
            prefs = prefs,
            rhrBaselineRaw = rhrBaselineRaw,
            rhrBaselineRounded = rhrBaselineRounded,
            hrvBaselineRoundedValue = hrvBaselineRoundedValue,
            rhrSnapshotRaw = rhrSnapshotRaw,
        )
    }

    private fun buildDailyMetrics(
        summary: DailySummary,
        prefs: UserPreferences,
        rhrBaselineRaw: Float?,
        rhrBaselineRounded: Int?,
        hrvBaselineRoundedValue: Int?,
        rhrSnapshotRaw: Float?,
    ): DailyMetrics {
        val loadScoreMetrics = buildLoadScoreMetrics(summary, prefs)
        val baselineComparisons = buildBaselineComparisons(summary, rhrBaselineRounded, hrvBaselineRoundedValue)

        return DailyMetrics(
            date = summary.date,
            nocturnalRhrRaw = summary.restingHeartRate,
            nocturnalHrvRaw = summary.nocturnalHrv,
            rhrBaselineRaw = rhrBaselineRaw,
            hrvBaselineMeanRaw = summary.hrvMuMssd,
            hrvBaselineSdRaw = summary.hrvSigmaMssd,
            rhrSnapshotRaw = rhrSnapshotRaw,
            strainRatioRaw = LoadSourceSelector.selectStrainRatio(summary, prefs.strainLoadSourceMode),
            nocturnalRhrRounded = summary.restingHeartRate,
            nocturnalHrvRounded = summary.nocturnalHrv,
            restingHeartRateRounded = summary.restingHeartRate,
            rhrBaselineRounded = rhrBaselineRounded,
            hrvBaselineRounded = hrvBaselineRoundedValue,
            sleepScoreRounded = summary.sleepScore?.roundToInt(),
            readinessRounded = loadScoreMetrics.readiness,
            loadScoreRounded = loadScoreMetrics.load,
            restorationRounded = summary.sRest?.roundToInt(),
            trimpRounded = loadScoreMetrics.trimp,
            rasRounded = loadScoreMetrics.rasTotal,
            rasDayScoreRounded = loadScoreMetrics.rasDay,
            spo2Rounded = summary.avgSleepingSpo2?.roundToInt(),
            rhrBaselineDiff = baselineComparisons.rhrDiff,
            hrvBaselineDiff = baselineComparisons.hrvDiff,
            restingHrBaselineDiff = baselineComparisons.rhrDiff,
            rhrBaselineArrow = baselineComparisons.rhrArrow,
            hrvBaselineArrow = baselineComparisons.hrvArrow,
            restingHrBaselineArrow = baselineComparisons.rhrArrow,
            sleepDurationDisplay = formatSleepDuration(summary.sleepDurationMinutes),
            weightKgDisplay = formatWeight(summary.weightKg),
            weightLbsDisplay = formatWeightLbs(summary.weightKg),
            bodyFatDisplay = formatBodyFatPercent(summary.bodyFatPercent),
            strainRatioDisplay = formatStrainRatio(summary, prefs.strainLoadSourceMode),
            zLnHrvDisplay = summary.zLnHrv?.let { format2(it) },
            hrvSigmaDisplay = summary.hrvSigma?.let { format3(it) },
            bloodPressureDisplay = formatBloodPressure(summary.bloodPressureSystolic, summary.bloodPressureDiastolic),
            deepSleepPercentDisplay = formatPercentDisplay(summary.deepSleepPercent),
            remSleepPercentDisplay = formatPercentDisplay(summary.remSleepPercent),
            needsRecalc = LoadSourceSelector.needsRecalc(summary, prefs),
            readinessLowConfidence = LoadSourceSelector.readinessLowConfidence(summary, prefs),
            napDurationDisplay = summary.supplementalSleepDurationMinutes?.let(::formatSleepDuration),
            napCount = summary.napCount,
        )
    }

    private data class BaselineComparisons(
        val rhrDiff: Int?,
        val hrvDiff: Int?,
        val rhrArrow: BaselineArrow?,
        val hrvArrow: BaselineArrow?,
    )

    private data class LoadScoreMetrics(
        val readiness: Int?,
        val load: Int?,
        val trimp: Int?,
        val rasTotal: Int?,
        val rasDay: Int?,
    )

    private fun buildBaselineComparisons(
        summary: DailySummary,
        rhrBaselineRounded: Int?,
        hrvBaselineRoundedValue: Int?,
    ): BaselineComparisons {
        val rhrDiff = diff(summary.restingHeartRate, rhrBaselineRounded)
        val hrvDiff = diff(summary.nocturnalHrv, hrvBaselineRoundedValue)
        val rhrArrow = arrow(summary.restingHeartRate, rhrBaselineRounded)
        val hrvArrow = arrow(summary.nocturnalHrv, hrvBaselineRoundedValue)
        return BaselineComparisons(rhrDiff, hrvDiff, rhrArrow, hrvArrow)
    }

    private fun buildLoadScoreMetrics(
        summary: DailySummary,
        prefs: UserPreferences,
    ): LoadScoreMetrics {
        val readiness = LoadSourceSelector.selectReadiness(summary, prefs.strainLoadSourceMode)?.roundToInt()
        val load = LoadSourceSelector.selectLoadScore(summary, prefs.strainLoadSourceMode)?.roundToInt()
        val trimp = LoadSourceSelector.selectTrimp(summary, prefs.strainLoadSourceMode)?.roundToInt()
        val rasTotal = LoadSourceSelector.selectTotalRas(summary, prefs.rasSourceMode)?.roundToInt()
        val rasDay = LoadSourceSelector.selectDailyRas(summary, prefs.rasSourceMode)?.roundToInt()
        return LoadScoreMetrics(readiness, load, trimp, rasTotal, rasDay)
    }

    private fun formatWeight(weightKg: Float?): String? =
        weightKg?.let { format1(it) }

    private fun formatWeightLbs(weightKg: Float?): String? =
        weightKg?.let { format1(it * UnitConverter.KG_TO_LBS) }

    private fun formatBodyFatPercent(bodyFatPercent: Float?): String? =
        bodyFatPercent?.let { "${format1(it)}%" }

    private fun formatStrainRatio(
        summary: DailySummary,
        loadSourceMode: LoadSourceMode,
    ): String? =
        LoadSourceSelector.selectStrainRatio(summary, loadSourceMode)?.let {
            MetricFormatter.formatStrain(it)
        }

    private fun formatPercentDisplay(percent: Float?): String? =
        percent?.let { "${it.roundToInt()}%" }

    private fun deriveRhrBaselineRaw(
        summary: DailySummary,
        prefs: UserPreferences,
    ): Float? =
        acceptedRhrSnapshotRaw(summary)
            ?: prefs.rhrBaselineOverride

    fun rhrBaselineRounded(
        summary: DailySummary,
        prefs: UserPreferences,
    ): Int? = deriveRhrBaselineRaw(summary, prefs)?.roundToInt()

    fun rhrBaselineRounded(
        summary: DailySummary,
        rhrBaselineOverride: Float?,
    ): Int? = acceptedRhrSnapshotRaw(summary)?.roundToInt() ?: rhrBaselineOverride?.roundToInt()

    private fun acceptedRhrSnapshotRaw(summary: DailySummary): Float? =
        summary.rhrBpm.takeIf { summary.baselineCalculatedAtDate != null }

    /**
     * The HRV baseline rounded to whole ms, exactly as shown on the dashboard. Callers
     * comparing a day's HRV to its baseline (e.g. insight rules) must reuse this instead of
     * re-deriving the rounding independently, so "below baseline" always agrees with what
     * the UI displays.
     */
    fun hrvBaselineRounded(
        summary: DailySummary,
        prefs: UserPreferences,
    ): Int? =
        summary.hrvMuMssd?.let { exp(it).roundToInt() }
            ?: prefs.hrvBaselineOverride?.roundToInt()
            ?: summary.hrvBaseline

    fun hrvBaselineRounded(
        summary: DailySummary,
        hrvBaselineOverride: Float?,
    ): Int? =
        summary.hrvMuMssd?.let { exp(it).roundToInt() }
            ?: hrvBaselineOverride?.roundToInt()
            ?: summary.hrvBaseline

    private fun diff(
        current: Int?,
        baseline: Int?,
    ): Int? = if (current != null && baseline != null) abs(current - baseline) else null

    private fun arrow(
        current: Int?,
        baseline: Int?,
    ): BaselineArrow? {
        if (current == null || baseline == null) return null
        return when {
            current > baseline -> BaselineArrow.UP
            current < baseline -> BaselineArrow.DOWN
            else -> BaselineArrow.EQUAL
        }
    }

    fun formatSleepDuration(minutes: Int?): String? {
        if (minutes == null) return null
        val hours = minutes / 60
        val mins = minutes % 60
        return if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
    }

    /**
     * Returns hours and minutes as separate strings for split gauge display
     * (hours on the value line, minutes on the unit line).
     */
    fun formatSleepDurationSplit(minutes: Int?): Pair<String, String>? {
        if (minutes == null) return null
        val hours = minutes / 60
        val mins = minutes % 60
        return "${hours}h" to if (mins > 0) "${mins}m" else ""
    }

    private fun formatBloodPressure(
        systolic: Int?,
        diastolic: Int?,
    ): String? {
        if (!isValidPressure(systolic) || !isValidPressure(diastolic)) return null
        return "$systolic/$diastolic"
    }

    private fun isValidPressure(value: Int?): Boolean = value != null && value > 0

    private fun format1(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

    private fun format2(value: Float): String = String.format(Locale.getDefault(), "%.2f", value)

    private fun format3(value: Float): String = String.format(Locale.getDefault(), "%.3f", value)
}
