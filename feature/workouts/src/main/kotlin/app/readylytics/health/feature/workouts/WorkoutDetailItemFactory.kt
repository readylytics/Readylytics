package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemId

/**
 * Maps every customizable detail item onto its renderer. Consumed by `ReorderableGrid`,
 * which decides width and placement from `WorkoutDetailItemCatalog.FULL_WIDTH_ITEMS`.
 */
fun buildWorkoutDetailItemDataMap(
    uiState: WorkoutDetailUiState,
    onGrantPermissionClick: () -> Unit,
    parentScrollInProgress: () -> Boolean,
): Map<WorkoutDetailItemId, @Composable (WorkoutDetailItemConfiguration) -> Unit> {
    val workout = uiState.workout ?: return emptyMap()
    return mapOf(
        WorkoutDetailItemId.TRAINING_LOAD to { _ ->
            TrainingLoadTile(computedTrimp = uiState.computedTrimp, workout = workout)
        },
        WorkoutDetailItemId.AVG_PULSE to { _ -> AvgPulseTile(workout = workout) },
        WorkoutDetailItemId.GAINED_STRAIN to { _ ->
            GainedStrainTile(
                gainedStrain = uiState.gainedStrain,
                gainedStrainDisplay = uiState.gainedStrainDisplay,
            )
        },
        WorkoutDetailItemId.RAS to { _ -> RasTile(ras = uiState.ras) },
        WorkoutDetailItemId.OVERALL_LOAD to { _ -> OverallLoadTile(classification = uiState.classification) },
        WorkoutDetailItemId.INTENSITY to { _ -> IntensityTile(classification = uiState.classification) },
        WorkoutDetailItemId.DISTANCE to { _ ->
            DistanceTile(workout = workout, unitSystem = uiState.unitSystem)
        },
        WorkoutDetailItemId.AVG_PACE_SPEED to { _ ->
            AvgPaceSpeedTile(workout = workout, unitSystem = uiState.unitSystem)
        },
        WorkoutDetailItemId.ELEVATION_GAIN to { _ ->
            ElevationGainTile(
                elevationGainMeters = uiState.displayElevationGainMeters ?: workout.elevationGainMeters,
                unitSystem = uiState.unitSystem,
            )
        },
        WorkoutDetailItemId.ZONE_BREAKDOWN to { _ -> ZoneBreakdownCard(workout = workout) },
        WorkoutDetailItemId.ROUTE_CONTOUR to { _ ->
            RouteContourCard(
                uiState = uiState.routeUiState,
                onGrantPermissionClick = onGrantPermissionClick,
            )
        },
        WorkoutDetailItemId.PACE_SPEED_CHART to { _ ->
            PaceSpeedChartCard(
                chartData = uiState.paceSpeedChartData,
                isPaceMode = uiState.isPaceMode,
                unitSystem = uiState.unitSystem,
                parentScrollInProgress = parentScrollInProgress,
            )
        },
        WorkoutDetailItemId.ELEVATION_CHART to { _ ->
            ElevationChartCard(
                chartData = uiState.elevationChartData,
                unitSystem = uiState.unitSystem,
                parentScrollInProgress = parentScrollInProgress,
            )
        },
        WorkoutDetailItemId.TRIMP_BREAKDOWN to { _ ->
            TrimpBreakdownChart(
                uiState.hrChartData,
                uiState.durationMinutes,
                parentScrollInProgress = parentScrollInProgress,
            )
        },
        WorkoutDetailItemId.RECOVERY_HRR to { _ ->
            WorkoutRecoverySection(uiState)
        },
    )
}
