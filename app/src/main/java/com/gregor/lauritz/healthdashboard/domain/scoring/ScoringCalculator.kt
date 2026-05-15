package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.domain.model.ReadinessCappingReason
import com.gregor.lauritz.healthdashboard.domain.model.ReadinessRecommendation
import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import com.gregor.lauritz.healthdashboard.domain.model.TrainingAction
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Readiness
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Restoration
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Sleep
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Strain
import com.gregor.lauritz.healthdashboard.domain.scoring.components.EmergencyFlagThresholds
import com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets
import com.gregor.lauritz.healthdashboard.domain.util.logD
import com.gregor.lauritz.healthdashboard.domain.util.mean
import com.gregor.lauritz.healthdashboard.domain.util.median
import com.gregor.lauritz.healthdashboard.domain.util.stdev
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

@Singleton
interface ScoringCalculator {
    fun computeStrainRatio(
        atl: Float,
        ctl: Float,
    ): Float

    fun computeCtlEma(
        dailyTrimpList: List<Float>,
        seedFitnessLevel: Float = ScoringConstants.DEFAULT_FITNESS_LEVEL,
        windowDays: Long = ScoringConstants.CHRONIC_DAYS,
    ): Float

    fun computeAtlEma(
        dailyTrimpList: List<Float>,
        seedFatigueLevel: Float = ScoringConstants.DEFAULT_FITNESS_LEVEL,
        windowDays: Long = ScoringConstants.ACUTE_DAYS,
    ): Float

    fun computeLoadScore(sr: Float): Float

    fun computeDurationSubScore(
        durationMinutes: Int,
        efficiency: Float,
        goalSleepHours: Float,
    ): Float

    fun computeArchSubScore(
        deepSleepMinutes: Int,
        remSleepMinutes: Int,
        durationMinutes: Int,
        userAge: Int = 30,
        sleepTargets: SleepArchitectureTargets? = null,
    ): Float

    fun hrvSigma(
        lnHrvValues: List<Float>,
        sigmaPrior: Float = PhysiologyProfile.GENERAL.lnSigmaPrior,
    ): Float

    fun computeHrvZScore(
        currentRmssdMs: Float,
        muHistory: List<Float>,
        sigmaHistory: List<Float>,
        sigmaPrior: Float = PhysiologyProfile.GENERAL.lnSigmaPrior,
        baselineOverride: Float? = null,
    ): Float?

    fun computeHrvScore(z: Float): Float

    fun computeRhrZScore(
        currentRhrBpm: Float,
        rhrHistory: List<Int>,
        baselineOverride: Float? = null,
    ): Float?

    fun computeRestorationSubScore(
        currentHrvMean: Float,
        muHrvHistory: List<Float>,
        sigmaHrvHistory: List<Float>,
        sigmaPrior: Float = PhysiologyProfile.GENERAL.lnSigmaPrior,
        currentNocturnalRhr: Float,
        rhrValues: List<Int>,
        rhrBaselineOverride: Float?,
        hrvBaselineOverride: Float?,
        restorationWeights: com.gregor.lauritz.healthdashboard.domain.scoring.components.RestorationWeights? = null,
    ): Float

    fun computeSleepScore(
        durationMinutes: Int,
        efficiency: Float,
        deepSleepMinutes: Int,
        remSleepMinutes: Int,
        goalSleepHours: Float,
        sRest: Float,
        userAge: Int = 30,
        stagesSuspicious: Boolean = false,
        sleepTargets: com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets? = null,
    ): Float

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
        emergencyFlags: com.gregor.lauritz.healthdashboard.domain.scoring.components.EmergencyFlagThresholds? = null,
    ): Set<RecoveryFlag>

    fun computeReadinessScore(
        sRest: Float,
        sleepScore: Float,
        loadScore: Float,
        recoveryFlags: Set<RecoveryFlag> = emptySet(),
    ): Float

    /**
     * Rich readiness result that augments [computeReadinessScore] with the
     * capping reason and a [ReadinessRecommendation]. Used by
     * [ComputeSleepMetricsUseCase] (Phase 0.4) to surface medical-grade
     * action guidance instead of a bare number.
     *
     * @param strainRatio current ACWR (acute / chronic workload ratio). Used to
     *  detect the EXTREME_LOAD cap (SR > 2.0).
     * @param consecutiveOverreachingDays number of consecutive nights the
     *  overreaching pattern has fired; controls escalating cap (day 1 → 70,
     *  day 3+ → 55).
     */
    data class ReadinessScoreDetail(
        val score: Float,
        val rawScore: Float,
        val cappingReason: ReadinessCappingReason,
        val recommendation: ReadinessRecommendation,
        val capExplanation: String?,
    )

    fun computeReadinessScoreDetail(
        sRest: Float,
        sleepScore: Float,
        loadScore: Float,
        recoveryFlags: Set<RecoveryFlag> = emptySet(),
        strainRatio: Float? = null,
        consecutiveOverreachingDays: Int = 1,
        consecutiveIllnessDays: Int = 1,
    ): ReadinessScoreDetail

    fun isLateNadir(
        minHrTimestampMs: Long,
        sessionStartMs: Long,
        durationMinutes: Int,
    ): Boolean

    fun computeCtlEmaWithDecay(
        dailyTrimpByDate: Map<LocalDate, Float>,
        rangeEnd: LocalDate,
        windowDays: Long = ScoringConstants.CHRONIC_DAYS,
    ): Float

    fun computeAtlEmaWithDecay(
        dailyTrimpByDate: Map<LocalDate, Float>,
        rangeEnd: LocalDate,
        windowDays: Long = ScoringConstants.ACUTE_DAYS,
    ): Float

    data class NightValidationResult(
        val rmssdValid: Boolean,
        val rhrValid: Boolean,
        val durationValid: Boolean,
        val stagesValid: Boolean,
        val stagesSuspicious: Boolean,
        val hrCoverageValid: Boolean = true,
        /**
         * Human-readable reason the stages were flagged suspicious or invalid
         * (e.g. "REM 28% above age band 26%"). Null when stages are healthy.
         */
        val stagesSuspiciousReason: String? = null,
    ) {
        val canContributeToBaseline: Boolean
            get() = rmssdValid && rhrValid && durationValid && hrCoverageValid
    }

    fun validateNight(
        rmssdMs: Float?,
        rhrBpm: Float?,
        durationMinutes: Int,
        deepMinutes: Int,
        remMinutes: Int,
        hrCoverageValid: Boolean = true,
        userAge: Int = 30,
        deviceSource: String? = null,
    ): NightValidationResult
}

/**
 * Pure Kotlin class containing all scoring formulas.
 * Decoupled from data fetching and persistence for better testability and clean architecture.
 *
 * HRV display values are always in raw ms. The ln(RMSSD) transform is applied internally
 * only for Z-score statistics to normalise the log-normal distribution.
 * REF: Plews 2013 Sports Med 43:773; Buchheit 2014 Front Physiol 5:73
 */
@Singleton
class ScoringCalculatorImpl
    @Inject
    constructor(
        private val sleepArchitectureValidator: SleepArchitectureValidator,
    ) : ScoringCalculator {
        /**
         * Convenience secondary constructor: lets unit tests instantiate without
         * Hilt by relying on the default-constructible [SleepArchitectureValidator].
         */
        constructor() : this(SleepArchitectureValidator())
        override fun computeStrainRatio(
            atl: Float,
            ctl: Float,
        ): Float = if (ctl > 0f) atl / ctl else 0f

        override fun computeCtlEma(
            dailyTrimpList: List<Float>,
            seedFitnessLevel: Float,
            windowDays: Long,
        ): Float = computeEma(dailyTrimpList, seedFitnessLevel, windowDays)

        override fun computeAtlEma(
            dailyTrimpList: List<Float>,
            seedFatigueLevel: Float,
            windowDays: Long,
        ): Float = computeEma(dailyTrimpList, seedFatigueLevel, windowDays)

        private fun computeEma(
            data: List<Float>,
            seed: Float,
            windowDays: Long,
        ): Float {
            val n = data.size
            if (n < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION) return seed

            // Initialize with SMA of the first MIN_SESSIONS_FOR_CALIBRATION days to stabilize the start
            val sma = data.take(ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION).average().toFloat()
            if (n <= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION) return sma

            val alpha = 2f / (windowDays + 1f)
            var currentEma = sma

            for (i in ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION until data.size) {
                currentEma = (data[i] * alpha) + (currentEma * (1f - alpha))
            }

            return currentEma
        }

        // All SR ≤ sweet-spot (1.3) score 100; no penalty for rest days.
        // Above 1.3: smooth quadratic decay with no artificial floor.
        // REF: Gabbett 2016 BJSM; Windt & Gabbett 2018 BJSM; Impellizzeri 2020 IJSPP
        override fun computeLoadScore(sr: Float): Float {
            if (sr <= Strain.SR_SWEET_SPOT_MAX) return Strain.OPTIMAL_SWEET_SPOT_SCORE
            val excess = sr - Strain.SR_SWEET_SPOT_MAX
            return (100f * exp(-Strain.QUADRATIC_PENALTY_K * excess * excess)).coerceIn(0f, 100f)
        }

        // Additive efficiency: TST and efficiency scored separately then combined.
        // Avoids double-penalty because TST = TIB × SE already encodes efficiency.
        // REF: Buysse 1989 PSQI; A.3 review finding
        override fun computeDurationSubScore(
            durationMinutes: Int,
            efficiency: Float,
            goalSleepHours: Float,
        ): Float {
            require(goalSleepHours > 0f) { "goalSleepHours must be > 0" }
            require(efficiency in 0f..100f) { "efficiency must be in [0, 100], was $efficiency" }
            require(durationMinutes >= 0) { "durationMinutes must be >= 0" }
            val tstTerm = (durationMinutes / 60f / goalSleepHours).coerceIn(0f, 1f) * 100f
            val effBanded =
                when {
                    efficiency >= Sleep.EFF_EXCELLENT_THRESHOLD -> Sleep.EFF_EXCELLENT_SCORE
                    efficiency >= Sleep.EFF_GOOD_THRESHOLD -> Sleep.EFF_GOOD_SCORE
                    efficiency >= Sleep.EFF_FAIR_THRESHOLD -> Sleep.EFF_FAIR_SCORE
                    efficiency >= Sleep.EFF_POOR_THRESHOLD -> Sleep.EFF_POOR_SCORE
                    else -> Sleep.EFF_VERY_POOR_SCORE
                }
            return (Sleep.WEIGHT_TST_IN_DURATION * tstTerm + Sleep.WEIGHT_EFF_IN_DURATION * effBanded)
                .coerceIn(0f, 100f)
        }

        // Age-banded saturation denominators prevent systematic penalisation of older users.
        // REF: Ohayon 2004 Sleep 27:1255; Boulos 2019 Lancet Respir Med 7:533
        override fun computeArchSubScore(
            deepSleepMinutes: Int,
            remSleepMinutes: Int,
            durationMinutes: Int,
            userAge: Int,
            sleepTargets: com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets?,
        ): Float {
            require(durationMinutes >= 0) { "durationMinutes must be >= 0" }
            require(deepSleepMinutes >= 0) { "deepSleepMinutes must be >= 0" }
            require(remSleepMinutes >= 0) { "remSleepMinutes must be >= 0" }
            if (durationMinutes == 0) return 0f
            val deepPct = deepSleepMinutes / durationMinutes.toFloat()
            val remPct = remSleepMinutes / durationMinutes.toFloat()
            val (deepTarget, remTarget) =
                if (sleepTargets != null) {
                    Pair(sleepTargets.deepPercentage, sleepTargets.remPercentage)
                } else {
                    Pair(deepSleepTarget(userAge), remSleepTarget(userAge))
                }
            val deepComponent = (deepPct / deepTarget).coerceAtMost(1f) * 100f
            val remComponent = (remPct / remTarget).coerceAtMost(1f) * 100f
            return Sleep.WEIGHT_DEEP_COMPONENT * deepComponent + Sleep.WEIGHT_REM_COMPONENT * remComponent
        }

        private fun deepSleepTarget(age: Int): Float =
            when {
                age < 30 -> Sleep.DEEP_TARGET_UNDER_30
                age < 50 -> Sleep.DEEP_TARGET_30_49
                age < 65 -> Sleep.DEEP_TARGET_50_64
                else -> Sleep.DEEP_TARGET_65_PLUS
            }

        private fun remSleepTarget(age: Int): Float =
            when {
                age < 30 -> Sleep.REM_TARGET_UNDER_30
                age < 50 -> Sleep.REM_TARGET_30_49
                age < 65 -> Sleep.REM_TARGET_50_64
                else -> Sleep.REM_TARGET_65_PLUS
            }

        // Blended provisional sigma on ln-scale.
        // w = clamp((n - 7) / (60 - 7), 0, 1); at n<7 fully prior-driven; at n≥60 fully personal.
        // Heuristic blending — see HrvSigmaTest for validation against published cohorts (Kubios MARS).
        // REF: Plews 2013b Sports Med; Schaffarczyk 2024; Kubios HRV practice
        override fun hrvSigma(
            lnHrvValues: List<Float>,
            sigmaPrior: Float,
        ): Float {
            val n = lnHrvValues.size
            val w =
                (
                    (n - ScoringConstants.HRV_SIGMA_BLEND_MIN_N).toFloat() /
                        (ScoringConstants.HRV_SIGMA_BLEND_MAX_N - ScoringConstants.HRV_SIGMA_BLEND_MIN_N)
                ).coerceIn(0f, 1f)
            val personal = lnHrvValues.stdev()
            val blended = w * personal + (1f - w) * sigmaPrior
            val final = blended.coerceAtLeast(Restoration.MIN_LN_SIGMA)
            logD("HrvSigma") {
                "n=$n w=$w personal=$personal prior=$sigmaPrior blended=$blended final=$final " +
                    "confidence=${hrvSigmaConfidence(n)}"
            }
            return final
        }

        /**
         * Confidence in the personal HRV sigma estimate (0–1 scale).
         * Maps directly to blending weight w; n<7 → 0; n>=60 → 1.
         * Persistable as DailySummaryEntity.hrvSigmaConfidence.
         */
        fun hrvSigmaConfidence(n: Int): Float =
            (
                (n - ScoringConstants.HRV_SIGMA_BLEND_MIN_N).toFloat() /
                    (ScoringConstants.HRV_SIGMA_BLEND_MAX_N - ScoringConstants.HRV_SIGMA_BLEND_MIN_N)
            ).coerceIn(0f, 1f)

        // Computes the HRV Z-score on the ln(RMSSD) scale using separate mean and sigma windows.
        // muHistory     = last 7 valid nights (short-term mean)
        // sigmaHistory  = last 56 valid nights (long-term variance, blended with prior)
        // Display in the UI always uses raw ms; this value drives 0-100 scoring and colouring only.
        // REF: Plews 2013 Sports Med 43:773; Buchheit 2014 Front Physiol 5:73
        override fun computeHrvZScore(
            currentRmssdMs: Float,
            muHistory: List<Float>,
            sigmaHistory: List<Float>,
            sigmaPrior: Float,
            baselineOverride: Float?,
        ): Float? {
            if (currentRmssdMs <= 0f || (baselineOverride == null && muHistory.isEmpty())) return null
            val lnMuHistory = muHistory.map { ln(it.coerceAtLeast(0.001f)) }
            val lnSigmaHistory = sigmaHistory.map { ln(it.coerceAtLeast(0.001f)) }
            val lnToday = ln(currentRmssdMs.coerceAtLeast(0.001f))
            val mu =
                if (baselineOverride != null) {
                    ln(baselineOverride.coerceAtLeast(0.001f))
                } else {
                    lnMuHistory.mean()
                }
            val sigma = hrvSigma(lnSigmaHistory, sigmaPrior)
            return (lnToday - mu) / sigma
        }

        // Piecewise HRV score: linear below z=1.5, soft saturation above.
        // Preserves sensitivity near baseline; avoids ceiling lock for very high HRV.
        // REF: spec §4.2; Bellenger 2017 Front Physiol
        override fun computeHrvScore(z: Float): Float {
            val adjustedZ =
                if (z > ScoringConstants.HRV_SCORE_SATURATION_Z) {
                    ScoringConstants.HRV_SCORE_SATURATION_Z +
                        ScoringConstants.HRV_SCORE_SATURATION_SLOPE * (z - ScoringConstants.HRV_SCORE_SATURATION_Z)
                } else {
                    z
                }
            return (50f + 25f * adjustedZ).coerceIn(0f, 100f)
        }

        // RHR scored as Z-score vs. personal rolling baseline.
        // REF: Mishra 2020 Nat Biomed Eng; Buchheit 2014; Quer 2020 Nat Med
        override fun computeRhrZScore(
            currentRhrBpm: Float,
            rhrHistory: List<Int>,
            baselineOverride: Float?,
        ): Float? {
            if (rhrHistory.isEmpty() && baselineOverride == null) return null
            val mu = baselineOverride ?: rhrHistory.median()
            val sigma =
                rhrHistory
                    .stdev()
                    .takeIf { it > 0f } ?: (mu * 0.05f).coerceAtLeast(1f)
            return (currentRhrBpm - mu) / sigma
        }

        override fun computeRestorationSubScore(
            currentHrvMean: Float,
            muHrvHistory: List<Float>,
            sigmaHrvHistory: List<Float>,
            sigmaPrior: Float,
            currentNocturnalRhr: Float,
            rhrValues: List<Int>,
            rhrBaselineOverride: Float?,
            hrvBaselineOverride: Float?,
            restorationWeights: com.gregor.lauritz.healthdashboard.domain.scoring.components.RestorationWeights?,
        ): Float {
            val zHrv =
                computeHrvZScore(currentHrvMean, muHrvHistory, sigmaHrvHistory, sigmaPrior, hrvBaselineOverride) ?: 0f
            val hrvScore = computeHrvScore(zHrv)

            // Elevated RHR → positive Z → lower score
            val zRhr = computeRhrZScore(currentNocturnalRhr, rhrValues, rhrBaselineOverride) ?: 0f
            val rhrScore = (50f - 25f * zRhr).coerceIn(0f, 100f)

            return if (restorationWeights != null) {
                restorationWeights.hrvWeight * hrvScore + restorationWeights.rhrWeight * rhrScore
            } else {
                Restoration.WEIGHT_HRV_SCORE * hrvScore + Restoration.WEIGHT_RHR_SCORE * rhrScore
            }
        }

        override fun computeSleepScore(
            durationMinutes: Int,
            efficiency: Float,
            deepSleepMinutes: Int,
            remSleepMinutes: Int,
            goalSleepHours: Float,
            sRest: Float,
            userAge: Int,
            stagesSuspicious: Boolean,
            sleepTargets: com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets?,
        ): Float {
            require(durationMinutes >= 0) { "durationMinutes must be >= 0" }
            require(goalSleepHours > 0f) { "goalSleepHours must be > 0" }
            val sDur = computeDurationSubScore(durationMinutes, efficiency, goalSleepHours)
            val sArch = computeArchSubScore(deepSleepMinutes, remSleepMinutes, durationMinutes, userAge, sleepTargets)

            val durationWeight = if (stagesSuspicious) 0.75f else Sleep.WEIGHT_DURATION
            val archWeight = if (stagesSuspicious) 0.00f else Sleep.WEIGHT_ARCHITECTURE

            return durationWeight * sDur + archWeight * sArch + Sleep.WEIGHT_RESTORATION * sRest
        }

        // 2-night consecutive confirmation required for OVERREACHING and ILLNESS_ONSET.
        // Single-night anomalies are common noise; two nights in a row signals a genuine state.
        // REF: Le Meur 2013 Med Sci Sports Exerc; Mishra 2020 Nat Biomed Eng
        override fun computeRecoveryFlags(
            zLnHrv: Float?,
            zRhr: Float?,
            rhrDeltaBpm: Float?,
            yesterdayZLnHrv: Float?,
            yesterdayZRhr: Float?,
            hrvMissing: Boolean,
            stagesSuspicious: Boolean,
            isLateNadir: Boolean,
            isCalibrating: Boolean,
            emergencyFlags: EmergencyFlagThresholds?,
        ): Set<RecoveryFlag> {
            val flags = mutableSetOf<RecoveryFlag>()
            if (isCalibrating) flags += RecoveryFlag.CALIBRATING
            if (hrvMissing) flags += RecoveryFlag.HRV_MISSING
            if (stagesSuspicious) flags += RecoveryFlag.STAGES_MISSING
            if (isLateNadir) flags += RecoveryFlag.NADIR_DELAYED

            // Use provided thresholds or fall back to defaults
            val thresholds = emergencyFlags ?: EmergencyFlagThresholds()

            if (zLnHrv != null && zRhr != null) {
                val todayOverreaching =
                    zLnHrv > thresholds.overreachingZHrvThreshold &&
                        zRhr < thresholds.overreachingZRhrThreshold
                val prevOverreaching =
                    yesterdayZLnHrv != null &&
                        yesterdayZRhr != null &&
                        yesterdayZLnHrv > thresholds.overreachingZHrvThreshold &&
                        yesterdayZRhr < thresholds.overreachingZRhrThreshold
                if (todayOverreaching && prevOverreaching) flags += RecoveryFlag.OVERREACHING

                val todayIllness =
                    zLnHrv < thresholds.illnessZHrvThreshold &&
                        (
                            rhrDeltaBpm != null &&
                                rhrDeltaBpm >= thresholds.illnessRhrDeltaBpm ||
                                zRhr >= thresholds.illnessZRhrThreshold
                        )
                val prevIllness =
                    yesterdayZLnHrv != null &&
                        yesterdayZRhr != null &&
                        yesterdayZLnHrv < thresholds.illnessZHrvThreshold &&
                        yesterdayZRhr >= thresholds.illnessZRhrThreshold
                if (todayIllness && prevIllness) flags += RecoveryFlag.ILLNESS_ONSET
            }
            return flags
        }

        override fun computeReadinessScore(
            sRest: Float,
            sleepScore: Float,
            loadScore: Float,
            recoveryFlags: Set<RecoveryFlag>,
        ): Float =
            computeReadinessScoreDetail(
                sRest = sRest,
                sleepScore = sleepScore,
                loadScore = loadScore,
                recoveryFlags = recoveryFlags,
            ).score

        override fun computeReadinessScoreDetail(
            sRest: Float,
            sleepScore: Float,
            loadScore: Float,
            recoveryFlags: Set<RecoveryFlag>,
            strainRatio: Float?,
            consecutiveOverreachingDays: Int,
            consecutiveIllnessDays: Int,
        ): ScoringCalculator.ReadinessScoreDetail {
            val raw =
                Readiness.WEIGHT_RESTORATION * sRest +
                    Readiness.WEIGHT_SLEEP * sleepScore +
                    Readiness.WEIGHT_LOAD * loadScore

            var rs = raw
            var cappingReason = ReadinessCappingReason.NONE
            var capExplanation: String? = null

            // Functional overreaching: HRV↑ AND RHR↓ on 2 consecutive nights.
            // Escalating cap: 70 on day 1-2, 55 on day 3+.
            if (RecoveryFlag.OVERREACHING in recoveryFlags) {
                val cap =
                    if (consecutiveOverreachingDays >= Readiness.OVERREACHING_ESCALATED_DAYS) {
                        Readiness.OVERREACHING_ESCALATED_MAX_SCORE
                    } else {
                        Readiness.OVERREACHING_MAX_SCORE
                    }
                if (rs > cap) {
                    rs = cap
                    cappingReason = ReadinessCappingReason.OVERREACHING_CONSECUTIVE
                    capExplanation =
                        "Readiness capped at ${cap.toInt()}: overreaching pattern (high HRV, low RHR) " +
                            "for $consecutiveOverreachingDays night(s)"
                }
            }

            // Illness onset: HRV↓ AND RHR↑ on 2 consecutive nights.
            // Illness cap is stricter than overreaching and takes precedence when both fire.
            if (RecoveryFlag.ILLNESS_ONSET in recoveryFlags) {
                if (rs > Readiness.ILLNESS_MAX_SCORE) {
                    rs = Readiness.ILLNESS_MAX_SCORE
                    cappingReason = ReadinessCappingReason.ILLNESS_ONSET_CONSECUTIVE
                    capExplanation =
                        "Readiness capped at ${Readiness.ILLNESS_MAX_SCORE.toInt()}: illness signature " +
                            "(low HRV, elevated RHR) for $consecutiveIllnessDays night(s)"
                }
            }

            // Extreme training load (SR > 2.0) — independent of cardiac flags.
            if (strainRatio != null && strainRatio > Readiness.EXTREME_LOAD_SR_THRESHOLD) {
                if (rs > Readiness.EXTREME_LOAD_MAX_SCORE) {
                    rs = Readiness.EXTREME_LOAD_MAX_SCORE
                    cappingReason = ReadinessCappingReason.EXTREME_LOAD
                    capExplanation =
                        "Readiness capped at ${Readiness.EXTREME_LOAD_MAX_SCORE.toInt()}: training load very " +
                            "high (SR=${"%.2f".format(strainRatio)} > 2.0)"
                }
            }

            val finalScore = rs.coerceIn(0f, 100f)
            return ScoringCalculator.ReadinessScoreDetail(
                score = finalScore,
                rawScore = raw.coerceIn(0f, 100f),
                cappingReason = cappingReason,
                recommendation = buildRecommendation(finalScore, cappingReason),
                capExplanation = capExplanation,
            )
        }

        private fun buildRecommendation(
            score: Float,
            cappingReason: ReadinessCappingReason,
        ): ReadinessRecommendation {
            val action =
                when {
                    score >= Readiness.ACTION_FULL_EFFORT_THRESHOLD -> TrainingAction.FULL_EFFORT
                    score >= Readiness.ACTION_NORMAL_THRESHOLD -> TrainingAction.NORMAL
                    score >= Readiness.ACTION_LIGHT_THRESHOLD -> TrainingAction.LIGHT_ACTIVITY
                    else -> TrainingAction.REST
                }
            return when (cappingReason) {
                ReadinessCappingReason.OVERREACHING_CONSECUTIVE ->
                    ReadinessRecommendation(
                        action = TrainingAction.LIGHT_ACTIVITY,
                        message =
                            "HRV elevated 2+ nights with low RHR. Reduce training intensity 3-5 days. " +
                                "Stay hydrated and prioritise sleep.",
                        durationDays = ReadinessRecommendation.OVERREACHING_RECOMMENDATION_DAYS,
                    )
                ReadinessCappingReason.ILLNESS_ONSET_CONSECUTIVE ->
                    ReadinessRecommendation(
                        action = TrainingAction.REST,
                        message =
                            "RHR elevated with low HRV. May indicate infection or autonomic stress. " +
                                "Rest today and monitor symptoms; consult a clinician if fever or fatigue persists.",
                        durationDays = ReadinessRecommendation.ILLNESS_RECOMMENDATION_DAYS,
                    )
                ReadinessCappingReason.EXTREME_LOAD ->
                    ReadinessRecommendation(
                        action = TrainingAction.LIGHT_ACTIVITY,
                        message =
                            "Acute training load very high (SR > 2.0). Prioritise recovery the next 3 days " +
                                "to avoid non-functional overreaching.",
                        durationDays = ReadinessRecommendation.EXTREME_LOAD_RECOMMENDATION_DAYS,
                    )
                ReadinessCappingReason.NONE ->
                    ReadinessRecommendation(
                        action = action,
                        message =
                            when (action) {
                                TrainingAction.FULL_EFFORT ->
                                    "Excellent recovery — full-effort training is appropriate today."
                                TrainingAction.NORMAL ->
                                    "Solid recovery — train normally; listen to perceived exertion."
                                TrainingAction.LIGHT_ACTIVITY ->
                                    "Suboptimal recovery — keep today light (active recovery / Z1-Z2)."
                                TrainingAction.REST ->
                                    "Poor recovery — rest day recommended; revisit tomorrow."
                            },
                    )
            }
        }

        // Pure function — moves late-nadir check out of ScoringRepository for testability.
        // REF: Trinder 2001 J Sleep Res 10:253 — last-third cutoff
        override fun isLateNadir(
            minHrTimestampMs: Long,
            sessionStartMs: Long,
            durationMinutes: Int,
        ): Boolean {
            if (durationMinutes <= 0) return false
            val sessionDurationMs = durationMinutes * 60 * 1000L
            return (minHrTimestampMs - sessionStartMs) >
                (sessionDurationMs * Restoration.LATE_NADIR_THRESHOLD)
        }

        override fun computeCtlEmaWithDecay(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeEnd: LocalDate,
            windowDays: Long,
        ): Float = computeEmaWithDecay(dailyTrimpByDate, windowDays, rangeEnd)

        override fun computeAtlEmaWithDecay(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeEnd: LocalDate,
            windowDays: Long,
        ): Float = computeEmaWithDecay(dailyTrimpByDate, windowDays, rangeEnd)

        private fun computeEmaWithDecay(
            dailyTrimpByDate: Map<LocalDate, Float>,
            windowDays: Long,
            rangeEnd: LocalDate,
        ): Float {
            if (dailyTrimpByDate.isEmpty()) return ScoringConstants.DEFAULT_FITNESS_LEVEL

            // Decouple the historical calculation window from the EMA time constant.
            // Starting from the earliest available date allows the initial seed to wash out.
            val earliestDataDate = dailyTrimpByDate.keys.minOrNull() ?: rangeEnd
            val defaultStart = rangeEnd.minusDays(windowDays - 1)
            val rangeStart = if (earliestDataDate.isBefore(defaultStart)) earliestDataDate else defaultStart

            val alpha = 2.0 / (windowDays + 1)
            var ewma = (dailyTrimpByDate[rangeStart] ?: 0.0).toDouble()
            var date = rangeStart.plusDays(1)
            while (!date.isAfter(rangeEnd)) {
                val trimp = (dailyTrimpByDate[date] ?: 0.0).toDouble()
                ewma = trimp * alpha + ewma * (1.0 - alpha)
                date = date.plusDays(1)
            }
            return ewma.toFloat()
        }

        override fun validateNight(
            rmssdMs: Float?,
            rhrBpm: Float?,
            durationMinutes: Int,
            deepMinutes: Int,
            remMinutes: Int,
            hrCoverageValid: Boolean,
            userAge: Int,
            deviceSource: String?,
        ): ScoringCalculator.NightValidationResult {
            val rmssdValid =
                rmssdMs != null &&
                    rmssdMs in ScoringConstants.MIN_VALID_RMSSD_MS..ScoringConstants.MAX_VALID_RMSSD_MS
            val rhrValid =
                rhrBpm == null ||
                    rhrBpm in ScoringConstants.MIN_VALID_SLEEP_RHR..ScoringConstants.MAX_VALID_SLEEP_RHR
            val durationValid = durationMinutes >= ScoringConstants.MIN_VALID_SLEEP_DURATION_MINUTES

            val deepFrac = if (durationMinutes > 0) deepMinutes / durationMinutes.toFloat() else 0f
            val remFrac = if (durationMinutes > 0) remMinutes / durationMinutes.toFloat() else 0f

            // Age-banded + device-corrected validation per Ohayon 2004; replaces
            // hardcoded global thresholds. REF: SleepArchitectureValidator.
            val archResult =
                sleepArchitectureValidator.evaluate(
                    deepFraction = deepFrac,
                    remFraction = remFrac,
                    age = userAge,
                    deviceSource = deviceSource,
                )

            return ScoringCalculator.NightValidationResult(
                rmssdValid = rmssdValid,
                rhrValid = rhrValid,
                durationValid = durationValid,
                stagesValid = archResult.valid,
                stagesSuspicious = archResult.suspicious,
                hrCoverageValid = hrCoverageValid,
                stagesSuspiciousReason = archResult.warning,
            )
        }
    }
