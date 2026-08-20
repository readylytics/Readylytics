package app.readylytics.health.core.model.domain.workouts.detail

/**
 * Every customizable element of the workout detail screen. The screen header is
 * deliberately absent — it is pinned and not customizable.
 *
 * [AVG_PACE_SPEED] and [PACE_SPEED_CHART] are single items: they already switch
 * between pace and speed presentation based on the activity.
 */
enum class WorkoutDetailItemId {
    TRAINING_LOAD,
    AVG_PULSE,
    GAINED_STRAIN,
    RAS,
    OVERALL_LOAD,
    INTENSITY,
    DISTANCE,
    AVG_PACE_SPEED,
    ELEVATION_GAIN,
    ZONE_BREAKDOWN,
    ROUTE_CONTOUR,
    PACE_SPEED_CHART,
    ELEVATION_CHART,
    TRIMP_BREAKDOWN,
    RECOVERY_HRR,
}
