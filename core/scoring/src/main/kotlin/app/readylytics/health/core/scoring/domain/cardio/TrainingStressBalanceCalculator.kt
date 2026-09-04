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
            tsb > 25.0f -> TsbZone.VERY_FRESH_OR_TRANSITION
            tsb >= 5.0f -> TsbZone.FRESH_PEAKED
            tsb >= -10.0f -> TsbZone.OPTIMAL_PRODUCTIVE
            tsb >= -30.0f -> TsbZone.FATIGUED_OVERLOAD
            else -> TsbZone.HIGH_RISK_OVERREACHED
        }
        return TrainingStressBalance(value = tsb, zone = zone)
    }
}
