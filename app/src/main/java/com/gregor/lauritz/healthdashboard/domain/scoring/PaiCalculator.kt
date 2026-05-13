package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import kotlin.math.exp

object PaiCalculator {
    fun getDefaultPaiScalingFactor(profile: PhysiologyProfile): Float =
        when (profile) {
            PhysiologyProfile.ATHLETE -> 0.15f
            PhysiologyProfile.ACTIVE -> 0.18f
            PhysiologyProfile.GENERAL -> 0.20f
            PhysiologyProfile.SEDENTARY -> 0.25f
            PhysiologyProfile.SHIFT_WORKER -> 0.20f
        }

    /**
     * Phase III: Daily TRIMP Calculation using the selected training load model.
     * Banister is the default. Cheng (LT-TRIMP) and iTRIMP are fitness-adaptive alternatives.
     * All params after [gender] have defaults so existing call sites remain compatible.
     */
    fun calculateDailyTrimp(
        durationMinutes: Float,
        hrAvg: Float,
        rhrBaseline: Float,
        hrMax: Float,
        gender: String?,
        trimpModel: TrimpModel = TrimpModel.BANISTER,
        banisterMultiplier: Float = 1.0f,
        chengBeta: Float = 0.09f,
        itrimB: Float = 2.1f,
    ): Float {
        val hrr = hrMax - rhrBaseline
        if (hrr <= 0) return 0f

        val hrR = (hrAvg - rhrBaseline) / hrr
        if (hrAvg < (rhrBaseline + 5)) return 0f
        if (hrR <= 0) return 0f

        return when (trimpModel) {
            TrimpModel.BANISTER -> {
                val isMale = gender?.equals("Female", ignoreCase = true) != true
                val a = if (isMale) 0.64f else 0.86f
                val b = if (isMale) 1.92f else 1.67f
                durationMinutes * hrR * a * exp(b * hrR) * banisterMultiplier
            }
            TrimpModel.CHENG -> {
                // LT-TRIMP (Cheng): Piecewise logic with Lactate Threshold (LT) at 85% HRR
                // K1 = 0.006/sec (0.36/min), K2 = 0.012/sec (0.72/min)
                // REF: paiesque reference
                val ltThreshold = 0.85f
                val weight =
                    if (hrR < ltThreshold) {
                        0.36f // Linear weight below LT
                    } else {
                        0.72f * exp(chengBeta * hrR) // Exponential weight at/above LT
                    }
                durationMinutes * hrR * weight * 3.2f
            }
            TrimpModel.I_TRIMP -> {
                // iTRIMP: Exponential weighting instead of power law
                // REF: paiesque reference; Manzi et al. 2009
                durationMinutes * hrR * exp(itrimB * hrR) * 0.48f
            }
        }
    }

    /**
     * Phase IV: PAI Point Conversion & Scaling
     */
    fun calculateDailyPai(
        dailyTrimp: Float,
        scalingFactor: Float,
    ): Float {
        val paiD = dailyTrimp * scalingFactor
        return paiD.coerceAtMost(75f) // Daily Cap
    }

    /**
     * Phase IV.B: Non-Linear Accumulation (Logarithmic Decay)
     * Splits daily PAI across tier boundaries instead of applying a single multiplier.
     */
    fun applyAccumulationMultiplier(
        dailyPai: Float,
        totalPaiSoFar: Float,
    ): Float {
        if (dailyPai <= 0f) return 0f
        var remaining = dailyPai
        var accumulated = totalPaiSoFar
        var result = 0f
        // Tier 1: 0–50 → 1.0×
        if (accumulated < ScoringConstants.Pai.TIER1_THRESHOLD) {
            val used = remaining.coerceAtMost(ScoringConstants.Pai.TIER1_THRESHOLD - accumulated)
            result += used
            accumulated += used
            remaining -= used
        }
        // Tier 2: 50–100 → 0.5×
        if (remaining > 0f && accumulated < ScoringConstants.Pai.TIER2_THRESHOLD) {
            val used = remaining.coerceAtMost(ScoringConstants.Pai.TIER2_THRESHOLD - accumulated)
            result += used * ScoringConstants.Pai.TIER2_MULTIPLIER
            accumulated += used
            remaining -= used
        }
        // Tier 3: 100+ → 0.25×
        if (remaining > 0f) result += remaining * ScoringConstants.Pai.TIER3_MULTIPLIER
        return result
    }

    /**
     * Phase IV.C: Readiness Integration
     */
    fun adjustForReadiness(
        paiD: Float,
        readinessScore: Float?,
    ): Float {
        if (readinessScore == null) return paiD
        return paiD * (readinessScore / 100f)
    }
}
