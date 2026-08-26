package app.readylytics.health.feature.workouts

import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.workouts.weekly.ComputeWeeklyTrainingStatsUseCase
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
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
        val distancePermissionGate: WorkoutsDistancePermissionGate,
        private val clock: Clock = Clock.systemDefaultZone(),
    ) {
        /** Real "today" for comparison-window selection; injectable so tests stay deterministic. */
        fun today(zoneId: ZoneId): LocalDate = LocalDate.now(clock.withZone(zoneId))
    }
