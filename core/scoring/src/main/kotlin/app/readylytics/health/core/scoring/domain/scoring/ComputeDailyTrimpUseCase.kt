package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.CanonicalWorkoutResult
import app.readylytics.health.core.model.domain.scoring.WorkoutHrQuality
import app.readylytics.health.core.model.domain.scoring.WorkoutScoringIdentity
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import javax.inject.Inject

class ComputeDailyTrimpUseCase
    @Inject
    constructor(
        private val canonicalWorkoutResolver: CanonicalWorkoutResolver,
    ) {
        constructor(computeWorkoutTrimpUseCase: ComputeWorkoutTrimpUseCase) :
            this(CanonicalWorkoutResolver(computeWorkoutTrimpUseCase))

        data class WorkoutInput(
            val id: String,
            val startTime: Long,
            val endTime: Long,
            val currentModelTrimp: Float?,
            val currentQuality: WorkoutHrQuality? = null,
            val currentSourceRevision: Long? = null,
            val currentScoringSnapshotId: String? = null,
            val currentAlgorithmRevision: Int? = null,
            val samples: List<ComputeWorkoutTrimpUseCase.HeartRateSample>,
            val quality: WorkoutHrQuality = WorkoutHrQuality.RAW,
            val sourceRevision: Long = 0L,
        ) {
            private fun hasRevisionMetadata(): Boolean =
                currentQuality != null && currentSourceRevision != null

            private fun hasSnapshotMetadata(): Boolean =
                currentScoringSnapshotId != null && currentAlgorithmRevision != null

            val priorResult: CanonicalWorkoutResult?
                get() =
                    if (hasRevisionMetadata() && hasSnapshotMetadata()) {
                        CanonicalWorkoutResult(
                            workoutId = id,
                            endTimeMs = endTime,
                            trimp = currentModelTrimp,
                            quality = checkNotNull(currentQuality),
                            sourceRevision = checkNotNull(currentSourceRevision),
                            scoringSnapshotId = checkNotNull(currentScoringSnapshotId),
                            algorithmRevision = checkNotNull(currentAlgorithmRevision),
                        )
                    } else {
                        null
                    }
        }

        data class WorkoutModelTrimpUpdate(
            val workoutId: String,
            val modelTrimp: Float?,
            val quality: WorkoutHrQuality = WorkoutHrQuality.RAW,
            val sourceRevision: Long = 0L,
            val scoringSnapshotId: String = "",
            val algorithmRevision: Int = 1,
        )

        data class CanonicalWorkoutTrimp(
            val workoutId: String,
            val endTimeMs: Long,
            val trimp: Float?,
        )

        data class DailyTrimpResult(
            val totalDailyTrimpRaw: Float?,
            val workoutModelTrimpUpdates: List<WorkoutModelTrimpUpdate>,
            val canonicalWorkoutTrimps: List<CanonicalWorkoutTrimp>,
        )

        private data class ResolvedWorkout(
            val canonicalTrimp: CanonicalWorkoutTrimp,
            val update: WorkoutModelTrimpUpdate?,
            val validTrimp: Float?,
        )

        fun execute(
            workouts: List<WorkoutInput>,
            prefs: UserPreferences,
            rhrBaselineValue: Float,
            frozenHrMax: Float?,
            identity: WorkoutScoringIdentity? = null,
        ): DailyTrimpResult {
            val hrMax = frozenHrMax ?: HeartRateFormulas.resolveMaxHeartRate(prefs)
            val baseIdentity =
                identity ?: WorkoutScoringIdentity(
                    sourceRevision = 0L,
                    scoringSnapshotId = "",
                    algorithmRevision = 1,
                )

            val resolvedWorkouts =
                workouts.map { workout ->
                    processWorkout(workout, prefs, rhrBaselineValue, hrMax, baseIdentity)
                }

            val hasInvalid = resolvedWorkouts.any { it.validTrimp == null }
            val totalDailyTrimpRaw =
                when {
                    workouts.isEmpty() -> 0f
                    hasInvalid -> null
                    else -> resolvedWorkouts.mapNotNull { it.validTrimp }.sum()
                }

            return DailyTrimpResult(
                totalDailyTrimpRaw = totalDailyTrimpRaw,
                workoutModelTrimpUpdates = resolvedWorkouts.mapNotNull { it.update },
                canonicalWorkoutTrimps = resolvedWorkouts.map { it.canonicalTrimp },
            )
        }

        private fun processWorkout(
            workout: WorkoutInput,
            prefs: UserPreferences,
            rhrBaselineValue: Float,
            hrMax: Float,
            baseIdentity: WorkoutScoringIdentity,
        ): ResolvedWorkout {
            val workoutIdentity = baseIdentity.copy(sourceRevision = workout.sourceRevision)
            val context =
                WorkoutScoringContext(
                    prefs = prefs,
                    rhrBaseline = rhrBaselineValue,
                    frozenHrMax = hrMax,
                    identity = workoutIdentity,
                )

            val input =
                CanonicalWorkoutInput(
                    workoutId = workout.id,
                    startMs = workout.startTime,
                    endMs = workout.endTime,
                    samples = workout.samples,
                    quality = workout.quality,
                    context = context,
                    prior = workout.priorResult,
                )

            val result = canonicalWorkoutResolver.resolve(input)
            val trimp = result.trimp
            val isValid = trimp != null && trimp.isFinite()

            val update =
                if (hasModelTrimpChanged(result, workout)) {
                    WorkoutModelTrimpUpdate(
                        workoutId = workout.id,
                        modelTrimp = result.trimp,
                        quality = result.quality,
                        sourceRevision = result.sourceRevision,
                        scoringSnapshotId = result.scoringSnapshotId,
                        algorithmRevision = result.algorithmRevision,
                    )
                } else {
                    null
                }

            return ResolvedWorkout(
                canonicalTrimp =
                    CanonicalWorkoutTrimp(
                        workoutId = workout.id,
                        endTimeMs = workout.endTime,
                        trimp = trimp,
                    ),
                update = update,
                validTrimp = if (isValid) trimp else null,
            )
        }

        private fun hasModelTrimpChanged(
            result: CanonicalWorkoutResult,
            workout: WorkoutInput,
        ): Boolean {
            val trimpChanged = result.trimp != workout.currentModelTrimp
            val qualityChanged = result.quality != workout.currentQuality
            val identityChanged = isIdentityDifferent(result, workout)
            return trimpChanged || qualityChanged || identityChanged
        }

        private fun isIdentityDifferent(
            result: CanonicalWorkoutResult,
            workout: WorkoutInput,
        ): Boolean {
            val srcChanged = result.sourceRevision != workout.currentSourceRevision
            val snapChanged = result.scoringSnapshotId != workout.currentScoringSnapshotId
            val algoChanged = result.algorithmRevision != workout.currentAlgorithmRevision
            return srcChanged || snapChanged || algoChanged
        }
    }
