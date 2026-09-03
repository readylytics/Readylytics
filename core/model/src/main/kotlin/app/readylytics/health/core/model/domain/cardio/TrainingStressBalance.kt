package app.readylytics.health.core.model.domain.cardio

enum class TsbZone {
    VERY_FRESH_OR_TRANSITION,
    FRESH_PEAKED,
    OPTIMAL_PRODUCTIVE,
    FATIGUED_OVERLOAD,
    HIGH_RISK_OVERREACHED,
}

data class TrainingStressBalance(
    val value: Float,
    val zone: TsbZone,
)
