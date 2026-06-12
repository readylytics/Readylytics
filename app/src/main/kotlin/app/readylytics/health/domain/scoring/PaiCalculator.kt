package app.readylytics.health.domain.scoring

import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import kotlin.math.exp

object PaiCalculator {
    fun getDefaultPaiScalingFactor(profile: PhysiologyProfile): Float =
        when (profile) {
            PhysiologyProfile.ATHLETE -> ScoringConstants.Pai.PAI_SCALING_ATHLETE
            PhysiologyProfile.ACTIVE -> ScoringConstants.Pai.PAI_SCALING_ACTIVE
            PhysiologyProfile.GENERAL -> ScoringConstants.Pai.PAI_SCALING_GENERAL
            PhysiologyProfile.SEDENTARY -> ScoringConstants.Pai.PAI_SCALING_SEDENTARY
            PhysiologyProfile.SHIFT_WORKER -> ScoringConstants.Pai.PAI_SCALING_SHIFT_WORKER
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
        gender: Gender?,
        trimpModel: TrimpModel = TrimpModel.BANISTER,
        banisterMultiplier: Float = 1.0f,
        chengBeta: Float = 0.09f,
        itrimB: Float = 2.1f,
        ltBpm: Float = 0f,
    ): Float {
        val hrr = hrMax - rhrBaseline
        if (hrr <= 0) return 0f

        val hrR = ((hrAvg - rhrBaseline) / hrr).coerceIn(0f, 1f)
        if (hrAvg < (rhrBaseline + 5)) return 0f
        if (hrR <= 0) return 0f

        return when (trimpModel) {
            TrimpModel.BANISTER -> {
                val isMale = gender != Gender.FEMALE
                val a = if (isMale) ScoringConstants.Trimp.BANISTER_MALE_A else ScoringConstants.Trimp.BANISTER_FEMALE_A
                val b = if (isMale) ScoringConstants.Trimp.BANISTER_MALE_B else ScoringConstants.Trimp.BANISTER_FEMALE_B
                durationMinutes * hrR * a * exp(b * hrR) * banisterMultiplier
            }
            TrimpModel.CHENG -> {
                // LT-TRIMP (Cheng 1992): piecewise on absolute HR vs lactate threshold (LT).
                // Continuous at HR=LT: both branches yield weight=0.5.
                // REF: Cheng et al. 1992; paiesque reference. LT from user HR zones; no fallback.
                if (ltBpm <= 0f) return 0f
                val isMale = gender != Gender.FEMALE
                val sexFactor =
                    if (isMale) {
                        ScoringConstants.Trimp.BANISTER_MALE_A
                    } else {
                        ScoringConstants.Trimp.BANISTER_FEMALE_A
                    }
                val weight =
                    if (hrAvg <= ltBpm) {
                        0.5f * (hrAvg - rhrBaseline) / (ltBpm - rhrBaseline).coerceAtLeast(1f)
                    } else {
                        val f = ((hrAvg - ltBpm) / (hrMax - ltBpm).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        0.5f + sexFactor * f * exp(chengBeta * f)
                    }
                durationMinutes * weight
            }
            TrimpModel.I_TRIMP -> {
                // iTRIMP (Manzi et al. 2009): exponential weighting. No PAI calibration factor.
                // REF: Manzi et al. 2009; paiesque reference
                durationMinutes * hrR * exp(itrimB * hrR)
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
        return paiD.coerceAtMost(ScoringConstants.Pai.DAILY_CAP)
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
        return paiD * (readinessScore / ScoringConstants.Pai.READINESS_SCALE)
    }
}
