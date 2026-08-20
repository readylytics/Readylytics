package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.domain.model.DomainRouteLocation
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.model.RouteState
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.sync.mappers.WorkoutMapper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncWorkoutRouteUseCase
    @Inject
    constructor(
        private val hcRepo: HealthConnectRepository,
        private val healthIngestionStore: HealthIngestionStore,
    ) {
        /**
         * @param grantedRoutePoints route handed back by the per-session consent dialog
         * (`ExerciseRouteRequestContract`). That dialog returns the polyline directly as a one-time
         * grant -- re-reading the session afterwards still yields `ConsentRequired` -- so the points
         * must be injected here rather than fetched again. Null/empty keeps whatever the session
         * read produced.
         */
        suspend operator fun invoke(
            workoutId: String,
            grantedRoutePoints: List<DomainRouteLocation>? = null,
        ): Result<Unit> {
            val session =
                hcRepo.readExerciseSession(workoutId)
                    ?: return Result.failure("Workout session not found in Health Connect: $workoutId")
            val resolvedSession =
                if (grantedRoutePoints.isNullOrEmpty()) {
                    session
                } else {
                    session.copy(routePoints = grantedRoutePoints, routeState = RouteState.IMPORTED)
                }
            val workoutInput = WorkoutMapper.mapExerciseSession(resolvedSession)
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
