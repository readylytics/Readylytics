package app.readylytics.health.core.model.domain.workouts.detail

/**
 * Bounded grouping of Health Connect exercise types used as the key for
 * per-type workout detail layouts. Anything unrecognised groups under [OTHER].
 */
enum class WorkoutLayoutType {
    RUNNING,
    WALKING,
    CYCLING,
    SWIMMING,
    STRENGTH,
    HIKING,
    YOGA,
    PILATES,
    ELLIPTICAL,
    ROWING,
    STAIRS,
    HIIT,
    OTHER,
}
