package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
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
            storedTrimp: Float? = null,
        ): Result<Float> =
            try {
                val hrMax = HeartRateFormulas.resolveMaxHeartRate(prefs)

                // RHR baseline resolution with exercise-aware fallback:
                // 1. Use provided baseline if available (from BaselineComputer)
                // 2. Use override if set
                // 3. Estimate from workout data: workout-avg HR less the elevation during exercise
                //    This fallback only triggers when no historical baseline exists (edge case).
                //    For normal flow, ScoringRepositoryImpl always provides a calculated baseline.
                val rhrBaseline =
                    restingHrBaseline
                        ?: prefs.rhrBaselineOverride
                        ?: ScoringConstants.DEFAULT_RHR_BPM

                // STRICT FILTER: Only use samples within the workout boundaries
                val filteredSamples =
                    samples
                        .filter { it.timestamp.toEpochMilli() in workoutStartTime..workoutEndTime }
                        .sortedBy { it.timestamp }

                // If no valid samples are in range, calculate a "pseudo-integrated" TRIMP based on the session average.
                // This ensures that the fallback matches the integrated logic as closely as possible.
                if (filteredSamples.isEmpty()) {
                    val durationMinutes = (workoutEndTime - workoutStartTime) / 60_000f
                    return@execute Result.success(
                        if (durationMinutes > 0f) {
                            PaiCalculator.calculateDailyTrimp(
                                durationMinutes = durationMinutes,
                                hrAvg = workoutAvgHr,
                                rhrBaseline = rhrBaseline,
                                hrMax = hrMax,
                                gender = prefs.gender,
                                trimpModel = prefs.trimpModel,
                                banisterMultiplier = prefs.banisterMultiplier,
                                chengBeta = prefs.chengBeta,
                                itrimB = prefs.itrimB,
                            )
                        } else {
                            storedTrimp ?: 0f
                        },
                    )
                }

                var computedTrimp = 0f

                // Handle leading gap: from workoutStartTime to the first sample
                val firstSample = filteredSamples.first()
                val leadingGapMin = (firstSample.timestamp.toEpochMilli() - workoutStartTime) / 60_000f
                if (leadingGapMin > 0f) {
                    computedTrimp +=
                        PaiCalculator.calculateDailyTrimp(
                            durationMinutes = leadingGapMin,
                            hrAvg = firstSample.bpm.toFloat(),
                            rhrBaseline = rhrBaseline,
                            hrMax = hrMax,
                            gender = prefs.gender,
                            trimpModel = prefs.trimpModel,
                            banisterMultiplier = prefs.banisterMultiplier,
                            chengBeta = prefs.chengBeta,
                            itrimB = prefs.itrimB,
                        )
                }

                filteredSamples.forEachIndexed { i, sample ->
                    val nextMs =
                        if (i < filteredSamples.lastIndex) {
                            filteredSamples[i + 1].timestamp.toEpochMilli()
                        } else {
                            workoutEndTime
                        }

                    val durMin = (nextMs - sample.timestamp.toEpochMilli()) / 60_000f
                    if (durMin > 0f) {
                        computedTrimp +=
                            PaiCalculator.calculateDailyTrimp(
                                durationMinutes = durMin,
                                hrAvg = sample.bpm.toFloat(),
                                rhrBaseline = rhrBaseline,
                                hrMax = hrMax,
                                gender = prefs.gender,
                                trimpModel = prefs.trimpModel,
                                banisterMultiplier = prefs.banisterMultiplier,
                                chengBeta = prefs.chengBeta,
                                itrimB = prefs.itrimB,
                            )
                    }
                }
                Result.success(computedTrimp)
            } catch (e: Exception) {
                Result.failure("Failed to compute workout TRIMP", "TRIMP_COMPUTATION_ERROR")
            }

        data class HeartRateSample(
            val timestamp: Instant,
            val bpm: Int,
        )
    }
