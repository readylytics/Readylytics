package app.readylytics.health.feature.workouts

import app.readylytics.health.core.scoring.domain.cardio.TrainingStressBalanceCalculator
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import javax.inject.Inject
import javax.inject.Singleton

/** Bundles the pure-Kotlin scoring calculators the Workouts tab needs, keeping the
 *  [WorkoutsViewModel] constructor within detekt's LongParameterList threshold. */
@Singleton
data class WorkoutsScoringCalculators
    @Inject
    constructor(
        val scoringCalculator: ScoringCalculator,
        val trainingStressBalanceCalculator: TrainingStressBalanceCalculator,
    )
