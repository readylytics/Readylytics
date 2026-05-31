package com.gregor.lauritz.healthdashboard.domain.scoring.strategies

import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Restoration
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Sleep
import com.gregor.lauritz.healthdashboard.domain.scoring.components.RestorationWeights
import com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.round

@Singleton
class SleepScoringStrategy
    @Inject
    constructor(
        private val loadStrategy: LoadScoringStrategy,
    ) {
        fun computeDurationSubScore(
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

        fun computeArchSubScore(
            deepSleepMinutes: Int,
            remSleepMinutes: Int,
            durationMinutes: Int,
            userAge: Int,
            sleepTargets: SleepArchitectureTargets?,
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
            val result = Sleep.WEIGHT_DEEP_COMPONENT * deepComponent + Sleep.WEIGHT_REM_COMPONENT * remComponent
            return round(result * 10) / 10
        }

        fun computeRestorationSubScore(
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
        ): Float {
            val zHrv =
                loadStrategy.computeHrvZScore(
                    currentHrvMean,
                    muHrvHistory,
                    sigmaHrvHistory,
                    sigmaPrior,
                    baselineOverride = hrvBaselineOverride,
                    frozenLnMu = frozenLnMu,
                )
                    ?: 0f
            val hrvScore = loadStrategy.computeHrvScore(zHrv)

            val zRhr = loadStrategy.computeRhrZScore(currentNocturnalRhr, rhrValues, rhrBaselineOverride) ?: 0f
            val rhrScore = (50f - 25f * zRhr).coerceIn(0f, 100f)

            return if (restorationWeights != null) {
                restorationWeights.hrvWeight * hrvScore + restorationWeights.rhrWeight * rhrScore
            } else {
                Restoration.WEIGHT_HRV_SCORE * hrvScore + Restoration.WEIGHT_RHR_SCORE * rhrScore
            }
        }

        fun computeSleepScore(
            durationMinutes: Int,
            efficiency: Float,
            deepSleepMinutes: Int,
            remSleepMinutes: Int,
            goalSleepHours: Float,
            sRest: Float,
            userAge: Int,
            stagesSuspicious: Boolean,
            sleepTargets: SleepArchitectureTargets?,
        ): Float {
            require(durationMinutes >= 0) { "durationMinutes must be >= 0" }
            require(goalSleepHours > 0f) { "goalSleepHours must be > 0" }
            val sDur = computeDurationSubScore(durationMinutes, efficiency, goalSleepHours)
            val sArch = computeArchSubScore(deepSleepMinutes, remSleepMinutes, durationMinutes, userAge, sleepTargets)

            val durationWeight = if (stagesSuspicious) 0.75f else Sleep.WEIGHT_DURATION
            val archWeight = if (stagesSuspicious) 0.00f else Sleep.WEIGHT_ARCHITECTURE

            return durationWeight * sDur + archWeight * sArch + Sleep.WEIGHT_RESTORATION * sRest
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
    }
