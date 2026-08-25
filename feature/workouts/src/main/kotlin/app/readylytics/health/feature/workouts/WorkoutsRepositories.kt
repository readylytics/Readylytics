package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Bundles the repositories the Workouts tab needs, keeping the
 *  [WorkoutsViewModel] constructor within detekt's LongParameterList threshold. */
@Singleton
class WorkoutsRepositories
    @Inject
    constructor(
        val dailySummary: DailySummaryRepository,
        val workout: WorkoutRepository,
        val heartRate: HeartRateRepository,
    )
