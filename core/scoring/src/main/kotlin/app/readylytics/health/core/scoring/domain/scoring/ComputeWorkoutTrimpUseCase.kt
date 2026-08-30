package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.RasCalculator

import app.readylytics.health.core.model.domain.scoring.ScoringConstants

import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import app.readylytics.health.core.model.domain.util.logE
import java.time.Instant
import javax.inject.Inject

class ComputeWorkoutTrimpUseCase
    @Inject
    constructor() {
        fun execute(
            workoutStartTime: Long,
            workoutEndTime: Long,
            workoutAvgHr: Float,
            samples: List<HeartRateSample>,
            prefs: UserPreferences,
            restingHrBaseline: Float? = null,
            frozenHrMax: Float? = null,
        ): Result<Float> =
            try {
                val calculationContext =
                    TrimpCalculationContext(
                        prefs = prefs,
                        rhrBaseline =
                            restingHrBaseline
                                ?: prefs.rhrBaselineOverride
                                ?: ScoringConstants.DEFAULT_RHR_BPM,
                        hrMax = frozenHrMax ?: HeartRateFormulas.resolveMaxHeartRate(prefs),
                    )
                val filteredSamples =
                    samples
                        .filter { it.timestamp.toEpochMilli() in workoutStartTime..workoutEndTime }
                        .sortedBy { it.timestamp }
                val computedTrimp =
                    if (filteredSamples.isEmpty()) {
                        computeWithoutSamples(
                            workoutStartTime,
                            workoutEndTime,
                            workoutAvgHr,
                            calculationContext,
                        )
                    } else {
                        integrateSamples(
                            workoutStartTime,
                            workoutEndTime,
                            filteredSamples,
                            calculationContext,
                        )
                    }
                Result.success(computedTrimp)
            } catch (e: Exception) {
                logE("ComputeWorkoutTrimp", e) {
                    "TRIMP failed for workout $workoutStartTime..$workoutEndTime"
                }
                Result.failure("Failed to compute workout TRIMP", "TRIMP_COMPUTATION_ERROR")
            }

        private fun computeWithoutSamples(
            workoutStartTime: Long,
            workoutEndTime: Long,
            workoutAvgHr: Float,
            context: TrimpCalculationContext,
        ): Float {
            val durationMinutes = (workoutEndTime - workoutStartTime) / 60_000f
            if (durationMinutes <= 0f) {
                // Backup rows can contain equal/reversed timestamps. Edwards-style stored `trimp`
                // is not an input here, so invalid duration canonicalizes to zero.
                return 0f
            }
            return calculateTrimp(durationMinutes, workoutAvgHr, context)
        }

        private fun integrateSamples(
            workoutStartTime: Long,
            workoutEndTime: Long,
            samples: List<HeartRateSample>,
            context: TrimpCalculationContext,
        ): Float {
            val firstSample = samples.first()
            var computedTrimp =
                calculateTrimp(
                    durationMinutes = (firstSample.timestamp.toEpochMilli() - workoutStartTime) / 60_000f,
                    hrAvg = firstSample.bpm.toFloat(),
                    context = context,
                )
            samples.forEachIndexed { index, sample ->
                val nextMs =
                    samples
                        .getOrNull(index + 1)
                        ?.timestamp
                        ?.toEpochMilli()
                        ?: workoutEndTime
                computedTrimp +=
                    calculateTrimp(
                        durationMinutes = (nextMs - sample.timestamp.toEpochMilli()) / 60_000f,
                        hrAvg = sample.bpm.toFloat(),
                        context = context,
                    )
            }
            return computedTrimp
        }

        private fun calculateTrimp(
            durationMinutes: Float,
            hrAvg: Float,
            context: TrimpCalculationContext,
        ): Float {
            if (durationMinutes <= 0f) return 0f
            val prefs = context.prefs
            return RasCalculator.calculateDailyTrimp(
                durationMinutes = durationMinutes,
                hrAvg = hrAvg,
                rhrBaseline = context.rhrBaseline,
                hrMax = context.hrMax,
                gender = prefs.gender,
                trimpModel = prefs.trimpModel,
                banisterMultiplier = prefs.banisterMultiplier,
                chengBeta = prefs.chengBeta,
                itrimB = prefs.itrimB,
                ltBpm = prefs.zone3MaxBpm.toFloat(),
            )
        }

        private data class TrimpCalculationContext(
            val prefs: UserPreferences,
            val rhrBaseline: Float,
            val hrMax: Float,
        )

        data class HeartRateSample(
            val timestamp: Instant,
            val bpm: Int,
        )
    }
