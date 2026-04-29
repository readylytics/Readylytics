package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Readiness
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Restoration
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Sleep
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Strain
import com.gregor.lauritz.healthdashboard.domain.util.mean
import com.gregor.lauritz.healthdashboard.domain.util.median
import com.gregor.lauritz.healthdashboard.domain.util.stdev
import kotlin.math.exp
import kotlin.math.ln

/**
 * Pure Kotlin object containing all scoring formulas.
 * Decoupled from data fetching and persistence for better testability and clean architecture.
 *
 * HRV display values are always in raw ms. The ln(RMSSD) transform is applied internally
 * only for Z-score statistics to normalise the log-normal distribution.
 * REF: Plews 2013 Sports Med 43:773; Buchheit 2014 Front Physiol 5:73
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
            currentEma = (dailyTrimpList[i] * alpha) + (currentEma * (1f - alpha))
        }

        return currentEma
    }

    // All SR ≤ sweet-spot (1.3) score 100; no penalty for rest days.
    // Above 1.3: smooth quadratic decay with no artificial floor.
    // REF: Gabbett 2016 BJSM; Windt & Gabbett 2018 BJSM; Impellizzeri 2020 IJSPP
    fun computeLoadScore(sr: Float): Float {
        if (sr <= Strain.SR_SWEET_SPOT_MAX) return Strain.OPTIMAL_SWEET_SPOT_SCORE
        val excess = sr - Strain.SR_SWEET_SPOT_MAX
        return (100f * exp(-Strain.QUADRATIC_PENALTY_K * excess * excess)).coerceIn(0f, 100f)
    }

    // Additive efficiency: TST and efficiency scored separately then combined.
    // Avoids double-penalty because TST = TIB × SE already encodes efficiency.
    // REF: Buysse 1989 PSQI; A.3 review finding
    fun computeDurationSubScore(
        durationMinutes: Int,
        efficiency: Float,
        goalSleepHours: Float,
    ): Float {
        val tstTerm = (durationMinutes / 60f / goalSleepHours).coerceIn(0f, 1f) * 100f
        val effBanded = when {
            efficiency >= Sleep.EFF_EXCELLENT_THRESHOLD -> Sleep.EFF_EXCELLENT_SCORE
            efficiency >= Sleep.EFF_GOOD_THRESHOLD      -> Sleep.EFF_GOOD_SCORE
            efficiency >= Sleep.EFF_FAIR_THRESHOLD      -> Sleep.EFF_FAIR_SCORE
            efficiency >= Sleep.EFF_POOR_THRESHOLD      -> Sleep.EFF_POOR_SCORE
            else                                        -> Sleep.EFF_VERY_POOR_SCORE
        }
        return (Sleep.WEIGHT_TST_IN_DURATION * tstTerm + Sleep.WEIGHT_EFF_IN_DURATION * effBanded)
            .coerceIn(0f, 100f)
    }

    // Age-banded saturation denominators prevent systematic penalisation of older users.
    // REF: Ohayon 2004 Sleep 27:1255; Boulos 2019 Lancet Respir Med 7:533
    fun computeArchSubScore(
        deepSleepMinutes: Int,
        remSleepMinutes: Int,
        durationMinutes: Int,
        userAge: Int = 30,
    ): Float {
        if (durationMinutes == 0) return 0f
        val deepPct = deepSleepMinutes / durationMinutes.toFloat()
        val remPct  = remSleepMinutes  / durationMinutes.toFloat()
        val deepComponent = (deepPct / deepSleepTarget(userAge)).coerceAtMost(1f) * 100f
        val remComponent  = (remPct  / remSleepTarget(userAge)).coerceAtMost(1f) * 100f
        return Sleep.WEIGHT_DEEP_COMPONENT * deepComponent + Sleep.WEIGHT_REM_COMPONENT * remComponent
    }

    private fun deepSleepTarget(age: Int): Float = when {
        age < 30 -> Sleep.DEEP_TARGET_UNDER_30
        age < 50 -> Sleep.DEEP_TARGET_30_49
        age < 65 -> Sleep.DEEP_TARGET_50_64
        else     -> Sleep.DEEP_TARGET_65_PLUS
    }

    private fun remSleepTarget(age: Int): Float = when {
        age < 30 -> Sleep.REM_TARGET_UNDER_30
        age < 50 -> Sleep.REM_TARGET_30_49
        age < 65 -> Sleep.REM_TARGET_50_64
        else     -> Sleep.REM_TARGET_65_PLUS
    }

    // Computes the HRV Z-score on the ln(RMSSD) scale.
    // Display in the UI always uses raw ms; this value drives 0-100 scoring and colouring only.
    // REF: Plews 2013 Sports Med 43:773; Buchheit 2014 Front Physiol 5:73
    fun computeHrvZScore(
        currentRmssdMs: Float,
        rmssdHistory: List<Float>,
        baselineOverride: Float? = null,
    ): Float? {
        if (currentRmssdMs <= 0f && baselineOverride == null && rmssdHistory.isEmpty()) return null
        val lnHistory = rmssdHistory.map { ln(it.coerceAtLeast(0.001f)) }
        val lnToday   = ln(currentRmssdMs.coerceAtLeast(0.001f))
        val mu = if (baselineOverride != null) ln(baselineOverride.coerceAtLeast(0.001f))
                 else lnHistory.mean()
        val sigma = hrvSigma(lnHistory)
        return (lnToday - mu) / sigma
    }

    // RHR scored as Z-score vs. personal rolling baseline.
    // REF: Mishra 2020 Nat Biomed Eng; Buchheit 2014; Quer 2020 Nat Med
    fun computeRhrZScore(
        currentRhrBpm: Float,
        rhrHistory: List<Int>,
        baselineOverride: Float? = null,
    ): Float? {
        if (rhrHistory.isEmpty() && baselineOverride == null) return null
        val mu    = baselineOverride ?: rhrHistory.median()
        val sigma = rhrHistory.map { it.toFloat() }.stdev()
            .takeIf { it > 0f } ?: (mu * 0.05f).coerceAtLeast(1f)
        return (currentRhrBpm - mu) / sigma
    }

    fun computeRestorationSubScore(
        currentHrvMean: Float,
        hrvValues: List<Float>,
        currentNocturnalRhr: Float,
        rhrValues: List<Int>,
        rhrBaselineOverride: Float?,
        hrvBaselineOverride: Float?,
    ): Float {
        val zHrv     = computeHrvZScore(currentHrvMean, hrvValues, hrvBaselineOverride) ?: 0f
        val hrvScore = (50f + 25f * zHrv).coerceIn(0f, 100f)

        // Elevated RHR → positive Z → lower score
        val zRhr     = computeRhrZScore(currentNocturnalRhr, rhrValues, rhrBaselineOverride) ?: 0f
        val rhrScore = (50f - 25f * zRhr).coerceIn(0f, 100f)

        return Restoration.WEIGHT_HRV_SCORE * hrvScore + Restoration.WEIGHT_RHR_SCORE * rhrScore
    }

    fun computeSleepScore(
        durationMinutes: Int,
        efficiency: Float,
        deepSleepMinutes: Int,
        remSleepMinutes: Int,
        goalSleepHours: Float,
        sRest: Float,
        userAge: Int = 30,
    ): Float {
        val sDur  = computeDurationSubScore(durationMinutes, efficiency, goalSleepHours)
        val sArch = computeArchSubScore(deepSleepMinutes, remSleepMinutes, durationMinutes, userAge)
        return Sleep.WEIGHT_DURATION * sDur + Sleep.WEIGHT_ARCHITECTURE * sArch + Sleep.WEIGHT_RESTORATION * sRest
    }

    fun computeReadinessScore(
        sRest: Float,
        sleepScore: Float,
        loadScore: Float,
        zLnHrv: Float? = null,
        zRhr: Float? = null,
        rhrDeltaBpm: Float? = null,
    ): Float {
        var rs = Readiness.WEIGHT_RESTORATION * sRest +
                 Readiness.WEIGHT_SLEEP * sleepScore +
                 Readiness.WEIGHT_LOAD * loadScore

        if (zLnHrv != null && zRhr != null && rhrDeltaBpm != null) {
            // Functional overreaching: HRV↑ AND RHR↓
            // REF: Le Meur 2013 Med Sci Sports Exerc; Bellenger 2017 Front Physiol
            if (zLnHrv > Readiness.OVERREACHING_Z_HRV_THRESHOLD &&
                zRhr   < Readiness.OVERREACHING_Z_RHR_THRESHOLD) {
                rs = rs.coerceAtMost(Readiness.OVERREACHING_MAX_SCORE)
            }
            // Illness onset: HRV↓ AND RHR↑
            // REF: Mishra 2020 Nat Biomed Eng; Quer 2021 Nat Med
            if (zLnHrv < Readiness.ILLNESS_Z_HRV_THRESHOLD &&
                (rhrDeltaBpm >= Readiness.ILLNESS_RHR_DELTA_BPM || zRhr >= 2.0f)) {
                rs = rs.coerceAtMost(Readiness.ILLNESS_MAX_SCORE)
            }
        }

        return rs.coerceIn(0f, 100f)
    }

    // Operates on already-ln-transformed values. σ floor 0.04 on log scale.
    // REF: Plews 2013b Sports Med; Kubios HRV practice
    fun hrvSigma(lnHrvValues: List<Float>): Float =
        if (lnHrvValues.size < ScoringConstants.MATURE_DATA_TENURE_DAYS) {
            maxOf(lnHrvValues.mean() * Restoration.PROVISIONAL_CV_RULE, Restoration.MIN_LN_SIGMA)
        } else {
            lnHrvValues.stdev()
                .takeIf { it > Restoration.MIN_LN_SIGMA }
                ?: maxOf(lnHrvValues.mean() * Restoration.PROVISIONAL_CV_RULE, Restoration.MIN_LN_SIGMA)
        }
}
