package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Readiness
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Restoration
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Sleep
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Strain
import com.gregor.lauritz.healthdashboard.domain.util.mean
import com.gregor.lauritz.healthdashboard.domain.util.median
import com.gregor.lauritz.healthdashboard.domain.util.stdev

/**
 * Pure Kotlin object containing all scoring formulas.
 * Decoupled from data fetching and persistence for better testability and clean architecture.
 */
object ScoringCalculator {

    fun computeStrainRatio(atl: Float, ctl: Float): Float =
        if (ctl > 0f) atl / ctl else 0f

    fun computeCtlEma(
        dailyTrimpList: List<Float>,
        seedFitnessLevel: Float = ScoringConstants.DEFAULT_FITNESS_LEVEL,
        windowDays: Long = ScoringConstants.CHRONIC_DAYS,
    ): Float {
        val dataTenureDays = dailyTrimpList.size

        if (dataTenureDays < 7) return seedFitnessLevel

        val sma = dailyTrimpList.take(ScoringConstants.PROVISIONAL_DAYS).average().toFloat()
        if (dataTenureDays <= ScoringConstants.PROVISIONAL_DAYS) return sma

        val alpha = 2f / (windowDays + 1f)
        var currentEma = sma

        for (i in ScoringConstants.PROVISIONAL_DAYS until dailyTrimpList.size) {
            val dailyTrimp = dailyTrimpList[i]
            currentEma = (dailyTrimp * alpha) + (currentEma * (1f - alpha))
        }

        return currentEma
    }

    fun computeLoadScore(sr: Float): Float =
        when {
            sr <= 0f -> Strain.OPTIMAL_LOW_SCORE
            sr < Strain.SR_UNDER_TRAINING -> Strain.NEUTRAL_SCORE
            sr <= Strain.SR_SWEET_SPOT_MAX -> Strain.OPTIMAL_SWEET_SPOT_SCORE
            sr <= Strain.SR_OVER_TRAINING_MAX -> Strain.OPTIMAL_SWEET_SPOT_SCORE - (sr - Strain.SR_SWEET_SPOT_MAX) * 200f
            else -> Strain.POOR_SCORE
        }

    fun computeDurationSubScore(
        durationMinutes: Int,
        efficiency: Float,
        goalSleepHours: Float,
    ): Float {
        val ratio = (durationMinutes / 60f / goalSleepHours).coerceIn(0f, 1f)
        return ratio * 100f * (efficiency / 100f)
    }

    fun computeArchSubScore(
        deepSleepMinutes: Int,
        remSleepMinutes: Int,
        durationMinutes: Int,
    ): Float {
        if (durationMinutes == 0) return 0f
        val deepPct = deepSleepMinutes / durationMinutes.toFloat() * 100f
        val remPct = remSleepMinutes / durationMinutes.toFloat() * 100f
        val deepComponent = (deepPct / Sleep.DEEP_SLEEP_OPTIMAL_PCT).coerceAtMost(1f) * 100f
        val remComponent = (remPct / Sleep.REM_SLEEP_OPTIMAL_PCT).coerceAtMost(1f) * 100f
        return Sleep.WEIGHT_DEEP_COMPONENT * deepComponent + Sleep.WEIGHT_REM_COMPONENT * remComponent
    }

    fun computeRestorationSubScore(
        currentHrvMean: Float,
        hrvValues: List<Float>,
        currentNocturnalRhr: Float,
        rhrValues: List<Int>,
        rhrBaselineOverride: Float?,
        hrvBaselineOverride: Float?,
    ): Float {
        val muHrv = hrvBaselineOverride ?: hrvValues.mean()
        val sigmaHrv = hrvSigma(hrvValues)
        val zHrv = (currentHrvMean - muHrv) / sigmaHrv
        val hrvScore = (50f + 25f * zHrv).coerceIn(0f, 100f)

        val baselineRhr = rhrBaselineOverride ?: rhrValues.median()
        val rhrScore = (baselineRhr / (currentNocturnalRhr + 0.001f) * 100f).coerceIn(0f, 100f)

        return Restoration.WEIGHT_HRV_SCORE * hrvScore + Restoration.WEIGHT_RHR_SCORE * rhrScore
    }

    fun computeSleepScore(
        durationMinutes: Int,
        efficiency: Float,
        deepSleepMinutes: Int,
        remSleepMinutes: Int,
        goalSleepHours: Float,
        sRest: Float,
    ): Float {
        val sDur = computeDurationSubScore(durationMinutes, efficiency, goalSleepHours)
        val sArch = computeArchSubScore(deepSleepMinutes, remSleepMinutes, durationMinutes)
        return Sleep.WEIGHT_DURATION * sDur + Sleep.WEIGHT_ARCHITECTURE * sArch + Sleep.WEIGHT_RESTORATION * sRest
    }

    fun computeReadinessScore(
        sRest: Float,
        sleepScore: Float,
        loadScore: Float,
        zHrv: Float? = null,
        rhrDeltaBpm: Float? = null,
    ): Float {
        var rs = Readiness.WEIGHT_RESTORATION * sRest + Readiness.WEIGHT_SLEEP * sleepScore + Readiness.WEIGHT_LOAD * loadScore

        if (zHrv != null && rhrDeltaBpm != null &&
            zHrv > Readiness.PARADOXICAL_HIGH_Z_HRV &&
            rhrDeltaBpm >= Readiness.PARADOXICAL_HIGH_RHR_DELTA_BPM) {
            rs = rs.coerceAtMost(Readiness.PARADOXICAL_HIGH_MAX_SCORE)
        }
        return rs.coerceIn(0f, 100f)
    }

    fun hrvSigma(hrvValues: List<Float>): Float =
        if (hrvValues.size < ScoringConstants.MATURE_DATA_TENURE_DAYS) {
            maxOf(hrvValues.mean() * Restoration.PROVISIONAL_CV_RULE, Restoration.MIN_SIGMA)
        } else {
            hrvValues.stdev().takeIf { it > 0f } ?: maxOf(hrvValues.mean() * Restoration.PROVISIONAL_CV_RULE, Restoration.MIN_SIGMA)
        }
}
