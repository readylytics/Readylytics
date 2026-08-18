package app.readylytics.health.domain.scoring.strategies

import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.domain.scoring.ScoringConstants.Restoration
import app.readylytics.health.domain.scoring.ScoringConstants.Sleep
import app.readylytics.health.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.domain.scoring.components.RestorationWeights
import app.readylytics.health.domain.scoring.components.SleepArchitectureTargetFactory
import app.readylytics.health.domain.scoring.components.SleepArchitectureTargets
import app.readylytics.health.domain.scoring.components.SleepContinuityCurves
import app.readylytics.health.domain.scoring.sleep.SleepFragmentation
import javax.inject.Inject
import javax.inject.Singleton

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
            hypersomniaOnsetRatio: Float = Sleep.DEFAULT_HYPERSOMNIA_ONSET_RATIO,
        ): Float {
            require(goalSleepHours > 0f) { "goalSleepHours must be > 0" }
            require(efficiency in 0f..100f) { "efficiency must be in [0, 100], was $efficiency" }
            require(durationMinutes >= 0) { "durationMinutes must be >= 0" }
            val ratio = durationMinutes / 60f / goalSleepHours
            val tstTerm = SleepContinuityCurves.durationTerm(ratio, hypersomniaOnsetRatio)
            val effTerm = SleepContinuityCurves.efficiencyTerm(efficiency)
            return (Sleep.WEIGHT_TST_IN_DURATION * tstTerm + Sleep.WEIGHT_EFF_IN_DURATION * effTerm)
                .coerceIn(0f, 100f)
        }

        fun computeFragmentationSubScore(fragmentation: SleepFragmentation): Float =
            SleepContinuityCurves.fragmentationTerm(
                fragmentation.wasoMinutes.coerceAtLeast(0f),
                fragmentation.awakeningCount.coerceAtLeast(0),
            )

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
            val resolvedTargets = sleepTargets ?: SleepArchitectureTargetFactory.create(userAge)
            val deepTarget = resolvedTargets.deepPercentage
            val remTarget = resolvedTargets.remPercentage
            val deepComponent = (deepPct / deepTarget).coerceAtMost(1f) * 100f
            val remComponent = (remPct / remTarget).coerceAtMost(1f) * 100f
            // Return full precision; rounding happens only at the final UI/DAO boundary.
            // Pre-rounding here previously leaked into the weighted sleep-score sum (computeSleepScore),
            // causing the composite score to shift by ±1 on recalculation.
            return Sleep.WEIGHT_DEEP_COMPONENT * deepComponent + Sleep.WEIGHT_REM_COMPONENT * remComponent
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
            frozenLnSigma: Float?,
            frozenRhrSigma: Float? = null,
            saturationZ: Float = ScoringConstants.HRV_SCORE_SATURATION_Z,
        ): Float {
            val zHrv =
                loadStrategy.computeHrvZScore(
                    currentHrvMean,
                    muHrvHistory,
                    sigmaHrvHistory,
                    sigmaPrior,
                    baselineOverride = hrvBaselineOverride,
                    frozenLnMu = frozenLnMu,
                    frozenLnSigma = frozenLnSigma,
                )
                    ?: 0f
            val hrvScore = loadStrategy.computeHrvScore(zHrv, saturationZ)

            val zRhr =
                loadStrategy.computeRhrZScore(
                    currentNocturnalRhr,
                    rhrValues,
                    rhrBaselineOverride,
                    frozenRhrSigma,
                ) ?: 0f
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
            fragmentation: SleepFragmentation? = null,
            weightProfile: SleepScoreWeightProfile = SleepScoreWeightProfile.DEFAULT,
            regularityScore: Float? = null,
            hypersomniaOnsetRatio: Float = Sleep.DEFAULT_HYPERSOMNIA_ONSET_RATIO,
        ): Float {
            require(durationMinutes >= 0) { "durationMinutes must be >= 0" }
            require(goalSleepHours > 0f) { "goalSleepHours must be > 0" }

            val sDur = computeDurationSubScore(durationMinutes, efficiency, goalSleepHours, hypersomniaOnsetRatio)
            val stageDataUsable = !stagesSuspicious && fragmentation != null

            val raw =
                if (stageDataUsable) {
                    val sArch = computeArchSubScore(deepSleepMinutes, remSleepMinutes, durationMinutes, userAge, sleepTargets)
                    val sFrag = computeFragmentationSubScore(fragmentation)
                    weightProfile.durationWeight * sDur +
                        weightProfile.architectureWeight * sArch +
                        weightProfile.restorationWeight * sRest +
                        weightProfile.fragmentationWeight * sFrag
                } else {
                    weightProfile.degradedDurationWeight * sDur + weightProfile.degradedRestorationWeight * sRest
                }

            return (raw * SleepContinuityCurves.regularityMultiplier(regularityScore)).coerceIn(0f, 100f)
        }
    }
