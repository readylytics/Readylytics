package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import com.gregor.lauritz.healthdashboard.domain.scoring.components.EmergencyFlagThresholds
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Validates recovery flags and assigns confidence scores.
 *
 * The default thresholds for OVERREACHING (zHrv>1.5 & zRhr<-2.0) and
 * ILLNESS_ONSET (zHrv<-1.5 & zRhr>2.0) come from small specialised cohorts:
 *   - Le Meur et al. 2013 (overreaching, n=16 elite endurance athletes)
 *   - Mishra et al. 2020 (illness signal, n=18 COVID-positive subjects)
 *
 * These populations are not representative of general app users; this validator
 * exposes sensitivity / specificity targets so the flag can be tuned and tracked.
 *
 * Targets:
 *   - OVERREACHING: 80% specificity, 70% sensitivity (avoid false alarms)
 *   - ILLNESS:     85% specificity, 60% sensitivity (very conservative)
 *
 * Confidence is derived from the distance the z-scores penetrate past their thresholds
 * and from the consecutive-day count. Two consecutive nights past the threshold yields
 * the highest confidence.
 *
 * REF: Le Meur 2013 Med Sci Sports Exerc; Mishra 2020 Nat Biomed Eng;
 *      Bellenger 2017 Front Physiol; Quer 2021 Nat Med.
 */
@Singleton
class RecoveryFlagValidator
    @Inject
    constructor() {
        data class FlagAssessment(
            val flag: RecoveryFlag,
            /** 0..1: confidence the flag is a true positive. */
            val confidence: Float,
            /** Number of consecutive nights the trigger condition has held. */
            val consecutiveDays: Int,
            /** Human-readable rationale for the assessment (for audit / UI). */
            val rationale: String,
        )

        /**
         * Sensitivity / specificity targets per flag.
         * These are population-level targets, not measured at runtime.
         */
        data class FlagPerformanceTargets(
            val sensitivity: Float,
            val specificity: Float,
        )

        /**
         * Tracks whether a flag was set yesterday but the user reported feeling well today
         * (or recovered without intervention). Used for false-positive monitoring.
         */
        data class FalsePositiveEvent(
            val flag: RecoveryFlag,
            val flagSetTimestampMs: Long,
            val recoveredWithinDays: Int,
        )

        /**
         * Compute confidence for an OVERREACHING signal given today + yesterday z-scores.
         * Returns null if the flag is not currently triggered.
         */
        fun assessOverreaching(
            zLnHrvToday: Float?,
            zRhrToday: Float?,
            zLnHrvYesterday: Float?,
            zRhrYesterday: Float?,
            consecutiveDays: Int,
            thresholds: EmergencyFlagThresholds = EmergencyFlagThresholds(),
        ): FlagAssessment? {
            if (zLnHrvToday == null || zRhrToday == null) return null
            val todayTriggered =
                zLnHrvToday > thresholds.overreachingZHrvThreshold &&
                    zRhrToday < thresholds.overreachingZRhrThreshold
            val prevTriggered =
                zLnHrvYesterday != null && zRhrYesterday != null &&
                    zLnHrvYesterday > thresholds.overreachingZHrvThreshold &&
                    zRhrYesterday < thresholds.overreachingZRhrThreshold
            if (!todayTriggered || !prevTriggered) return null

            // Confidence scales with penetration depth past thresholds.
            val hrvPenetration =
                (zLnHrvToday - thresholds.overreachingZHrvThreshold).coerceAtLeast(0f) / MAX_HRV_PENETRATION
            val rhrPenetration =
                (thresholds.overreachingZRhrThreshold - zRhrToday).coerceAtLeast(0f) / MAX_RHR_PENETRATION
            val penetrationConfidence = ((hrvPenetration + rhrPenetration) / 2f).coerceIn(0f, 1f)
            val daysConfidence =
                ((consecutiveDays - 1).coerceAtLeast(0).toFloat() / MAX_CONSECUTIVE_DAYS_FOR_CONFIDENCE)
                    .coerceIn(0f, 1f)
            // Blend: penetration carries 60%, consecutive-day evidence 40%.
            val confidence = (BLEND_PENETRATION * penetrationConfidence + BLEND_DAYS * daysConfidence)
                .coerceIn(0f, 1f)
            return FlagAssessment(
                flag = RecoveryFlag.OVERREACHING,
                confidence = confidence,
                consecutiveDays = consecutiveDays,
                rationale =
                    "zHRV=$zLnHrvToday (>${thresholds.overreachingZHrvThreshold}), " +
                        "zRHR=$zRhrToday (<${thresholds.overreachingZRhrThreshold}) " +
                        "for $consecutiveDays consecutive nights",
            )
        }

        /**
         * Compute confidence for an ILLNESS_ONSET signal given today + yesterday z-scores
         * (and optionally absolute RHR delta).
         */
        fun assessIllness(
            zLnHrvToday: Float?,
            zRhrToday: Float?,
            zLnHrvYesterday: Float?,
            zRhrYesterday: Float?,
            rhrDeltaBpm: Float?,
            consecutiveDays: Int,
            thresholds: EmergencyFlagThresholds = EmergencyFlagThresholds(),
        ): FlagAssessment? {
            if (zLnHrvToday == null || zRhrToday == null) return null
            val todayTriggered =
                zLnHrvToday < thresholds.illnessZHrvThreshold &&
                    (
                        (rhrDeltaBpm != null && rhrDeltaBpm >= thresholds.illnessRhrDeltaBpm) ||
                            zRhrToday >= thresholds.illnessZRhrThreshold
                    )
            val prevTriggered =
                zLnHrvYesterday != null && zRhrYesterday != null &&
                    zLnHrvYesterday < thresholds.illnessZHrvThreshold &&
                    zRhrYesterday >= thresholds.illnessZRhrThreshold
            if (!todayTriggered || !prevTriggered) return null

            val hrvPenetration =
                (thresholds.illnessZHrvThreshold - zLnHrvToday).coerceAtLeast(0f) / MAX_HRV_PENETRATION
            val rhrPenetration =
                (zRhrToday - thresholds.illnessZRhrThreshold).coerceAtLeast(0f) / MAX_RHR_PENETRATION
            val absDeltaContribution = (rhrDeltaBpm ?: 0f).let { abs(it) / MAX_RHR_DELTA_BPM }
            val penetrationConfidence =
                ((hrvPenetration + rhrPenetration + absDeltaContribution) / 3f).coerceIn(0f, 1f)
            val daysConfidence =
                ((consecutiveDays - 1).coerceAtLeast(0).toFloat() / MAX_CONSECUTIVE_DAYS_FOR_CONFIDENCE)
                    .coerceIn(0f, 1f)
            val confidence = (BLEND_PENETRATION * penetrationConfidence + BLEND_DAYS * daysConfidence)
                .coerceIn(0f, 1f)
            return FlagAssessment(
                flag = RecoveryFlag.ILLNESS_ONSET,
                confidence = confidence,
                consecutiveDays = consecutiveDays,
                rationale =
                    "zHRV=$zLnHrvToday (<${thresholds.illnessZHrvThreshold}), " +
                        "zRHR=$zRhrToday (>=${thresholds.illnessZRhrThreshold}), " +
                        "rhrDelta=${rhrDeltaBpm ?: 0f} bpm " +
                        "for $consecutiveDays consecutive nights",
            )
        }

        /**
         * Performance targets per flag — used for documentation, A/B testing, and
         * future cohort-validation work. NOT enforced at runtime.
         */
        fun targetsFor(flag: RecoveryFlag): FlagPerformanceTargets? =
            when (flag) {
                RecoveryFlag.OVERREACHING ->
                    FlagPerformanceTargets(sensitivity = 0.70f, specificity = 0.80f)
                RecoveryFlag.ILLNESS_ONSET ->
                    FlagPerformanceTargets(sensitivity = 0.60f, specificity = 0.85f)
                else -> null
            }

        companion object {
            // Penetration normalisers (z-score units past the threshold to reach max confidence).
            const val MAX_HRV_PENETRATION = 1.5f
            const val MAX_RHR_PENETRATION = 1.5f
            const val MAX_RHR_DELTA_BPM = 10f
            const val MAX_CONSECUTIVE_DAYS_FOR_CONFIDENCE = 5f

            const val BLEND_PENETRATION = 0.6f
            const val BLEND_DAYS = 0.4f
        }
    }
