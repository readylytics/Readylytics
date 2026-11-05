package app.readylytics.health.core.database.testutil

import app.readylytics.health.core.database.data.repository.BaseSummaryAssembler
import app.readylytics.health.core.database.data.repository.CalibrationGate
import app.readylytics.health.core.database.data.repository.DailyTrimpComputer
import app.readylytics.health.core.database.data.repository.DayAssembler
import app.readylytics.health.core.database.data.repository.FinalSummaryAssembler
import app.readylytics.health.core.database.data.repository.RasTotalsComputer
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ResidualFatigueComputer
import app.readylytics.health.core.database.data.repository.ScoringDayContextResolver
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRuntime
import app.readylytics.health.core.database.data.repository.Vo2MaxScoringDependencies
import app.readylytics.health.core.database.data.repository.WalkForwardContextLoader
import app.readylytics.health.core.database.data.repository.recommendation.MorningRecommendationAssembler
import app.readylytics.health.core.database.data.repository.recommendation.MorningRecoveryLoader
import app.readylytics.health.core.database.data.repository.recommendation.WorkoutExampleLoader
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Single construction seam for [ScoringRepositoryImpl] in tests: a production constructor change
 * only needs to touch this body, not every test.
 *
 * Builds the same collaborator graph Hilt does, by hand. Like the Hilt `@Singleton` graph, one
 * [ResidualFatigueComputer] and one [CalibrationGate] are shared by every collaborator that needs
 * them. Pass [dayAssembler] to substitute the day-assembly collaborator.
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
    dayAssembler: DayAssembler? = null,
): ScoringRepositoryImpl {
    val graph =
        ScoringTestGraph(
            loaders,
            useCases,
            baselineComputer,
            scoringConfigFactory,
            scoringHistoryRepository,
            readinessSummaryCoordinator,
            recommendation,
        )
    return ScoringRepositoryImpl(
        settingsRepo = settingsRepo,
        dayAssembler = dayAssembler ?: graph.dayAssembler(runtime),
        dataLoader = loaders.dataLoader,
        residualFatigueComputer = graph.residualFatigueComputer,
        walkForwardContextLoader = graph.walkForwardContextLoader(),
        runtime = runtime,
    )
}

private class ScoringTestGraph(
    private val loaders: ScoringTestLoaders,
    private val useCases: ScoringTestUseCases,
    private val baselineComputer: BaselineComputer,
    private val scoringConfigFactory: ScoringConfigFactory,
    private val scoringHistoryRepository: ScoringHistoryRepository,
    private val readinessSummaryCoordinator: ReadinessSummaryCoordinator,
    private val recommendation: RecommendationTestDeps,
) {
    val residualFatigueComputer = ResidualFatigueComputer(loaders.dataLoader, useCases.computeResidualFatigue)
    private val calibrationGate = CalibrationGate(scoringHistoryRepository)

    fun walkForwardContextLoader(): WalkForwardContextLoader =
        WalkForwardContextLoader(
            loaders.seriesLoader,
            loaders.bodyMetricsDataLoader,
            baselineComputer,
            scoringHistoryRepository,
            residualFatigueComputer,
        )

    fun dayAssembler(runtime: ScoringRuntime): DayAssembler =
        DayAssembler(
            readinessSummaryCoordinator = readinessSummaryCoordinator,
            dataLoader = loaders.dataLoader,
            dailyTrimpComputer =
                DailyTrimpComputer(
                    loaders.dataLoader,
                    loaders.heartRateDataLoader,
                    useCases.computeDailyTrimp,
                    useCases.assembleEverydayLoadInput,
                ),
            rasTotalsComputer = RasTotalsComputer(loaders.seriesLoader),
            finalSummaryAssembler = finalSummaryAssembler(),
            morningRecommendationAssembler = morningRecommendationAssembler(),
            scoringDayContextResolver =
                ScoringDayContextResolver(
                    scoringConfigFactory,
                    useCases.resolveDailyBaselines,
                    scoringHistoryRepository,
                ),
            defaultDispatcher = runtime.defaultDispatcher,
        )

    private fun finalSummaryAssembler(): FinalSummaryAssembler =
        FinalSummaryAssembler(
            BaseSummaryAssembler(loaders.bodyMetricsDataLoader),
            calibrationGate,
            baselineComputer,
            loaders.bodyMetricsDataLoader,
            readinessSummaryCoordinator,
            residualFatigueComputer,
            useCases.computeTrainingReadiness,
            Vo2MaxScoringDependencies(
                useCases.uthVo2MaxCalculator,
                useCases.materkoAdaptedVo2MaxCalculator,
                useCases.vo2MaxSourceResolver,
            ),
        )

    private fun morningRecommendationAssembler(): MorningRecommendationAssembler =
        MorningRecommendationAssembler(
            recommendation.sleepSessionRepository,
            MorningRecoveryLoader(
                recommendation.sleepSessionRepository,
                recommendation.computeSleepMetricsUseCase,
                recommendation.hrvResolver,
                residualFatigueComputer,
                scoringConfigFactory,
                baselineComputer,
                calibrationGate,
            ),
            WorkoutExampleLoader(
                recommendation.workoutRepository,
                recommendation.dailySummaryRepository,
                recommendation.getWorkoutDisplayMetricsUseCase,
            ),
        )
}
