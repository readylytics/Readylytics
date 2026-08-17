package app.readylytics.health.feature.workouts

import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemId

/**
 * Minimal projection of [WorkoutDetailUiState] needed to decide which detail items have
 * data. Kept separate from the ui state so the rules are unit-testable without building a
 * full `WorkoutData`.
 */
data class WorkoutDetailAvailabilityInput(
    val hasDistance: Boolean,
    val hasSpeed: Boolean,
    val hasElevationGain: Boolean,
    val routeState: RouteDataState,
    val hasPaceSpeedChartData: Boolean,
    val hasElevationChartData: Boolean,
    val hasHrChartData: Boolean,
    val hasRecoveryData: Boolean,
)

/**
 * An enabled item with no data for the current workout is hidden outside edit mode.
 * Inside edit mode the screen renders a placeholder instead, so the item stays draggable.
 */
object WorkoutDetailItemAvailability {
    private val ALWAYS_AVAILABLE =
        setOf(
            WorkoutDetailItemId.TRAINING_LOAD,
            WorkoutDetailItemId.AVG_PULSE,
            WorkoutDetailItemId.GAINED_STRAIN,
            WorkoutDetailItemId.RAS,
            WorkoutDetailItemId.OVERALL_LOAD,
            WorkoutDetailItemId.INTENSITY,
            WorkoutDetailItemId.ZONE_BREAKDOWN,
        )

    fun available(input: WorkoutDetailAvailabilityInput): Set<WorkoutDetailItemId> =
        buildSet {
            addAll(ALWAYS_AVAILABLE)
            if (input.hasDistance) add(WorkoutDetailItemId.DISTANCE)
            if (input.hasSpeed) add(WorkoutDetailItemId.AVG_PACE_SPEED)
            if (input.hasElevationGain) add(WorkoutDetailItemId.ELEVATION_GAIN)
            // PermissionRequired keeps rendering: that card owns the grant-permission CTA.
            if (input.routeState != RouteDataState.NotAvailable) add(WorkoutDetailItemId.ROUTE_CONTOUR)
            if (input.hasPaceSpeedChartData) add(WorkoutDetailItemId.PACE_SPEED_CHART)
            if (input.hasElevationChartData) add(WorkoutDetailItemId.ELEVATION_CHART)
            if (input.hasHrChartData) add(WorkoutDetailItemId.TRIMP_BREAKDOWN)
            if (input.hasRecoveryData) add(WorkoutDetailItemId.RECOVERY_HRR)
        }

    fun inputFrom(uiState: WorkoutDetailUiState): WorkoutDetailAvailabilityInput {
        val workout = uiState.workout
        return WorkoutDetailAvailabilityInput(
            hasDistance = workout?.totalDistanceMeters != null,
            hasSpeed = (workout?.avgSpeedKmh ?: 0f) > 0f,
            hasElevationGain = (uiState.displayElevationGainMeters ?: workout?.elevationGainMeters) != null,
            routeState = uiState.routeUiState.state,
            hasPaceSpeedChartData = uiState.paceSpeedChartData.isNotEmpty(),
            hasElevationChartData = uiState.elevationChartData.isNotEmpty(),
            hasHrChartData = uiState.hrChartData.isNotEmpty(),
            hasRecoveryData = uiState.hrr1Min != null || uiState.hrr2Min != null || uiState.hrr3Min != null,
        )
    }
}
