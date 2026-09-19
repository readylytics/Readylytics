package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.getOrNull
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.CanonicalWorkoutResult
import app.readylytics.health.core.model.domain.scoring.WorkoutHrQuality
import app.readylytics.health.core.model.domain.scoring.WorkoutScoringIdentity
import javax.inject.Inject

data class WorkoutScoringContext(
    val prefs: UserPreferences,
    val rhrBaseline: Float,
    val frozenHrMax: Float,
    val identity: WorkoutScoringIdentity,
)

data class CanonicalWorkoutInput(
    val workoutId: String,
    val startMs: Long,
    val endMs: Long,
    val samples: List<ComputeWorkoutTrimpUseCase.HeartRateSample>,
    val quality: WorkoutHrQuality,
    val context: WorkoutScoringContext,
    val prior: CanonicalWorkoutResult?,
)

class CanonicalWorkoutResolver
    @Inject
    constructor(
        private val computeWorkoutTrimpUseCase: ComputeWorkoutTrimpUseCase,
    ) {
        fun resolve(input: CanonicalWorkoutInput): CanonicalWorkoutResult {
            if (input.endMs <= input.startMs) {
                return resolveZeroDuration(input)
            }
            return resolveNonZeroDuration(input)
        }

        private fun resolveZeroDuration(input: CanonicalWorkoutInput): CanonicalWorkoutResult {
            val resolvedQuality =
                if (input.quality == WorkoutHrQuality.UNAVAILABLE) {
                    WorkoutHrQuality.RAW
                } else {
                    input.quality
                }
            return CanonicalWorkoutResult(
                workoutId = input.workoutId,
                endTimeMs = input.endMs,
                trimp = 0f,
                quality = resolvedQuality,
                sourceRevision = input.context.identity.sourceRevision,
                scoringSnapshotId = input.context.identity.scoringSnapshotId,
                algorithmRevision = input.context.identity.algorithmRevision,
            )
        }

        private fun resolveNonZeroDuration(input: CanonicalWorkoutInput): CanonicalWorkoutResult {
            val validSamples = filterSamples(input)
            return if (validSamples.isEmpty()) {
                resolveMissingSamples(input)
            } else {
                computeFromSamples(input, validSamples)
            }
        }

        private fun filterSamples(input: CanonicalWorkoutInput) =
            input.samples
                .filter { it.timestamp.toEpochMilli() in input.startMs..input.endMs }
                .sortedBy { it.timestamp }

        private fun isMatchingPrior(
            prior: CanonicalWorkoutResult?,
            identity: WorkoutScoringIdentity,
        ): Boolean {
            if (prior?.trimp == null) return false
            val matchesSource = prior.sourceRevision == identity.sourceRevision
            val matchesSnapshot = prior.scoringSnapshotId == identity.scoringSnapshotId
            val matchesAlgorithm = prior.algorithmRevision == identity.algorithmRevision
            return matchesSource && matchesSnapshot && matchesAlgorithm
        }

        private fun resolveMissingSamples(input: CanonicalWorkoutInput): CanonicalWorkoutResult {
            val prior = input.prior
            return if (isMatchingPrior(prior, input.context.identity)) {
                requireNotNull(prior).copy(quality = WorkoutHrQuality.VALIDATED_PRIOR)
            } else {
                CanonicalWorkoutResult(
                    workoutId = input.workoutId,
                    endTimeMs = input.endMs,
                    trimp = null,
                    quality = WorkoutHrQuality.UNAVAILABLE,
                    sourceRevision = input.context.identity.sourceRevision,
                    scoringSnapshotId = input.context.identity.scoringSnapshotId,
                    algorithmRevision = input.context.identity.algorithmRevision,
                )
            }
        }

        private fun computeFromSamples(
            input: CanonicalWorkoutInput,
            validSamples: List<ComputeWorkoutTrimpUseCase.HeartRateSample>,
        ): CanonicalWorkoutResult {
            val avgHr = validSamples.map { it.bpm }.average().toFloat()
            val trimpResult =
                computeWorkoutTrimpUseCase.execute(
                    workoutStartTime = input.startMs,
                    workoutEndTime = input.endMs,
                    workoutAvgHr = if (avgHr.isNaN()) 0f else avgHr,
                    samples = validSamples,
                    prefs = input.context.prefs,
                    restingHrBaseline = input.context.rhrBaseline,
                    frozenHrMax = input.context.frozenHrMax,
                )
            val trimp = trimpResult.getOrNull()
            val isFiniteTrimp = trimp != null && trimp.isFinite()
            val resolvedQuality = resolveSampleQuality(input.quality, isFiniteTrimp)

            return CanonicalWorkoutResult(
                workoutId = input.workoutId,
                endTimeMs = input.endMs,
                trimp = if (isFiniteTrimp) trimp else null,
                quality = resolvedQuality,
                sourceRevision = input.context.identity.sourceRevision,
                scoringSnapshotId = input.context.identity.scoringSnapshotId,
                algorithmRevision = input.context.identity.algorithmRevision,
            )
        }

        private fun resolveSampleQuality(
            inputQuality: WorkoutHrQuality,
            isFiniteTrimp: Boolean,
        ): WorkoutHrQuality =
            when {
                !isFiniteTrimp -> WorkoutHrQuality.UNAVAILABLE
                inputQuality == WorkoutHrQuality.UNAVAILABLE -> WorkoutHrQuality.RAW
                else -> inputQuality
            }
    }
