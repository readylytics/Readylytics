package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.sync.mappers.WorkoutMapper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncWorkoutRouteUseCase
    @Inject
    constructor(
        private val hcRepo: HealthConnectRepository,
        private val healthIngestionStore: HealthIngestionStore,
    ) {
        suspend operator fun invoke(workoutId: String): Result<Unit> {
            val session =
                hcRepo.readExerciseSession(workoutId)
                    ?: return Result.failure("Workout session not found in Health Connect: $workoutId")
            val workoutInput = WorkoutMapper.mapExerciseSession(session)
            healthIngestionStore.persistSingleWorkoutRoute(
                workoutId = workoutInput.id,
                routePoints = workoutInput.routePoints,
                routeState = workoutInput.routeState,
                totalDistanceMeters = workoutInput.totalDistanceMeters,
                avgSpeedKmh = workoutInput.avgSpeedKmh,
                elevationGainMeters = workoutInput.elevationGainMeters,
            )
            return Result.success(Unit)
        }
    }
