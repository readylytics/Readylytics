package app.readylytics.health.core.database.testutil

import app.readylytics.health.core.database.data.repository.BodyMetricsDataLoader
import app.readylytics.health.core.database.data.repository.MorningRecommendationDependencies
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ScoringDataLoaders
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringDayUseCases
import app.readylytics.health.core.database.data.repository.ScoringHeartRateDataLoader
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRuntime
import app.readylytics.health.core.database.data.repository.ScoringSeriesLoader
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.scoring.domain.cardio.MaterkoAdaptedVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Single construction seam for [ScoringRepositoryImpl] in tests: a production constructor change
 * only needs to touch this body, not every test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun createScoringRepositoryForTest(
    loaders: ScoringTestLoaders,
    useCases: ScoringTestUseCases,
    settingsRepo: SettingsRepository,
    baselineComputer: BaselineComputer,
    scoringConfigFactory: ScoringConfigFactory,
    scoringHistoryRepository: ScoringHistoryRepository,
    readinessSummaryCoordinator: ReadinessSummaryCoordinator,
    runtime: ScoringRuntime = ScoringRuntime(UnconfinedTestDispatcher(), FIXED_TEST_CLOCK),
    recommendation: RecommendationTestDeps = RecommendationTestDeps(),
): ScoringRepositoryImpl =
    ScoringRepositoryImpl(
        loaders =
            ScoringDataLoaders(
                loaders.dataLoader,
                loaders.bodyMetricsDataLoader,
                loaders.seriesLoader,
                loaders.heartRateDataLoader,
            ),
        settingsRepo = settingsRepo,
        baselineComputer = baselineComputer,
        scoringConfigFactory = scoringConfigFactory,
        useCases =
            ScoringDayUseCases(
                computeDailyTrimp = useCases.computeDailyTrimp,
                computeResidualFatigue = useCases.computeResidualFatigue,
                resolveDailyBaselines = useCases.resolveDailyBaselines,
                assembleEverydayLoadInput = useCases.assembleEverydayLoadInput,
                computeTrainingReadiness = useCases.computeTrainingReadiness,
                uthVo2MaxCalculator = useCases.uthVo2MaxCalculator,
                vo2MaxSourceResolver = useCases.vo2MaxSourceResolver,
                materkoAdaptedVo2MaxCalculator = useCases.materkoAdaptedVo2MaxCalculator,
            ),
        scoringHistoryRepository = scoringHistoryRepository,
        readinessSummaryCoordinator = readinessSummaryCoordinator,
        runtime = runtime,
        recommendationDependencies =
            MorningRecommendationDependencies(
                sleepSessionRepository = recommendation.sleepSessionRepository,
                computeSleepMetricsUseCase = recommendation.computeSleepMetricsUseCase,
                hrvResolver = recommendation.hrvResolver,
                workoutRepository = recommendation.workoutRepository,
                dailySummaryRepository = recommendation.dailySummaryRepository,
                getWorkoutDisplayMetricsUseCase = recommendation.getWorkoutDisplayMetricsUseCase,
            ),
    )
