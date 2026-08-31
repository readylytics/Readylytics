package app.readylytics.health.data.backup

import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestoreLayoutRepositories
    @Inject
    constructor(
        val cardConfigurationRepository: CardConfigurationRepository,
        val vitalsLayoutRepository: VitalsLayoutRepository,
        val sleepLayoutRepository: SleepLayoutRepository,
        val workoutsLayoutRepository: WorkoutsLayoutRepository,
        val workoutDetailLayoutRepository: WorkoutDetailLayoutRepository,
    )
