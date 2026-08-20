package app.readylytics.health.core.model.domain.model

data class WorkoutRoutePoint(
    val id: Long = 0,
    val workoutId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val timestampMs: Long,
    val horizontalAccuracy: Float? = null,
    val verticalAccuracy: Float? = null,
)
