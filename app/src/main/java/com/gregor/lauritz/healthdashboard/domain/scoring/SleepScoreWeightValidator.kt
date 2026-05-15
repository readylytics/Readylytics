package com.gregor.lauritz.healthdashboard.domain.scoring

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Framework for validating and comparing alternative weight schemes for the sleep score.
 *
 * The current 0.50/0.25/0.25 split (duration/architecture/restoration) is rooted in
 * the PSQI clinical instrument (Buysse 1989); it has not been empirically optimised
 * against wearable-driven next-day readiness outcomes.
 *
 * This validator allows weight sets to be compared on a synthetic or real dataset
 * by correlating the resulting sleep score against a known next-day readiness label,
 * returning Pearson r and R² so the best-fit weights can be selected.
 *
 * Optional age-band adjustments (under-30 emphasises REM; 60+ emphasises sleep debt /
 * duration) are encoded here too — they remain off by default until validated.
 *
 * REF: Buysse 1989 PSQI; Ohayon 2004 Sleep 27:1255; Walch 2024 Sleep Advances on
 *      sleep-recovery prediction in wearables.
 */
@Singleton
class SleepScoreWeightValidator
    @Inject
    constructor() {
        /**
         * A complete weight set (must sum to 1.0 within tolerance).
         */
        data class WeightSet(
            val duration: Float,
            val architecture: Float,
            val restoration: Float,
            val version: Int,
        ) {
            init {
                require(abs(duration + architecture + restoration - 1f) < 0.001f) {
                    "Weights must sum to 1.0; got $duration + $architecture + $restoration"
                }
            }
        }

        data class SyntheticSample(
            val durationSubScore: Float,
            val architectureSubScore: Float,
            val restorationSubScore: Float,
            /** Known next-day readiness improvement label (0..100). */
            val readinessLabel: Float,
        )

        data class WeightEvaluation(
            val weightSet: WeightSet,
            val pearsonR: Float,
            val rSquared: Float,
            val meanAbsError: Float,
        )

        /**
         * Apply a weight set to a single sample, returning the predicted sleep score.
         */
        fun computeScore(
            sample: SyntheticSample,
            weights: WeightSet,
        ): Float =
            (
                weights.duration * sample.durationSubScore +
                    weights.architecture * sample.architectureSubScore +
                    weights.restoration * sample.restorationSubScore
            ).coerceIn(0f, 100f)

        /**
         * Evaluate a weight set against a labelled dataset.
         * Returns Pearson r, R², and mean-absolute error vs. the readiness label.
         */
        fun evaluate(
            dataset: List<SyntheticSample>,
            weights: WeightSet,
        ): WeightEvaluation {
            require(dataset.isNotEmpty()) { "Dataset must be non-empty" }
            val predicted = dataset.map { computeScore(it, weights) }
            val labels = dataset.map { it.readinessLabel }
            val r = pearson(predicted, labels)
            val mae = predicted.zip(labels) { p, l -> abs(p - l) }.average().toFloat()
            return WeightEvaluation(
                weightSet = weights,
                pearsonR = r,
                rSquared = r * r,
                meanAbsError = mae,
            )
        }

        /**
         * Compare candidate weight sets on the dataset; returns the highest-R² evaluation.
         */
        fun selectBest(
            dataset: List<SyntheticSample>,
            candidates: List<WeightSet>,
        ): WeightEvaluation {
            require(candidates.isNotEmpty())
            return candidates
                .map { evaluate(dataset, it) }
                .maxBy { it.rSquared }
        }

        /**
         * Suggested age-banded adjustment. Returns the user's effective weight set.
         * When `useAgeAdjustment = false`, returns [DEFAULT_WEIGHTS] unchanged.
         */
        fun weightsForAge(
            age: Int,
            useAgeAdjustment: Boolean = false,
        ): WeightSet {
            if (!useAgeAdjustment) return DEFAULT_WEIGHTS
            return when {
                // Younger users — REM is more diagnostic of recovery
                age < 30 -> WeightSet(duration = 0.40f, architecture = 0.35f, restoration = 0.25f, version = 2)
                // Older users — duration / sleep debt dominate
                age >= 60 -> WeightSet(duration = 0.55f, architecture = 0.20f, restoration = 0.25f, version = 2)
                else -> DEFAULT_WEIGHTS
            }
        }

        private fun pearson(
            xs: List<Float>,
            ys: List<Float>,
        ): Float {
            require(xs.size == ys.size)
            val n = xs.size
            if (n < 2) return 0f
            val mx = xs.average()
            val my = ys.average()
            var num = 0.0
            var dx2 = 0.0
            var dy2 = 0.0
            for (i in 0 until n) {
                val dx = xs[i] - mx
                val dy = ys[i] - my
                num += dx * dy
                dx2 += dx * dx
                dy2 += dy * dy
            }
            val den = sqrt(dx2 * dy2)
            if (den == 0.0) return 0f
            return (num / den).toFloat().coerceIn(-1f, 1f)
        }

        companion object {
            /** Current canonical weights (version 1). REF: ScoringConstants.Sleep. */
            val DEFAULT_WEIGHTS = WeightSet(duration = 0.50f, architecture = 0.25f, restoration = 0.25f, version = 1)

            /** Equal-thirds candidate. */
            val EQUAL_WEIGHTS = WeightSet(duration = 0.34f, architecture = 0.33f, restoration = 0.33f, version = 100)

            /** Duration-light candidate. */
            val DURATION_LIGHT = WeightSet(duration = 0.40f, architecture = 0.30f, restoration = 0.30f, version = 101)

            /** Restoration-heavy candidate. */
            val RESTORATION_HEAVY =
                WeightSet(duration = 0.30f, architecture = 0.35f, restoration = 0.35f, version = 102)

            /** Standard candidate set used by selectBest() in tests. */
            val STANDARD_CANDIDATES: List<WeightSet> =
                listOf(DEFAULT_WEIGHTS, EQUAL_WEIGHTS, DURATION_LIGHT, RESTORATION_HEAVY)
        }
    }
