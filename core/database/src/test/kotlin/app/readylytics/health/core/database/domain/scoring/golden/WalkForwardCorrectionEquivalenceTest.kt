package app.readylytics.health.core.database.domain.scoring.golden

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
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
import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * Task 5 Step 4: Proves that incremental correction replays across early, middle, and recent
 * anchor days over a 200+ day history produce bit-identical results to a full walk-forward replay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WalkForwardCorrectionEquivalenceTest {
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private val startDate: LocalDate = LocalDate.of(2024, 1, 1)
    private val endDate: LocalDate = startDate.plusDays(204) // 205 days total

    @Test
    fun `full replay matches early, middle, and recent correction replay across 200+ days`() =
        runBlocking {
            val fullReplayDb = createAndSeedDatabase()
            val correctionDb = createAndSeedDatabase()

            try {
                val fullSupport = createDailyRecomputeSupport(fullReplayDb)
                val correctionSupport = createDailyRecomputeSupport(correctionDb)
                val stepsMap =
                    GoldenFixtureDataBuilder(zoneId, seed = 42L)
                        .build(fullReplayDb, startDate, endDate)
                        .stepsByDate
                GoldenFixtureDataBuilder(zoneId, seed = 42L)
                    .build(correctionDb, startDate, endDate)

                val prefs =
                    UserPreferences(
                        scoringZoneId = zoneId.id,
                        installDate = startDate.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        age = 30,
                    )
                val runContext = ScoringRunContext.capture(prefs, startDate.atStartOfDay(zoneId).toInstant())

                // 1. Run full walk-forward replay on fullReplayDb
                recomputeRange(fullSupport, startDate, endDate, stepsMap, prefs, runContext)

                // 2. Run full initial pass on correctionDb
                recomputeRange(correctionSupport, startDate, endDate, stepsMap, prefs, runContext)

                // 3. Test correction replay from early, middle, and recent slices on correctionDb
                val earlyDay = startDate.plusDays(25)
                val middleDay = startDate.plusDays(100)
                val recentDay = startDate.plusDays(185)

                for (correctionStart in listOf(earlyDay, middleDay, recentDay)) {
                    val instant = correctionStart.atStartOfDay(zoneId).toInstant()
                    val correctionRunContext = ScoringRunContext.capture(prefs, instant)
                    recomputeRange(correctionSupport, correctionStart, endDate, stepsMap, prefs, correctionRunContext)

                    val fullSummaries = readSummaries(fullReplayDb, correctionStart, endDate)
                    val correctionSummaries = readSummaries(correctionDb, correctionStart, endDate)
                    assertEquals(fullSummaries, correctionSummaries, "Summaries mismatch at $correctionStart")

                    val fullWorkouts = readWorkouts(fullReplayDb, correctionStart, endDate)
                    val correctionWorkouts = readWorkouts(correctionDb, correctionStart, endDate)
                    assertEquals(fullWorkouts, correctionWorkouts, "Workouts mismatch at $correctionStart")
                }
            } finally {
                fullReplayDb.close()
                correctionDb.close()
            }
        }

    private suspend fun recomputeRange(
        support: DailyRecomputeSupport,
        fromDay: LocalDate,
        toDay: LocalDate,
        stepsMap: Map<LocalDate, Long>,
        prefs: UserPreferences,
        runContext: ScoringRunContext,
    ) {
        val contexts =
            WalkForwardContexts(
                trimp = support.buildWalkForwardTrimpContext(fromDay, toDay, zoneId),
                baseline = support.buildWalkForwardBaselineContext(fromDay, toDay, zoneId),
                fatigue = support.buildWalkForwardFatigueContext(fromDay, toDay, prefs, runContext),
                vo2Max = support.buildWalkForwardVo2MaxContext(fromDay, toDay, zoneId),
                ras = support.buildWalkForwardRasContext(fromDay, zoneId),
            )
        var day = fromDay
        while (!day.isAfter(toDay)) {
            support.inRecomputeTransaction {
                support.recomputeDay(day, stepsMap[day], prefs, contexts, runContext)
            }
            day = day.plusDays(1)
        }
    }

    private suspend fun readSummaries(db: HealthDatabase, fromDay: LocalDate, toDay: LocalDate): List<String> {
        val fromMs = fromDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val toMs = toDay.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return db
            .dailySummaryDao()
            .getAllSummaries()
            .filter { it.dateMidnightMs in fromMs until toMs }
            .sortedBy { it.dateMidnightMs }
            .map { it.toString() }
    }

    private suspend fun readWorkouts(db: HealthDatabase, fromDay: LocalDate, toDay: LocalDate): List<String> {
        val fromMs = fromDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val toMs = toDay.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return db
            .workoutDao()
            .getWorkoutsInRange(fromMs, toMs)
            .sortedBy { it.startTime }
            .map { it.toString() }
    }

    private fun createAndSeedDatabase(): HealthDatabase {
        return Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                HealthDatabase::class.java,
            ).build()
    }

    private fun createDailyRecomputeSupport(db: HealthDatabase): DailyRecomputeSupport {
        val prefs = UserPreferences(scoringZoneId = zoneId.id)
        val settingsRepo = FakeSettingsRepository(prefs)
        val historyRepo =
            ScoringHistoryRepositoryImpl(
                db.heartRateDao(), db.hrvDao(), db.sleepSessionDao(), db.dailySummaryDao(), db.minuteBucketDao(),
            )
        val loadScoringStrategy = LoadScoringStrategy()
        val calculator =
            CompositeScoringCalculator(
                sleepStrategy = SleepScoringStrategy(loadScoringStrategy),
                rasStrategy = RasScoringStrategy(),
                loadStrategy = loadScoringStrategy,
            )
        val baselineComputer = BaselineComputer(historyRepo, calculator)
        val configFactory = ScoringConfigFactory()
        val dataLoader = ScoringDayDataLoader(db.workoutDao(), db.sleepSessionDao(), db.dailySummaryDao())
        val seriesLoader = ScoringSeriesLoader(db.workoutDao(), db.dailySummaryDao())

        val deps =
            ScoringTestDependencies(
                db, settingsRepo, historyRepo, calculator, baselineComputer, configFactory, dataLoader, seriesLoader,
            )
        val resolveDailyBaselinesUseCase = ResolveDailyBaselinesUseCase(baselineComputer)
        val readinessCoordinator = createReadinessCoordinator(deps, resolveDailyBaselinesUseCase)
        val bodyMetricsLoader =
            BodyMetricsDataLoader(
                db.weightRecordDao(), db.bodyFatRecordDao(), db.bloodPressureRecordDao(),
                db.oxygenSaturationRecordDao(), db.bodyTemperatureRecordDao(), db.vo2MaxRecordDao(),
            )
        val heartRateDataLoader = ScoringHeartRateDataLoader(db.heartRateDao(), db.minuteBucketDao())
        val scoringRepo =
            ScoringRepositoryImpl(
                loaders = ScoringDataLoaders(dataLoader, bodyMetricsLoader, seriesLoader, heartRateDataLoader),
                settingsRepo = settingsRepo,
                baselineComputer = baselineComputer,
                scoringConfigFactory = configFactory,
                useCases = createScoringDayUseCases(resolveDailyBaselinesUseCase, calculator),
                scoringHistoryRepository = historyRepo,
                readinessSummaryCoordinator = readinessCoordinator,
                defaultDispatcher = UnconfinedTestDispatcher(),
                recommendationDependencies = createRecommendationDependencies(),
            )
        return DailyRecomputeSupport(scoringRepo, settingsRepo, RoomTransactionRunner(db))
    }

    private data class ScoringTestDependencies(
        val db: HealthDatabase,
        val settingsRepo: FakeSettingsRepository,
        val historyRepo: ScoringHistoryRepositoryImpl,
        val calculator: CompositeScoringCalculator,
        val baselineComputer: BaselineComputer,
        val configFactory: ScoringConfigFactory,
        val dataLoader: ScoringDayDataLoader,
        val seriesLoader: ScoringSeriesLoader,
    )

    private fun createScoringDayUseCases(
        resolveBaselines: ResolveDailyBaselinesUseCase,
        calculator: CompositeScoringCalculator,
    ) = ScoringDayUseCases(
        ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
        ComputeResidualFatigueUseCase(),
        resolveBaselines,
        AssembleEverydayLoadInputUseCase(),
        ComputeTrainingReadinessUseCase(calculator),
        UthVo2MaxCalculator(),
        Vo2MaxSourceResolver(),
    )

    private fun createRecommendationDependencies() =
        MorningRecommendationDependencies(
            sleepSessionRepository = io.mockk.mockk(relaxed = true),
            computeSleepMetricsUseCase = io.mockk.mockk(relaxed = true),
            hrvResolver = io.mockk.mockk(relaxed = true),
            workoutRepository = io.mockk.mockk(relaxed = true),
            dailySummaryRepository = io.mockk.mockk(relaxed = true),
            getWorkoutDisplayMetricsUseCase = io.mockk.mockk(relaxed = true),
        )

    private fun createReadinessCoordinator(
        deps: ScoringTestDependencies,
        resolveDailyBaselinesUseCase: ResolveDailyBaselinesUseCase,
    ): ReadinessSummaryCoordinator {
        val sessionRepo = SleepSessionRepositoryImpl(deps.db.sleepSessionDao(), deps.db.sleepStageDao())
        val circadianRepo = CircadianConsistencyRepository(sessionRepo, deps.settingsRepo, FakeEncryptionManager())
        val sleepModifierResolver = SleepModifierResolver(sessionRepo, circadianRepo)
        val computeSleepMetricsUseCase =
            ComputeSleepMetricsUseCase(
                collaborators =
                    SleepMetricsCollaborators(
                        baselineComputer = deps.baselineComputer,
                        scoringHistoryRepository = deps.historyRepo,
                        scoringCalculator = deps.calculator,
                        scoringConfigFactory = deps.configFactory,
                        encryptionManager = FakeEncryptionManager(),
                        hrvResolver = CurrentNightHrvResolver(deps.historyRepo),
                        sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(deps.historyRepo),
                        nadirAnalyzer = SleepNadirAnalyzer(deps.calculator),
                        coverageValidator = HrCoverageValidator(),
                        sleepModifierResolver = sleepModifierResolver,
                    ),
            )
        return ReadinessSummaryCoordinator(
            dataLoader = deps.dataLoader,
            seriesLoader = deps.seriesLoader,
            scoringHistoryRepository = deps.historyRepo,
            baselineComputer = deps.baselineComputer,
            buildLoadSeriesUseCase = BuildLoadSeriesUseCase(deps.calculator),
            computeSleepMetricsUseCase = computeSleepMetricsUseCase,
            resolveDailyBaselinesUseCase = resolveDailyBaselinesUseCase,
            assembleDailySummaryUseCase = AssembleDailySummaryUseCase(),
        )
    }
}
