package app.readylytics.health.feature.workouts

/**
 * Which series the training-load chart card (Workouts tab) currently displays.
 *
 * Kept as its own tiny file rather than folded into [WorkoutsStateFactory] / [WorkoutsUiState]
 * since [WorkoutsStateFactory.kt] is already above the 400-line soft target.
 */
enum class TrainingLoadMetric {
    ACWR,
    TSB,
}
