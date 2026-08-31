package app.readylytics.health.core.model.domain.workouts.detail

/**
 * Layout metadata for workout detail items. Items listed here render full-width;
 * everything else is a half-width metric tile that pairs two-per-row in
 * `ReorderableGrid`.
 */
object WorkoutDetailItemCatalog {
    val FULL_WIDTH_ITEMS: Set<WorkoutDetailItemId> =
        setOf(
            WorkoutDetailItemId.ZONE_BREAKDOWN,
            WorkoutDetailItemId.ROUTE_CONTOUR,
            WorkoutDetailItemId.PACE_SPEED_CHART,
            WorkoutDetailItemId.ELEVATION_CHART,
            WorkoutDetailItemId.TRIMP_BREAKDOWN,
            WorkoutDetailItemId.RECOVERY_HRR,
        )
}
