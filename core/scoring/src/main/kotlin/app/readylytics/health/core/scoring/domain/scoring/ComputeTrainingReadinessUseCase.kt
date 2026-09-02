package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

data class TrainingReadinessProjection(
    val acuteLoadRecovery: Float?,
    val trainingLoadReadiness: Float?,
    val trainingReadiness: Float?,
)

/**
 * Combines residual fatigue with the existing readiness calculation without changing legacy score
 * semantics when fatigue is unavailable or the load-balance weight is fully legacy.
 */
@Singleton
class ComputeTrainingReadinessUseCase
    @Inject
    constructor(
        private val scoringCalculator: ScoringCalculator,
    ) {
        fun compute(
            restoration: Float?,
            sleepScore: Float?,
            loadScore: Float?,
            legacyReadiness: Float?,
            residualFatigue: Float?,
            recoveryFlags: Set<RecoveryFlag>,
            config: TrainingReadinessConfig,
        ): TrainingReadinessProjection {
            val acuteLoadRecovery = residualFatigue?.takeIf(Float::isFinite)?.let { fatigue ->
                (100.0 * exp((-fatigue / config.residualFatigueScale).toDouble()))
                    .toFloat()
                    .coerceIn(0f, 100f)
            }
            val trainingLoadReadiness =
                loadScore?.let { load ->
                    acuteLoadRecovery
                        ?.let { acute -> config.loadBalanceWeight * load + (1f - config.loadBalanceWeight) * acute }
                        ?.coerceIn(0f, 100f) ?: load
                }
            val trainingReadiness =
                when {
                    legacyReadiness == null -> null
                    config.loadBalanceWeight == 1f -> legacyReadiness
                    acuteLoadRecovery == null -> legacyReadiness
                    restoration == null || sleepScore == null || trainingLoadReadiness == null -> null
                    else ->
                        scoringCalculator.computeReadinessScore(
                            restoration,
                            sleepScore,
                            trainingLoadReadiness,
                            recoveryFlags,
                        )
                }

            return TrainingReadinessProjection(
                acuteLoadRecovery = acuteLoadRecovery,
                trainingLoadReadiness = trainingLoadReadiness,
                trainingReadiness = trainingReadiness,
            )
        }
    }
