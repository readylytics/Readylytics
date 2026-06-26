package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.components.EmergencyFlagThresholds
import app.readylytics.health.domain.scoring.components.RestorationWeights
import app.readylytics.health.domain.scoring.components.SleepArchitectureTargets
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.domain.scoring.strategies.SleepScoringStrategy
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompositeScoringCalculator
    @Inject
    constructor(
        private val sleepStrategy: SleepScoringStrategy,
        private val rasStrategy: RasScoringStrategy,
        private val loadStrategy: LoadScoringStrategy,
    ) : ScoringCalculator {
        override fun computeStrainRatio(
            atl: Float,
            ctl: Float,
        ): Float = rasStrategy.computeStrainRatio(atl, ctl)

        override fun computeCtlEma(
            dailyTrimpList: List<Float>,
            seedFitnessLevel: Float,
            windowDays: Long,
        ): Float = rasStrategy.computeCtlEma(dailyTrimpList, seedFitnessLevel, windowDays)

        override fun computeAtlEma(
            dailyTrimpList: List<Float>,
            seedFatigueLevel: Float,
            windowDays: Long,
        ): Float = rasStrategy.computeAtlEma(dailyTrimpList, seedFatigueLevel, windowDays)

        override fun computeLoadScore(sr: Float): Float = loadStrategy.computeLoadScore(sr)

        override fun computeDurationSubScore(
            durationMinutes: Int,
            efficiency: Float,
            goalSleepHours: Float,
        ): Float = sleepStrategy.computeDurationSubScore(durationMinutes, efficiency, goalSleepHours)

        override fun computeArchSubScore(
            deepSleepMinutes: Int,
            remSleepMinutes: Int,
            durationMinutes: Int,
            userAge: Int,
            sleepTargets: SleepArchitectureTargets?,
        ): Float =
            sleepStrategy.computeArchSubScore(deepSleepMinutes, remSleepMinutes, durationMinutes, userAge, sleepTargets)

        override fun hrvSigma(
            lnHrvValues: List<Float>,
            sigmaPrior: Float,
        ): Float = loadStrategy.hrvSigma(lnHrvValues, sigmaPrior)

        override fun computeHrvZScore(
            currentRmssdMs: Float,
            muHistory: List<Float>,
            sigmaHistory: List<Float>,
            sigmaPrior: Float,
            baselineOverride: Float?,
            frozenLnMu: Float?,
            frozenLnSigma: Float?,
        ): Float? =
            loadStrategy.computeHrvZScore(
                currentRmssdMs,
                muHistory,
                sigmaHistory,
                sigmaPrior,
                baselineOverride,
                frozenLnMu,
                frozenLnSigma,
            )

        override fun computeHrvScore(
            z: Float,
            saturationZ: Float,
        ): Float = loadStrategy.computeHrvScore(z, saturationZ)

        override fun computeRhrZScore(
            currentRhrBpm: Float,
            rhrHistory: List<Int>,
            baselineOverride: Float?,
            frozenSigma: Float?,
        ): Float? = loadStrategy.computeRhrZScore(currentRhrBpm, rhrHistory, baselineOverride, frozenSigma)

        override fun computeRestorationSubScore(
            currentHrvMean: Float,
            muHrvHistory: List<Float>,
            sigmaHrvHistory: List<Float>,
            sigmaPrior: Float,
            currentNocturnalRhr: Float,
            rhrValues: List<Int>,
            rhrBaselineOverride: Float?,
            hrvBaselineOverride: Float?,
            restorationWeights: RestorationWeights?,
            frozenLnMu: Float?,
            frozenLnSigma: Float?,
            frozenRhrSigma: Float?,
            saturationZ: Float,
        ): Float =
            sleepStrategy.computeRestorationSubScore(
                currentHrvMean,
                muHrvHistory,
                sigmaHrvHistory,
                sigmaPrior,
                currentNocturnalRhr,
                rhrValues,
                rhrBaselineOverride,
                hrvBaselineOverride,
                restorationWeights,
                frozenLnMu,
                frozenLnSigma,
                frozenRhrSigma,
                saturationZ,
            )

        override fun computeSleepScore(
            durationMinutes: Int,
            efficiency: Float,
            deepSleepMinutes: Int,
            remSleepMinutes: Int,
            goalSleepHours: Float,
            sRest: Float,
            userAge: Int,
            stagesSuspicious: Boolean,
            sleepTargets: SleepArchitectureTargets?,
        ): Float =
            sleepStrategy.computeSleepScore(
                durationMinutes,
                efficiency,
                deepSleepMinutes,
                remSleepMinutes,
                goalSleepHours,
                sRest,
                userAge,
                stagesSuspicious,
                sleepTargets,
            )

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
            yesterdayTrimp: Float?,
            yesterdayHrv: Float?,
            currentHrv: Float?,
            hrvOptimalThreshold: Float,
            isCurrentHrvOptimal: Boolean,
            isCurrentRhrOptimal: Boolean,
            isPreviousHrvOptimal: Boolean,
        ): Set<RecoveryFlag> =
            loadStrategy.computeRecoveryFlags(
                zLnHrv,
                zRhr,
                rhrDeltaBpm,
                yesterdayZLnHrv,
                yesterdayZRhr,
                hrvMissing,
                stagesSuspicious,
                isLateNadir,
                isCalibrating,
                emergencyFlags,
                yesterdayTrimp,
                yesterdayHrv,
                currentHrv,
                hrvOptimalThreshold,
                isCurrentHrvOptimal,
                isCurrentRhrOptimal,
                isPreviousHrvOptimal,
            )

        override fun computeReadinessScore(
            sRest: Float,
            sleepScore: Float,
            loadScore: Float,
            recoveryFlags: Set<RecoveryFlag>,
        ): Float = loadStrategy.computeReadinessScore(sRest, sleepScore, loadScore, recoveryFlags)

        override fun isLateNadir(
            minHrTimestampMs: Long,
            sessionStartMs: Long,
            durationMinutes: Int,
        ): Boolean = loadStrategy.isLateNadir(minHrTimestampMs, sessionStartMs, durationMinutes)

        override fun computeCtlEmaWithDecay(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeEnd: LocalDate,
            windowDays: Long,
        ): Float = rasStrategy.computeCtlEmaWithDecay(dailyTrimpByDate, rangeEnd, windowDays)

        override fun computeCtlEmaSeries(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeStart: LocalDate,
            rangeEnd: LocalDate,
            windowDays: Long,
        ): Map<LocalDate, Float> = rasStrategy.computeCtlEmaSeries(dailyTrimpByDate, rangeStart, rangeEnd, windowDays)

        override fun computeAtlEmaWithDecay(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeEnd: LocalDate,
            windowDays: Long,
        ): Float = rasStrategy.computeAtlEmaWithDecay(dailyTrimpByDate, rangeEnd, windowDays)

        override fun computeAtlEmaSeries(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeStart: LocalDate,
            rangeEnd: LocalDate,
            windowDays: Long,
        ): Map<LocalDate, Float> = rasStrategy.computeAtlEmaSeries(dailyTrimpByDate, rangeStart, rangeEnd, windowDays)

        override fun validateNight(
            rmssdMs: Float?,
            rhrBpm: Float?,
            durationMinutes: Int,
            deepMinutes: Int,
            remMinutes: Int,
            hrCoverageValid: Boolean,
        ): ScoringCalculator.NightValidationResult =
            loadStrategy.validateNight(
                rmssdMs,
                rhrBpm,
                durationMinutes,
                deepMinutes,
                remMinutes,
                hrCoverageValid,
            )
    }
