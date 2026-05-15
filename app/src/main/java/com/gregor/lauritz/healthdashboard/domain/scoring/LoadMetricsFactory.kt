package com.gregor.lauritz.healthdashboard.domain.scoring

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the most appropriate load-metric source given the data available for a given day.
 *
 * Recent literature (Carey 2016 JSAMS; Robinson 2017 BJSM; Impellizzeri 2020 IJSPP) has questioned
 * the validity of ACWR (Acute:Chronic Workload Ratio) as a single predictor of injury or readiness.
 * This factory introduces graceful fallbacks so the load score remains meaningful when:
 *   - workout data is sparse or absent
 *   - chronic baseline (CTL) is not yet established
 *   - the user is in a rest / recovery block where ACWR is noisy or undefined
 *
 * Confidence ratings (informational only; do not gate scoring):
 *   - RATIO_ACWR        : moderate (questioned in recent reviews; still widely used)
 *   - MONOTONICITY      : low-moderate (population-level evidence only)
 *   - ABSOLUTE          : low (single-day TRIMP is highly variable)
 *   - SLEEP_ONLY        : low (sleep + HRV proxy when no workout data exists)
 *
 * REF: Carey 2016, Robinson 2017, Gabbett 2016, Windt & Gabbett 2018,
 *      Impellizzeri 2020 IJSPP, Foster 1998 (monotony index).
 */
@Singleton
class LoadMetricsFactory
    @Inject
    constructor() {
        /**
         * Identifies which metric source should drive the load score for a given day.
         * Persistable as DailySummaryEntity.loadMetricSource.
         */
        enum class LoadMetricSource {
            /** Standard ACWR = ATL_7d / CTL_42d. Used when workouts exist and CTL is established. */
            RATIO_ACWR,

            /** Acute > chronic for 3+ consecutive days; flags monotony / high load risk. */
            MONOTONICITY,

            /** Absolute single-day TRIMP only; used when chronic baseline missing. */
            ABSOLUTE,

            /** No workout data at all; load falls back to sleep + HRV signals only. */
            SLEEP_ONLY,
        }

        data class LoadMetricResult(
            val source: LoadMetricSource,
            val score: Float,
            /** Informational confidence in the score (0..1). */
            val confidence: Float,
            /** Optional secondary diagnostics for logging / audit. */
            val notes: String? = null,
        )

        /**
         * Selects the appropriate load metric source given inputs.
         *
         * @param acuteTrimp     7d EMA of TRIMP (ATL)
         * @param chronicTrimp   42d EMA of TRIMP (CTL)
         * @param recentDailyTrimp ordered list of daily TRIMP values for the last 7 days
         *                       (most recent last); used for monotonicity detection.
         * @param hasChronicBaseline   true when there are ≥ MIN_DAYS_FOR_CHRONIC_BASELINE
         *                             days of training history.
         * @param hasAnyWorkoutData    true if any workout exists in the look-back window.
         */
        fun selectSource(
            acuteTrimp: Float,
            chronicTrimp: Float,
            recentDailyTrimp: List<Float>,
            hasChronicBaseline: Boolean,
            hasAnyWorkoutData: Boolean,
        ): LoadMetricSource {
            if (!hasAnyWorkoutData) return LoadMetricSource.SLEEP_ONLY
            if (!hasChronicBaseline) {
                // Without CTL, use absolute TRIMP only (avoid divide-by-zero artefacts).
                return LoadMetricSource.ABSOLUTE
            }
            // Detect 3+ consecutive days of elevated acute load → monotonicity flag.
            val monotonicHighLoad = detectMonotonicity(recentDailyTrimp, chronicTrimp)
            if (monotonicHighLoad) return LoadMetricSource.MONOTONICITY

            return LoadMetricSource.RATIO_ACWR
        }

        /**
         * Computes the load score for a single day given the inputs and a selected source.
         * Returns score on the standard 0..100 scale alongside source + confidence metadata.
         */
        fun compute(
            acuteTrimp: Float,
            chronicTrimp: Float,
            recentDailyTrimp: List<Float>,
            hasChronicBaseline: Boolean,
            hasAnyWorkoutData: Boolean,
            sleepScore: Float? = null,
            hrvScore: Float? = null,
            scoringCalculator: ScoringCalculator,
        ): LoadMetricResult {
            val source =
                selectSource(
                    acuteTrimp = acuteTrimp,
                    chronicTrimp = chronicTrimp,
                    recentDailyTrimp = recentDailyTrimp,
                    hasChronicBaseline = hasChronicBaseline,
                    hasAnyWorkoutData = hasAnyWorkoutData,
                )

            return when (source) {
                LoadMetricSource.RATIO_ACWR -> {
                    val sr = scoringCalculator.computeStrainRatio(acuteTrimp, chronicTrimp)
                    val score = scoringCalculator.computeLoadScore(sr)
                    LoadMetricResult(
                        source = source,
                        score = score,
                        confidence = CONFIDENCE_RATIO_ACWR,
                        notes = "ACWR=$sr",
                    )
                }
                LoadMetricSource.MONOTONICITY -> {
                    // 3+ consecutive high-load days → penalise; otherwise sweet-spot.
                    val score = SCORE_MONOTONIC_HIGH_LOAD
                    LoadMetricResult(
                        source = source,
                        score = score,
                        confidence = CONFIDENCE_MONOTONICITY,
                        notes = "3+ days acute > chronic",
                    )
                }
                LoadMetricSource.ABSOLUTE -> {
                    // Absolute single-day TRIMP: low TRIMP → rest day; high → likely fatigue.
                    val today = recentDailyTrimp.lastOrNull() ?: 0f
                    val score =
                        when {
                            today < ABSOLUTE_LOW_TRIMP -> SCORE_ABSOLUTE_REST_DAY
                            today < ABSOLUTE_HIGH_TRIMP -> SCORE_ABSOLUTE_MODERATE
                            else -> SCORE_ABSOLUTE_HIGH_LOAD
                        }
                    LoadMetricResult(
                        source = source,
                        score = score,
                        confidence = CONFIDENCE_ABSOLUTE,
                        notes = "TRIMP=$today",
                    )
                }
                LoadMetricSource.SLEEP_ONLY -> {
                    // Fall back to sleep + HRV midpoint (50 default if either missing).
                    val s = sleepScore ?: DEFAULT_NEUTRAL_SCORE
                    val h = hrvScore ?: DEFAULT_NEUTRAL_SCORE
                    LoadMetricResult(
                        source = source,
                        score = ((s + h) / 2f).coerceIn(0f, 100f),
                        confidence = CONFIDENCE_SLEEP_ONLY,
                        notes = "Sleep+HRV fallback",
                    )
                }
            }
        }

        /**
         * Validation hook: log a warning if ACWR contradicts actual session data.
         * Specifically: SR signals "high load" but the day had no recorded workout.
         */
        fun validateAcwrConsistency(
            strainRatio: Float,
            todayTrimp: Float,
        ): String? {
            return when {
                strainRatio > 1.5f && todayTrimp <= 0f ->
                    "ACWR=$strainRatio suggests high load but no workout recorded today (TRIMP=0)"
                strainRatio < 0.6f && todayTrimp > 100f ->
                    "ACWR=$strainRatio suggests low load but TRIMP=$todayTrimp recorded today"
                else -> null
            }
        }

        private fun detectMonotonicity(
            recentDailyTrimp: List<Float>,
            chronicTrimp: Float,
        ): Boolean {
            if (recentDailyTrimp.size < MIN_DAYS_FOR_MONOTONICITY) return false
            if (chronicTrimp <= 0f) return false
            val tail = recentDailyTrimp.takeLast(MIN_DAYS_FOR_MONOTONICITY)
            return tail.all { it > chronicTrimp }
        }

        companion object {
            const val MIN_DAYS_FOR_CHRONIC_BASELINE = 14
            const val MIN_DAYS_FOR_MONOTONICITY = 3

            // Confidence map per source (informational only).
            const val CONFIDENCE_RATIO_ACWR = 0.7f
            const val CONFIDENCE_MONOTONICITY = 0.6f
            const val CONFIDENCE_ABSOLUTE = 0.5f
            const val CONFIDENCE_SLEEP_ONLY = 0.4f

            // Absolute TRIMP thresholds (Banister scale; ~typical user).
            const val ABSOLUTE_LOW_TRIMP = 20f // < 20 → rest day
            const val ABSOLUTE_HIGH_TRIMP = 150f // > 150 → high load day
            const val SCORE_ABSOLUTE_REST_DAY = 100f
            const val SCORE_ABSOLUTE_MODERATE = 80f
            const val SCORE_ABSOLUTE_HIGH_LOAD = 55f

            const val SCORE_MONOTONIC_HIGH_LOAD = 50f
            const val DEFAULT_NEUTRAL_SCORE = 50f
        }
    }
