package app.readylytics.health.core.scoring.domain.scoring.components

import app.readylytics.health.core.scoring.domain.scoring.components.SleepContinuityCurves

import app.readylytics.health.core.model.domain.scoring.ScoringConstants

import app.readylytics.health.core.model.domain.scoring.ScoringConstants.Sleep
import kotlin.math.exp

/**
 * Continuous sleep-score curves. Pure functions, no state: every sub-score input maps smoothly
 * to 0..100 so that a small change in an input can never produce a step in the output.
 */
object SleepContinuityCurves {
    private val DURATION_NORMALIZER =
        sigmoid(Sleep.DURATION_LOGISTIC_SLOPE * (1f - Sleep.DURATION_LOGISTIC_MIDPOINT))

    private val EFFICIENCY_NORMALIZER =
        sigmoid(Sleep.EFFICIENCY_SLOPE * (100f - Sleep.EFFICIENCY_MIDPOINT))

    fun efficiencyTerm(efficiency: Float): Float =
        (
            100f * sigmoid(Sleep.EFFICIENCY_SLOPE * (efficiency.coerceIn(0f, 100f) - Sleep.EFFICIENCY_MIDPOINT)) /
                EFFICIENCY_NORMALIZER
        ).coerceIn(0f, 100f)

    fun durationTerm(
        ratio: Float,
        hypersomniaOnsetRatio: Float,
    ): Float {
        val safeRatio = ratio.coerceAtLeast(0f)
        val onset = hypersomniaOnsetRatio.coerceAtLeast(1f)
        return when {
            safeRatio <= 1f ->
                (
                    100f *
                        sigmoid(Sleep.DURATION_LOGISTIC_SLOPE * (safeRatio - Sleep.DURATION_LOGISTIC_MIDPOINT)) /
                        DURATION_NORMALIZER
                ).coerceIn(0f, 100f)
            safeRatio <= onset -> 100f
            else -> {
                val excess = safeRatio - onset
                100f * exp(-(excess * excess) / (2f * Sleep.HYPERSOMNIA_SIGMA * Sleep.HYPERSOMNIA_SIGMA))
            }
        }
    }

    fun fragmentationTerm(
        wasoMinutes: Float,
        awakeningCount: Int,
    ): Float {
        val wasoExcess = (wasoMinutes - Sleep.WASO_GRACE_MINUTES).coerceAtLeast(0f)
        val countExcess = (awakeningCount - Sleep.AWAKENING_GRACE_COUNT).coerceAtLeast(0)
        val decay = Sleep.WASO_DECAY_PER_MINUTE * wasoExcess + Sleep.AWAKENING_DECAY_PER_EVENT * countExcess
        return 100f * exp(-decay)
    }

    fun regularityMultiplier(regularityScore: Float?): Float {
        if (regularityScore == null) return 1f
        val normalized = regularityScore.coerceIn(0f, 100f) / 100f
        return (Sleep.REGULARITY_FLOOR + Sleep.REGULARITY_SPAN * normalized)
            .coerceIn(Sleep.REGULARITY_FLOOR, 1f)
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))
}
