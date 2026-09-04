package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.cardio.TrainingStressBalance
import app.readylytics.health.core.model.domain.cardio.TsbZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrainingStressBalanceCalculator @Inject constructor() {
    fun calculate(ctl: Float?, atl: Float?): TrainingStressBalance? {
        if (ctl == null || atl == null) return null
        val tsb = ctl - atl
        val zone = when {
            tsb > VERY_FRESH_THRESHOLD -> TsbZone.VERY_FRESH_OR_TRANSITION
            tsb >= FRESH_THRESHOLD -> TsbZone.FRESH_PEAKED
            tsb >= FATIGUED_THRESHOLD -> TsbZone.OPTIMAL_PRODUCTIVE
            tsb >= HIGH_RISK_THRESHOLD -> TsbZone.FATIGUED_OVERLOAD
            else -> TsbZone.HIGH_RISK_OVERREACHED
        }
        return TrainingStressBalance(value = tsb, zone = zone)
    }

    companion object {
        const val VERY_FRESH_THRESHOLD = 25.0f
        const val FRESH_THRESHOLD = 5.0f
        const val FATIGUED_THRESHOLD = -10.0f
        const val HIGH_RISK_THRESHOLD = -30.0f
    }
}
