package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
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
    ): Float = computeEma(dailyTrimpList, seedFitnessLevel, windowDays)

    fun computeAtlEma(
        dailyTrimpList: List<Float>,
        seedFatigueLevel: Float = ScoringConstants.DEFAULT_FITNESS_LEVEL,
        windowDays: Long = ScoringConstants.ACUTE_DAYS,
    ): Float = computeEma(dailyTrimpList, seedFatigueLevel, windowDays)

    private fun computeEma(
        data: List<Float>,
        seed: Float,
        windowDays: Long,
    ): Float {
        val n = data.size
        if (n < 7) return seed

        // Initialize with SMA of the first 7 days to stabilize the start
        val sma = data.take(7).average().toFloat()
        if (n <= 7) return sma

        val alpha = 2f / (windowDays + 1f)
        var currentEma = sma

        for (i in 7 until data.size) {
            currentEma = (data[i] * alpha) + (currentEma * (1f - alpha))
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
        require(goalSleepHours > 0f)        { "goalSleepHours must be > 0" }
        require(efficiency in 0f..100f)     { "efficiency must be in [0, 100], was $efficiency" }
        require(durationMinutes >= 0)       { "durationMinutes must be >= 0" }
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
        require(durationMinutes >= 0)    { "durationMinutes must be >= 0" }
        require(deepSleepMinutes >= 0)   { "deepSleepMinutes must be >= 0" }
        require(remSleepMinutes >= 0)    { "remSleepMinutes must be >= 0" }
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

    // Blended provisional sigma on ln-scale.
    // w = clamp((n - 7) / (60 - 7), 0, 1); at n<7 fully prior-driven; at n≥60 fully personal.
    // REF: Plews 2013b Sports Med; Schaffarczyk 2024; Kubios HRV practice
    fun hrvSigma(lnHrvValues: List<Float>, sigmaPrior: Float = PhysiologyProfile.GENERAL.lnSigmaPrior): Float {
        val n = lnHrvValues.size
        val w = ((n - ScoringConstants.HRV_SIGMA_BLEND_MIN_N).toFloat() /
                 (ScoringConstants.HRV_SIGMA_BLEND_MAX_N - ScoringConstants.HRV_SIGMA_BLEND_MIN_N))
                 .coerceIn(0f, 1f)
        val blended = w * lnHrvValues.stdev() + (1f - w) * sigmaPrior
        return blended.coerceAtLeast(Restoration.MIN_LN_SIGMA)
    }

    // Computes the HRV Z-score on the ln(RMSSD) scale using separate mean and sigma windows.
    // muHistory     = last 7 valid nights (short-term mean)
    // sigmaHistory  = last 56 valid nights (long-term variance, blended with prior)
    // Display in the UI always uses raw ms; this value drives 0-100 scoring and colouring only.
    // REF: Plews 2013 Sports Med 43:773; Buchheit 2014 Front Physiol 5:73
    fun computeHrvZScore(
        currentRmssdMs: Float,
        muHistory: List<Float>,
        sigmaHistory: List<Float>,
        sigmaPrior: Float = PhysiologyProfile.GENERAL.lnSigmaPrior,
        baselineOverride: Float? = null,
    ): Float? {
        if (currentRmssdMs <= 0f || (baselineOverride == null && muHistory.isEmpty())) return null
        val lnMuHistory    = muHistory.map    { ln(it.coerceAtLeast(0.001f)) }
        val lnSigmaHistory = sigmaHistory.map { ln(it.coerceAtLeast(0.001f)) }
        val lnToday = ln(currentRmssdMs.coerceAtLeast(0.001f))
        val mu = if (baselineOverride != null) ln(baselineOverride.coerceAtLeast(0.001f))
                 else lnMuHistory.mean()
        val sigma = hrvSigma(lnSigmaHistory, sigmaPrior)
        return (lnToday - mu) / sigma
    }

    // Piecewise HRV score: linear below z=1.5, soft saturation above.
    // Preserves sensitivity near baseline; avoids ceiling lock for very high HRV.
    // REF: spec §4.2; Bellenger 2017 Front Physiol
    fun computeHrvScore(z: Float): Float {
        val adjustedZ = if (z > ScoringConstants.HRV_SCORE_SATURATION_Z)
            ScoringConstants.HRV_SCORE_SATURATION_Z +
                ScoringConstants.HRV_SCORE_SATURATION_SLOPE * (z - ScoringConstants.HRV_SCORE_SATURATION_Z)
        else z
        return (50f + 25f * adjustedZ).coerceIn(0f, 100f)
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
        val sigma = rhrHistory.stdev()
            .takeIf { it > 0f } ?: (mu * 0.05f).coerceAtLeast(1f)
        return (currentRhrBpm - mu) / sigma
    }

    fun computeRestorationSubScore(
        currentHrvMean: Float,
        muHrvHistory: List<Float>,
        sigmaHrvHistory: List<Float>,
        sigmaPrior: Float = PhysiologyProfile.GENERAL.lnSigmaPrior,
        currentNocturnalRhr: Float,
        rhrValues: List<Int>,
        rhrBaselineOverride: Float?,
        hrvBaselineOverride: Float?,
    ): Float {
        val zHrv     = computeHrvZScore(currentHrvMean, muHrvHistory, sigmaHrvHistory, sigmaPrior, hrvBaselineOverride) ?: 0f
        val hrvScore = computeHrvScore(zHrv)

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
        require(durationMinutes >= 0) { "durationMinutes must be >= 0" }
        require(goalSleepHours > 0f)  { "goalSleepHours must be > 0" }
        val sDur  = computeDurationSubScore(durationMinutes, efficiency, goalSleepHours)
        val sArch = computeArchSubScore(deepSleepMinutes, remSleepMinutes, durationMinutes, userAge)
        return Sleep.WEIGHT_DURATION * sDur + Sleep.WEIGHT_ARCHITECTURE * sArch + Sleep.WEIGHT_RESTORATION * sRest
    }

    // 2-night consecutive confirmation required for OVERREACHING and ILLNESS_ONSET.
    // Single-night anomalies are common noise; two nights in a row signals a genuine state.
    // REF: Le Meur 2013 Med Sci Sports Exerc; Mishra 2020 Nat Biomed Eng
    fun computeRecoveryFlags(
        zLnHrv: Float?,
        zRhr: Float?,
        rhrDeltaBpm: Float?,
        yesterdayZLnHrv: Float?,
        yesterdayZRhr: Float?,
        hrvMissing: Boolean,
        stagesSuspicious: Boolean,
        isLateNadir: Boolean,
        isCalibrating: Boolean,
    ): Set<RecoveryFlag> {
        val flags = mutableSetOf<RecoveryFlag>()
        if (isCalibrating)    flags += RecoveryFlag.CALIBRATING
        if (hrvMissing)       flags += RecoveryFlag.HRV_MISSING
        if (stagesSuspicious) flags += RecoveryFlag.STAGES_MISSING
        if (isLateNadir)      flags += RecoveryFlag.NADIR_DELAYED

        if (zLnHrv != null && zRhr != null) {
            val todayOverreaching = zLnHrv > Readiness.OVERREACHING_Z_HRV_THRESHOLD &&
                                    zRhr   < Readiness.OVERREACHING_Z_RHR_THRESHOLD
            val prevOverreaching  = yesterdayZLnHrv != null && yesterdayZRhr != null &&
                                    yesterdayZLnHrv > Readiness.OVERREACHING_Z_HRV_THRESHOLD &&
                                    yesterdayZRhr   < Readiness.OVERREACHING_Z_RHR_THRESHOLD
            if (todayOverreaching && prevOverreaching) flags += RecoveryFlag.OVERREACHING

            val todayIllness = zLnHrv < Readiness.ILLNESS_Z_HRV_THRESHOLD &&
                               (rhrDeltaBpm != null && rhrDeltaBpm >= Readiness.ILLNESS_RHR_DELTA_BPM ||
                                zRhr >= 2.0f)
            val prevIllness  = yesterdayZLnHrv != null && yesterdayZRhr != null &&
                               yesterdayZLnHrv < Readiness.ILLNESS_Z_HRV_THRESHOLD &&
                               yesterdayZRhr   >= 2.0f
            if (todayIllness && prevIllness) flags += RecoveryFlag.ILLNESS_ONSET
        }
        return flags
    }

    fun computeReadinessScore(
        sRest: Float,
        sleepScore: Float,
        loadScore: Float,
        recoveryFlags: Set<RecoveryFlag> = emptySet(),
    ): Float {
        var rs = Readiness.WEIGHT_RESTORATION * sRest +
                 Readiness.WEIGHT_SLEEP * sleepScore +
                 Readiness.WEIGHT_LOAD * loadScore

        // Functional overreaching: HRV↑ AND RHR↓ on 2 consecutive nights
        // REF: Le Meur 2013 Med Sci Sports Exerc; Bellenger 2017 Front Physiol
        if (RecoveryFlag.OVERREACHING in recoveryFlags) {
            rs = rs.coerceAtMost(Readiness.OVERREACHING_MAX_SCORE)
        }
        // Illness onset: HRV↓ AND RHR↑ on 2 consecutive nights
        // REF: Mishra 2020 Nat Biomed Eng; Quer 2021 Nat Med
        if (RecoveryFlag.ILLNESS_ONSET in recoveryFlags) {
            rs = rs.coerceAtMost(Readiness.ILLNESS_MAX_SCORE)
        }

        return rs.coerceIn(0f, 100f)
    }

    // Pure function — moves late-nadir check out of ScoringRepository for testability.
    // REF: Trinder 2001 J Sleep Res 10:253 — last-third cutoff
    fun isLateNadir(
        minHrTimestampMs: Long,
        sessionStartMs: Long,
        durationMinutes: Int,
    ): Boolean {
        if (durationMinutes <= 0) return false
        val sessionDurationMs = durationMinutes * 60 * 1000L
        return (minHrTimestampMs - sessionStartMs) >
               (sessionDurationMs * Restoration.LATE_NADIR_THRESHOLD)
    }

    // Valid-night pipeline — validates raw inputs before they contribute to baselines.
    // REF: Task Force 1996 Eur Heart J 17:354; Clifford 2006 Advanced Methods for ECG Analysis
    data class NightValidationResult(
        val rmssdValid: Boolean,
        val rhrValid: Boolean,
        val durationValid: Boolean,
        val stagesValid: Boolean,
        val stagesSuspicious: Boolean,
    ) {
        val canContributeToBaseline: Boolean
            get() = rmssdValid && rhrValid && durationValid
    }

    fun validateNight(
        rmssdMs: Float?,
        rhrBpm: Float?,
        durationMinutes: Int,
        deepMinutes: Int,
        remMinutes: Int,
    ): NightValidationResult {
        val rmssdValid = rmssdMs != null &&
                         rmssdMs in ScoringConstants.MIN_VALID_RMSSD_MS..ScoringConstants.MAX_VALID_RMSSD_MS
        val rhrValid   = rhrBpm == null ||
                         rhrBpm in ScoringConstants.MIN_VALID_SLEEP_RHR..ScoringConstants.MAX_VALID_SLEEP_RHR
        val durationValid = durationMinutes >= ScoringConstants.MIN_VALID_SLEEP_DURATION_MINUTES

        val deepFrac = if (durationMinutes > 0) deepMinutes / durationMinutes.toFloat() else 0f
        val remFrac  = if (durationMinutes > 0) remMinutes  / durationMinutes.toFloat() else 0f
        val stagesInvalid    = deepFrac > ScoringConstants.MAX_VALID_DEEP_FRACTION ||
                               remFrac  > ScoringConstants.MAX_VALID_REM_FRACTION
        val stagesSuspicious = !stagesInvalid &&
                               (deepFrac + remFrac) > ScoringConstants.MAX_VALID_DEEP_REM_SUM

        return NightValidationResult(
            rmssdValid       = rmssdValid,
            rhrValid         = rhrValid,
            durationValid    = durationValid,
            stagesValid      = !stagesInvalid,
            stagesSuspicious = stagesSuspicious,
        )
    }
}
