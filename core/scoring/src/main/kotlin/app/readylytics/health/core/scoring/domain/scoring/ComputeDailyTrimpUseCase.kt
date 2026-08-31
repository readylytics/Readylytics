package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase

import app.readylytics.health.core.model.domain.model.getOrNull
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import javax.inject.Inject

class ComputeDailyTrimpUseCase
    @Inject
    constructor(
        private val computeWorkoutTrimpUseCase: ComputeWorkoutTrimpUseCase,
    ) {
        data class WorkoutInput(
            val id: String,
            val startTime: Long,
            val endTime: Long,
            val currentModelTrimp: Float?,
            val samples: List<ComputeWorkoutTrimpUseCase.HeartRateSample>,
        )

        data class WorkoutModelTrimpUpdate(
            val workoutId: String,
            val modelTrimp: Float,
        )

        data class CanonicalWorkoutTrimp(
            val workoutId: String,
            val endTimeMs: Long,
            val trimp: Float,
        )

        data class DailyTrimpResult(
            val totalDailyTrimpRaw: Float,
            val workoutModelTrimpUpdates: List<WorkoutModelTrimpUpdate>,
            val canonicalWorkoutTrimps: List<CanonicalWorkoutTrimp>,
        )

        fun execute(
            workouts: List<WorkoutInput>,
            prefs: UserPreferences,
            rhrBaselineValue: Float,
            frozenHrMax: Float?,
        ): DailyTrimpResult {
            var dailyTrimpRaw = 0f
            val workoutModelTrimpUpdates = mutableListOf<WorkoutModelTrimpUpdate>()
            val canonicalWorkoutTrimps = mutableListOf<CanonicalWorkoutTrimp>()

            workouts.forEach { workout ->
                val workoutAvgHr =
                    workout.samples
                        .takeIf { it.isNotEmpty() }
                        ?.map { it.bpm }
                        ?.average()
                        ?.toFloat()
                        ?: 0f

                val workoutTrimpResult =
                    computeWorkoutTrimpUseCase.execute(
                        workoutStartTime = workout.startTime,
                        workoutEndTime = workout.endTime,
                        workoutAvgHr = workoutAvgHr,
                        samples = workout.samples,
                        prefs = prefs,
                        restingHrBaseline = rhrBaselineValue,
                        frozenHrMax = frozenHrMax,
                    )
                val workoutTrimp = workoutTrimpResult.getOrNull() ?: 0f
                dailyTrimpRaw += workoutTrimp
                canonicalWorkoutTrimps +=
                    CanonicalWorkoutTrimp(
                        workoutId = workout.id,
                        endTimeMs = workout.endTime,
                        trimp = workoutTrimp,
                    )

                if (workout.currentModelTrimp != workoutTrimp) {
                    workoutModelTrimpUpdates += WorkoutModelTrimpUpdate(workout.id, workoutTrimp)
                }
            }

            return DailyTrimpResult(
                totalDailyTrimpRaw = dailyTrimpRaw,
                workoutModelTrimpUpdates = workoutModelTrimpUpdates,
                canonicalWorkoutTrimps = canonicalWorkoutTrimps,
            )
        }
    }
