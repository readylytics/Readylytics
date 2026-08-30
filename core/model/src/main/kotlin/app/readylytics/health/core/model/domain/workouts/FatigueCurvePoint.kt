package app.readylytics.health.core.model.domain.workouts

data class FatigueCurvePoint(
    val timestampMs: Long,
    val timeMinutesOfDay: Float,
    val fatigueValue: Float,
)
