package app.readylytics.health.domain.model

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.domain.util.UnitConverter
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
        val rhrBaselineRounded = rhrBaselineRaw?.roundToInt()
        val hrvBaselineRounded =
            summary.hrvMuMssd?.let { exp(it).roundToInt() }
                ?: prefs.hrvBaselineOverride?.roundToInt()
                ?: summary.hrvBaseline

        return DailyMetrics(
            date = summary.date,
            // Raw passthrough
            nocturnalRhrRaw = summary.restingHeartRate,
            nocturnalHrvRaw = summary.nocturnalHrv,
            rhrBaselineRaw = rhrBaselineRaw,
            hrvBaselineMeanRaw = summary.hrvMuMssd,
            hrvBaselineSdRaw = summary.hrvSigmaMssd,
            rhrSnapshotRaw = summary.rhrBpm,
            strainRatioRaw = LoadSourceSelector.selectStrainRatio(summary, prefs.strainLoadSourceMode),
            // Rounded display ints
            nocturnalRhrRounded = summary.restingHeartRate,
            nocturnalHrvRounded = summary.nocturnalHrv,
            restingHeartRateRounded = summary.restingHeartRate,
            rhrBaselineRounded = rhrBaselineRounded,
            hrvBaselineRounded = hrvBaselineRounded,
            sleepScoreRounded = summary.sleepScore?.roundToInt(),
            readinessRounded = LoadSourceSelector.selectReadiness(summary, prefs.strainLoadSourceMode)?.roundToInt(),
            loadScoreRounded = LoadSourceSelector.selectLoadScore(summary, prefs.strainLoadSourceMode)?.roundToInt(),
            restorationRounded = summary.sRest?.roundToInt(),
            trimpRounded = LoadSourceSelector.selectTrimp(summary, prefs.strainLoadSourceMode)?.roundToInt(),
            rasRounded = LoadSourceSelector.selectTotalRas(summary, prefs.rasSourceMode)?.roundToInt(),
            rasDayScoreRounded = LoadSourceSelector.selectDailyRas(summary, prefs.rasSourceMode)?.roundToInt(),
            spo2Rounded = summary.avgSleepingSpo2?.roundToInt(),
            // Baseline diffs + arrows
            rhrBaselineDiff = diff(summary.restingHeartRate, rhrBaselineRounded),
            hrvBaselineDiff = diff(summary.nocturnalHrv, hrvBaselineRounded),
            restingHrBaselineDiff = diff(summary.restingHeartRate, summary.rhrBpm?.roundToInt()),
            rhrBaselineArrow = arrow(summary.restingHeartRate, rhrBaselineRounded),
            hrvBaselineArrow = arrow(summary.nocturnalHrv, hrvBaselineRounded),
            restingHrBaselineArrow = arrow(summary.restingHeartRate, summary.rhrBpm?.roundToInt()),
            // Display strings
            sleepDurationDisplay = formatSleepDuration(summary.sleepDurationMinutes),
            weightKgDisplay = summary.weightKg?.let { format1(it) },
            weightLbsDisplay = summary.weightKg?.let { format1(it * UnitConverter.KG_TO_LBS) },
            bodyFatDisplay = summary.bodyFatPercent?.let { "${format1(it)}%" },
            strainRatioDisplay =
                LoadSourceSelector.selectStrainRatio(summary, prefs.strainLoadSourceMode)?.let {
                    MetricFormatter.formatStrain(it)
                },
            zLnHrvDisplay = summary.zLnHrv?.let { format2(it) },
            hrvSigmaDisplay = summary.hrvSigma?.let { format3(it) },
            bloodPressureDisplay = formatBloodPressure(summary.bloodPressureSystolic, summary.bloodPressureDiastolic),
            deepSleepPercentDisplay = summary.deepSleepPercent?.let { "${it.roundToInt()}%" },
            remSleepPercentDisplay = summary.remSleepPercent?.let { "${it.roundToInt()}%" },
            needsRecalc = LoadSourceSelector.needsRecalc(summary, prefs),
            readinessLowConfidence = LoadSourceSelector.readinessLowConfidence(summary, prefs),
        )
    }

    private fun deriveRhrBaselineRaw(
        summary: DailySummary,
        prefs: UserPreferences,
    ): Float? =
        summary.rhrBpm
            ?: prefs.rhrBaselineOverride
            ?: ScoringConstants.DEFAULT_RHR_BPM

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

    private fun formatBloodPressure(
        systolic: Int?,
        diastolic: Int?,
    ): String? {
        if (systolic == null || diastolic == null || systolic == 0 || diastolic == 0) return null
        return "$systolic/$diastolic"
    }

    private fun format1(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

    private fun format2(value: Float): String = String.format(Locale.getDefault(), "%.2f", value)

    private fun format3(value: Float): String = String.format(Locale.getDefault(), "%.3f", value)
}
