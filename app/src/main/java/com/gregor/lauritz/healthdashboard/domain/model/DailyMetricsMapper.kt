package com.gregor.lauritz.healthdashboard.domain.model

import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import com.gregor.lauritz.healthdashboard.domain.util.UnitConverter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The single site for all display rounding, metric string formatting, and resting-HR
 * baseline derivation for the [DailyMetrics] projection.
 *
 * Rounding rule is [kotlin.math.roundToInt] (half toward +∞) for every metric unless the
 * source is already an Int (passthrough). PAI is standardized to [roundToInt] here,
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
        val hrvBaselineRounded = summary.hrvBaseline ?: prefs.hrvBaselineOverride?.roundToInt()

        return DailyMetrics(
            date = summary.date,
            // Raw passthrough
            nocturnalRhrRaw = summary.nocturnalRhr,
            nocturnalHrvRaw = summary.nocturnalHrv,
            rhrBaselineRaw = rhrBaselineRaw,
            hrvBaselineMeanRaw = summary.hrvMuMssd,
            hrvBaselineSdRaw = summary.hrvSigmaMssd,
            rhrSnapshotRaw = summary.rhrBpm,
            // Rounded display ints
            nocturnalRhrRounded = summary.nocturnalRhr,
            nocturnalHrvRounded = summary.nocturnalHrv,
            restingHeartRateRounded = summary.restingHeartRate ?: summary.nocturnalRhr,
            rhrBaselineRounded = rhrBaselineRounded,
            hrvBaselineRounded = hrvBaselineRounded,
            sleepScoreRounded = summary.sleepScore?.roundToInt(),
            readinessRounded = summary.readinessScore?.roundToInt(),
            loadScoreRounded = summary.loadScore?.roundToInt(),
            restorationRounded = summary.sRest?.roundToInt(),
            trimpRounded = summary.totalTrimp?.roundToInt(),
            paiRounded = summary.totalPai?.roundToInt(),
            paiDayScoreRounded = summary.paiScore?.roundToInt(),
            spo2Rounded = summary.avgSleepingSpo2?.roundToInt(),
            // Baseline diffs + arrows
            rhrBaselineDiff = diff(summary.nocturnalRhr, rhrBaselineRounded),
            hrvBaselineDiff = diff(summary.nocturnalHrv, hrvBaselineRounded),
            restingHrBaselineDiff = diff(summary.restingHeartRate, summary.restingHrBaseline),
            rhrBaselineArrow = arrow(summary.nocturnalRhr, rhrBaselineRounded),
            hrvBaselineArrow = arrow(summary.nocturnalHrv, hrvBaselineRounded),
            restingHrBaselineArrow = arrow(summary.restingHeartRate, summary.restingHrBaseline),
            // Display strings
            sleepDurationDisplay = formatSleepDuration(summary.sleepDurationMinutes),
            weightKgDisplay = summary.weightKg?.let { format1(it) },
            weightLbsDisplay = summary.weightKg?.let { format1(it * UnitConverter.KG_TO_LBS) },
            bodyFatDisplay = summary.bodyFatPercent?.let { "${format1(it)}%" },
            zLnHrvDisplay = summary.zLnHrv?.let { format2(it) },
            hrvSigmaDisplay = summary.hrvSigma?.let { format3(it) },
            bloodPressureDisplay = formatBloodPressure(summary.bloodPressureSystolic, summary.bloodPressureDiastolic),
            deepSleepPercentDisplay = summary.deepSleepPercent?.let { "${it.roundToInt()}%" },
            remSleepPercentDisplay = summary.remSleepPercent?.let { "${it.roundToInt()}%" },
        )
    }

    /**
     * Resting-HR baseline derivation — the single source of truth for the
     * `(nocturnalRhr / rhrRatio)` calculation previously duplicated across providers
     * and ViewModels. Falls back to the stored/override/default baseline when the ratio
     * is unavailable.
     */
    private fun deriveRhrBaselineRaw(
        summary: DailySummary,
        prefs: UserPreferences,
    ): Float? {
        val ratio = summary.rhrRatio
        val rhr = summary.nocturnalRhr
        return if (ratio != null && ratio > 0f && rhr != null) {
            rhr / ratio
        } else {
            summary.restingHrBaseline?.toFloat()
                ?: summary.rhrBpm
                ?: prefs.rhrBaselineOverride
                ?: ScoringConstants.DEFAULT_RHR_BPM
        }
    }

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
