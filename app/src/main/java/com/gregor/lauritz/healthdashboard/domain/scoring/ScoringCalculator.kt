package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import com.gregor.lauritz.healthdashboard.domain.scoring.components.EmergencyFlagThresholds
import com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets
import java.time.LocalDate
import javax.inject.Singleton

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
        frozenLnMu: Float? = null,
        frozenLnSigma: Float? = null,
    ): Float?

    fun computeHrvScore(
        z: Float,
        saturationZ: Float = ScoringConstants.HRV_SCORE_SATURATION_Z,
    ): Float

    fun computeRhrZScore(
        currentRhrBpm: Float,
        rhrHistory: List<Int>,
        baselineOverride: Float? = null,
        frozenSigma: Float? = null,
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
        frozenLnMu: Float? = null,
        frozenLnSigma: Float? = null,
        frozenRhrSigma: Float? = null,
        saturationZ: Float = ScoringConstants.HRV_SCORE_SATURATION_Z,
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
    ): NightValidationResult
}
