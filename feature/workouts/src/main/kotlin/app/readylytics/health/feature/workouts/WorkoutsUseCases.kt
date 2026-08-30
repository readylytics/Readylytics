package app.readylytics.health.feature.workouts

import app.readylytics.health.core.scoring.domain.scoring.Generate24hResidualFatigueCurveUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.workouts.weekly.ComputeWeeklyTrainingStatsUseCase
import javax.inject.Inject
import javax.inject.Singleton

/** Bundles the use cases and cached Health Connect permission gating the Workouts tab needs,
 *  keeping the [WorkoutsViewModel] constructor within detekt's LongParameterList threshold. */
@Singleton
class WorkoutsUseCases
    @Inject
    constructor(
        val getWorkoutDisplayMetrics: GetWorkoutDisplayMetricsUseCase,
        val computeWeeklyTrainingStats: ComputeWeeklyTrainingStatsUseCase,
        val generate24hResidualFatigueCurve: Generate24hResidualFatigueCurveUseCase,
        val distancePermissionGate: WorkoutsDistancePermissionGate,
    )
