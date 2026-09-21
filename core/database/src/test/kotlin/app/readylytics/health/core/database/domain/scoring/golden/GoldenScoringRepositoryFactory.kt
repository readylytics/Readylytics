package app.readylytics.health.core.database.domain.scoring.golden

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.repository.BodyMetricsDataLoader
import app.readylytics.health.core.database.data.repository.MorningRecommendationDependencies
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ScoringDataLoaders
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringDayUseCases
import app.readylytics.health.core.database.data.repository.ScoringHeartRateDataLoader
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringSeriesLoader
import app.readylytics.health.core.database.data.repository.SleepSessionRepositoryImpl
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.SleepMetricsCollaborators
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
internal class GoldenScoringRepositoryFactory(
    private val db: HealthDatabase,
) {
    fun create(settingsRepository: SettingsRepository): ScoringRepositoryImpl {
        val history =
            ScoringHistoryRepositoryImpl(
                db.heartRateDao(),
                db.hrvDao(),
                db.sleepSessionDao(),
                db.dailySummaryDao(),
                db.minuteBucketDao(),
            )
        val loadStrategy = LoadScoringStrategy()
        val calculator =
            CompositeScoringCalculator(
                sleepStrategy = SleepScoringStrategy(loadStrategy),
                rasStrategy = RasScoringStrategy(),
                loadStrategy = loadStrategy,
            )
        val baseline = BaselineComputer(history, calculator)
        val configFactory = ScoringConfigFactory()
        val sleepMetrics = createSleepMetrics(history, calculator, baseline, configFactory)
        val loaders = createLoaders()
        val resolveBaselines = ResolveDailyBaselinesUseCase(baseline)
        val readiness =
            ReadinessSummaryCoordinator(
                dataLoader = loaders.day,
                seriesLoader = loaders.series,
                scoringHistoryRepository = history,
                baselineComputer = baseline,
                buildLoadSeriesUseCase = BuildLoadSeriesUseCase(calculator),
                computeSleepMetricsUseCase = sleepMetrics,
                resolveDailyBaselinesUseCase = resolveBaselines,
                assembleDailySummaryUseCase = AssembleDailySummaryUseCase(),
            )
        return ScoringRepositoryImpl(
            loaders = loaders,
            settingsRepo = settingsRepository,
            baselineComputer = baseline,
            scoringConfigFactory = configFactory,
            useCases = createDayUseCases(calculator, resolveBaselines),
            scoringHistoryRepository = history,
            readinessSummaryCoordinator = readiness,
            defaultDispatcher = UnconfinedTestDispatcher(),
            recommendationDependencies =
                MorningRecommendationDependencies(
                    sleepSessionRepository = io.mockk.mockk(relaxed = true),
                    computeSleepMetricsUseCase = io.mockk.mockk(relaxed = true),
                    hrvResolver = io.mockk.mockk(relaxed = true),
                    workoutRepository = io.mockk.mockk(relaxed = true),
                    dailySummaryRepository = io.mockk.mockk(relaxed = true),
                    getWorkoutDisplayMetricsUseCase = io.mockk.mockk(relaxed = true),
                ),
        )
    }

    private fun createDayUseCases(
        calculator: CompositeScoringCalculator,
        resolveBaselines: ResolveDailyBaselinesUseCase,
    ) = ScoringDayUseCases(
        ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
        ComputeResidualFatigueUseCase(),
        resolveBaselines,
        AssembleEverydayLoadInputUseCase(),
        ComputeTrainingReadinessUseCase(calculator),
        UthVo2MaxCalculator(),
        Vo2MaxSourceResolver(),
    )

    private fun createLoaders() =
        ScoringDataLoaders(
            ScoringDayDataLoader(db.workoutDao(), db.sleepSessionDao(), db.dailySummaryDao()),
            BodyMetricsDataLoader(
                db.weightRecordDao(),
                db.bodyFatRecordDao(),
                db.bloodPressureRecordDao(),
                db.oxygenSaturationRecordDao(),
                db.bodyTemperatureRecordDao(),
                db.vo2MaxRecordDao(),
            ),
            ScoringSeriesLoader(db.workoutDao(), db.dailySummaryDao()),
            ScoringHeartRateDataLoader(db.heartRateDao(), db.minuteBucketDao()),
        )

    private fun createSleepMetrics(
        history: ScoringHistoryRepositoryImpl,
        calculator: CompositeScoringCalculator,
        baseline: BaselineComputer,
        configFactory: ScoringConfigFactory,
    ): ComputeSleepMetricsUseCase {
        val sessions = SleepSessionRepositoryImpl(db.sleepSessionDao(), db.sleepStageDao())
        val circadian =
            CircadianConsistencyRepository(
                sessions,
                FakeSettingsRepository(UserPreferences()),
                FakeEncryptionManager(),
            )
        return ComputeSleepMetricsUseCase(
            collaborators =
                SleepMetricsCollaborators(
                    baselineComputer = baseline,
                    scoringHistoryRepository = history,
                    scoringCalculator = calculator,
                    scoringConfigFactory = configFactory,
                    encryptionManager = FakeEncryptionManager(),
                    hrvResolver = CurrentNightHrvResolver(history),
                    sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(history),
                    nadirAnalyzer = SleepNadirAnalyzer(calculator),
                    coverageValidator = HrCoverageValidator(),
                    sleepModifierResolver = SleepModifierResolver(sessions, circadian),
                ),
        )
    }
}
