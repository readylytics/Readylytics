package app.readylytics.health.domain.scoring

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.repository.WorkoutData
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.max

class ComputeWorkoutLoadMetricsUseCase
    @Inject
    constructor(
        private val computeWorkoutTrimpUseCase: ComputeWorkoutTrimpUseCase,
        private val scoringCalculator: ScoringCalculator,
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

            return WorkoutLoadMetrics(
                preciseTrimp = computedTrimp,
                roundedTrimp = MetricFormatter.roundTrimp(computedTrimp),
                preciseGainedStrain = gainedStrain,
                roundedGainedStrain = roundedGainedStrain,
                gainedStrainDisplay = MetricFormatter.formatStrain(roundedGainedStrain),
            )
        }

        data class WorkoutLoadMetrics(
            val preciseTrimp: Float,
            val roundedTrimp: Int,
            val preciseGainedStrain: Float,
            val roundedGainedStrain: Float,
            val gainedStrainDisplay: String,
        )
    }
