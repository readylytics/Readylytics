package app.readylytics.health.core.scoring.domain.cardio

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UthVo2MaxCalculator @Inject constructor() {
    fun estimate(hrMax: Float, rhrBaselineBpm: Float, isCalibrating: Boolean): Float? {
        if (isCalibrating || hrMax < MIN_PLAUSIBLE_HR_MAX || rhrBaselineBpm < MIN_PLAUSIBLE_RHR) {
            return null
        }
        val raw = UTH_COEFFICIENT * (hrMax / rhrBaselineBpm)
        return raw.coerceIn(PHYSIOLOGICAL_MIN_VO2, PHYSIOLOGICAL_MAX_VO2)
    }

    companion object {
        const val UTH_COEFFICIENT = 15.3f
        const val MIN_PLAUSIBLE_HR_MAX = 90f
        const val MIN_PLAUSIBLE_RHR = 30f
        const val PHYSIOLOGICAL_MIN_VO2 = 15.0f
        const val PHYSIOLOGICAL_MAX_VO2 = 95.0f
    }
}
