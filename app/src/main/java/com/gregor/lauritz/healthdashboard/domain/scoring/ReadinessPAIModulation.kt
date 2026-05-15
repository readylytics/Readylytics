package com.gregor.lauritz.healthdashboard.domain.scoring

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decouples PAI accumulation from the readiness score and replaces the previous
 * multiplicative coupling (PaiCalculator.adjustForReadiness) with a cap-based
 * modulation strategy.
 *
 * **Problem with the previous approach:**
 *   daily PAI = paiD × (readiness / 100)
 * Because readiness depends on load score, and PAI feeds back into load via training
 * stimulus tracking, the two metrics oscillate over consecutive days. Capping PAI
 * instead of scaling it breaks the feedback loop while still nudging the user
 * toward recovery when readiness is low.
 *
 * Modulation table:
 *   readiness < 40   → daily PAI cap 50  (recovery priority)
 *   readiness 40-59  → daily PAI cap 60
 *   readiness 60-69  → daily PAI cap 70
 *   readiness ≥ 70   → daily PAI cap 75  (normal)
 *   readiness null   → daily PAI cap 75  (no signal → no penalty)
 *
 * The user may override the recommendation; the override flag should be persisted
 * on DailySummaryEntity.userOverrideReadiness for a future migration.
 *
 * REF: Internal review §1.5; WHOOP PAI model (proprietary); see MEDICAL_ALGORITHMS.md.
 */
@Singleton
class ReadinessPAIModulation
    @Inject
    constructor() {
        data class ModulationResult(
            /** Adjusted daily PAI cap. */
            val dailyCap: Float,
            /** Original (non-modulated) cap. */
            val baseCap: Float,
            /** Multiplicative adjustment applied to the base cap (informational). */
            val adjustment: Float,
            /** Human-readable explanation for UI. */
            val message: String,
        )

        /**
         * Compute the effective daily PAI cap given the current readiness score.
         *
         * @param readinessScore current readiness on 0..100 scale; null when missing
         * @param userOverride when true, the user signalled they feel fine despite low
         *                     readiness — return the base cap unmodulated.
         */
        fun computeDailyCap(
            readinessScore: Float?,
            userOverride: Boolean = false,
            baseCap: Float = ScoringConstants.Pai.DAILY_CAP,
        ): ModulationResult {
            if (userOverride || readinessScore == null) {
                return ModulationResult(
                    dailyCap = baseCap,
                    baseCap = baseCap,
                    adjustment = 1f,
                    message =
                        if (userOverride) {
                            "User override active — using normal PAI target ($baseCap)."
                        } else {
                            "Readiness not yet available — using normal PAI target ($baseCap)."
                        },
                )
            }

            val (cap, msg) =
                when {
                    readinessScore < THRESHOLD_LOW ->
                        CAP_LOW to recoveryMessage(readinessScore, CAP_LOW, baseCap, "low")
                    readinessScore < THRESHOLD_MID ->
                        CAP_MID to recoveryMessage(readinessScore, CAP_MID, baseCap, "below baseline")
                    readinessScore < THRESHOLD_HIGH ->
                        CAP_NEAR_BASELINE to recoveryMessage(readinessScore, CAP_NEAR_BASELINE, baseCap, "moderate")
                    else ->
                        baseCap to "Readiness ${readinessScore.toInt()} — normal PAI target ($baseCap)."
                }

            val effectiveCap = cap.coerceAtMost(baseCap)
            val adjustment = if (baseCap > 0f) effectiveCap / baseCap else 1f
            return ModulationResult(
                dailyCap = effectiveCap,
                baseCap = baseCap,
                adjustment = adjustment,
                message = msg,
            )
        }

        /**
         * Apply the modulated cap to a daily PAI value. This is the recommended
         * replacement for PaiCalculator.adjustForReadiness().
         */
        fun applyCap(
            dailyPai: Float,
            readinessScore: Float?,
            userOverride: Boolean = false,
            baseCap: Float = ScoringConstants.Pai.DAILY_CAP,
        ): Float {
            val result = computeDailyCap(readinessScore, userOverride, baseCap)
            return dailyPai.coerceAtMost(result.dailyCap)
        }

        private fun recoveryMessage(
            readiness: Float,
            cap: Float,
            baseCap: Float,
            band: String,
        ): String =
            "Your readiness is $band (${readiness.toInt()}). Today's PAI target: ${cap.toInt()} " +
                "(was ${baseCap.toInt()}). Focus on recovery."

        companion object {
            const val THRESHOLD_LOW = 40f
            const val THRESHOLD_MID = 60f
            const val THRESHOLD_HIGH = 70f

            const val CAP_LOW = 50f
            const val CAP_MID = 60f
            const val CAP_NEAR_BASELINE = 70f
        }
    }
