package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.logW
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.max

class ComputeWorkoutLoadMetricsUseCase
    @Inject
    constructor(
        private val computeWorkoutTrimpUseCase: ComputeWorkoutTrimpUseCase,
        private val scoringCalculator: ScoringCalculator,
        private val workoutLoadClassifier: WorkoutLoadClassifier,
    ) {
        fun execute(
            workout: WorkoutData,
            workoutDate: LocalDate,
            samples: List<ComputeWorkoutTrimpUseCase.HeartRateSample>,
            prefs: UserPreferences,
            restingHrBaseline: Float?,
            trimpByDate: Map<LocalDate, Float>,
        ): WorkoutLoadMetrics {
            val computedTrimp =
                computeWorkoutTrimpUseCase
                    .execute(
                        workoutStartTime = workout.startTime,
                        workoutEndTime = workout.endTime,
                        workoutAvgHr = workout.avgHr,
                        samples = samples,
                        prefs = prefs,
                        restingHrBaseline = restingHrBaseline,
                        storedTrimp = workout.trimp,
                    ).getOrNull()
                    ?: workout.trimp

            val originalDayTrimp = trimpByDate[workoutDate] ?: 0f
            val trimpWithoutWorkout = (originalDayTrimp - computedTrimp).coerceAtLeast(0f)
            val trimpMapWith = trimpByDate.toMutableMap()
            val trimpMapWithout =
                trimpByDate.toMutableMap().apply {
                    put(workoutDate, trimpWithoutWorkout)
                }

            val atlWith = scoringCalculator.computeAtlEmaWithDecay(trimpMapWith, workoutDate)
            val ctlWith = scoringCalculator.computeCtlEmaWithDecay(trimpMapWith, workoutDate)
            val srWith = scoringCalculator.computeStrainRatio(atlWith, ctlWith)

            val atlWithout = scoringCalculator.computeAtlEmaWithDecay(trimpMapWithout, workoutDate)
            val ctlWithout = scoringCalculator.computeCtlEmaWithDecay(trimpMapWithout, workoutDate)
            val srWithout = scoringCalculator.computeStrainRatio(atlWithout, ctlWithout)

            val gainedStrain = max(0f, srWith - srWithout)
            val roundedGainedStrain = MetricFormatter.roundStrain(gainedStrain)
            val classification = classifyWorkoutLoad(workout, computedTrimp)

            return WorkoutLoadMetrics(
                preciseTrimp = computedTrimp,
                roundedTrimp = MetricFormatter.roundTrimp(computedTrimp),
                preciseGainedStrain = gainedStrain,
                roundedGainedStrain = roundedGainedStrain,
                gainedStrainDisplay = MetricFormatter.formatStrain(roundedGainedStrain),
                classification = classification,
            )
        }

        private fun classifyWorkoutLoad(
            workout: WorkoutData,
            preciseTrimp: Float,
        ): WorkoutLoadClassification? {
            if (!preciseTrimp.isFinite() || preciseTrimp < 0f) {
                logW("ComputeWorkoutLoadMetrics") {
                    "Skipping workout load classification due to invalid TRIMP input"
                }
                return null
            }

            val trimpPerMinute =
                preciseTrimp
                    .takeIf { workout.durationMinutes > 0 && workout.avgHr > 0f && it > 0f }
                    ?.let { it.toDouble() / workout.durationMinutes.toDouble() }

            val classification = workoutLoadClassifier.classify(preciseTrimp.toDouble(), trimpPerMinute)
            if (classification == null) {
                logW("ComputeWorkoutLoadMetrics") {
                    "Skipping workout load classification due to invalid workout load inputs"
                }
            } else if (classification.wasPromoted) {
                logD("ComputeWorkoutLoadMetrics") {
                    "Applied workout load promotion rule"
                }
            }

            return classification
        }

        data class WorkoutLoadMetrics(
            val preciseTrimp: Float,
            val roundedTrimp: Int,
            val preciseGainedStrain: Float,
            val roundedGainedStrain: Float,
            val gainedStrainDisplay: String,
            val classification: WorkoutLoadClassification?,
        )
    }
