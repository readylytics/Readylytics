package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.preferences.Gender
import app.readylytics.health.domain.preferences.PhysiologyProfile
import kotlin.math.exp

object RasCalculator {
    fun getDefaultRasScalingFactor(profile: PhysiologyProfile): Float =
        when (profile) {
            PhysiologyProfile.ATHLETE -> ScoringConstants.Ras.RAS_SCALING_ATHLETE
            PhysiologyProfile.ACTIVE -> ScoringConstants.Ras.RAS_SCALING_ACTIVE
            PhysiologyProfile.SEDENTARY -> ScoringConstants.Ras.RAS_SCALING_SEDENTARY
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
                // REF: Cheng et al. 1992; rasesque reference. LT from user HR zones; no fallback.
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
                // iTRIMP (Manzi et al. 2009): exponential weighting. No RAS calibration factor.
                // REF: Manzi et al. 2009; rasesque reference
                durationMinutes * hrR * exp(itrimB * hrR)
            }
        }
    }

    /**
     * Phase IV: RAS Point Conversion & Scaling
     */
    fun calculateDailyRas(
        dailyTrimp: Float,
        rasScalingFactor: Float,
    ): Float {
        val rasD = dailyTrimp * rasScalingFactor
        return rasD.coerceAtMost(ScoringConstants.Ras.DAILY_CAP)
    }
}
