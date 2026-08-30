package app.readylytics.health.core.model.domain.workouts

enum class FatigueCurveRange(val days: Int, val label: String) {
    ONE_DAY(1, "1D"),
    THREE_DAYS(3, "3D"),
    SEVEN_DAYS(7, "7D"),
}
