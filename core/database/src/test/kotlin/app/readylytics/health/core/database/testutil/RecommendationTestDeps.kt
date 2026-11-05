package app.readylytics.health.core.database.testutil

import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import io.mockk.mockk

/** Collaborators of the morning-recommendation wiring; relaxed mocks unless a test supplies real ones. */
internal data class RecommendationTestDeps(
    val sleepSessionRepository: SleepSessionRepository = mockk(relaxed = true),
    val computeSleepMetricsUseCase: ComputeSleepMetricsUseCase = mockk(relaxed = true),
    val hrvResolver: CurrentNightHrvResolver = mockk(relaxed = true),
    val workoutRepository: WorkoutRepository = mockk(relaxed = true),
    val dailySummaryRepository: DailySummaryRepository = mockk(relaxed = true),
    val getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase = mockk(relaxed = true),
)
