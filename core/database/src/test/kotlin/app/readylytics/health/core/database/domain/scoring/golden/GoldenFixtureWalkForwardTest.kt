package app.readylytics.health.core.database.domain.scoring.golden
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.database.data.repository.BodyMetricsDataLoader
import app.readylytics.health.core.database.data.repository.MorningRecommendationDependencies
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringHeartRateDataLoader
import app.readylytics.health.core.database.data.repository.ScoringSeriesLoader
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.SleepSessionRepositoryImpl
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
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
import app.readylytics.health.core.scoring.domain.scoring.SleepMetricsCollaborators
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import app.readylytics.health.core.database.data.repository.ScoringDayUseCases
import app.readylytics.health.core.database.data.repository.ScoringDataLoaders

/**
 * Golden regression lock (WP-01 of the architecture/HC/scoring remediation plan): seeds ~2 years
 * of realistic data, runs the *real* production recompute path (RECONCILE, then a per-day
 * RECOMPUTE walk-forward, mirroring `ResyncRangeUseCase`'s last two phases -- the PRUNE phase is
 * skipped since it only matters for a mid-history selected-source-device switch, which is not one
 * of this fixture's scenarios), and snapshots every produced [DailySummaryEntity] against a
 * checked-in golden JSON file.
 *
 * The whole object graph below is real (no mocks): only [FakeSettingsRepository] (a fixed
 * preferences snapshot) and [FakeEncryptionManager] (identity passthrough, never exercised since
 * the fixture never sets `circadianThresholdOverride`) stand in for framework-provided
 * collaborators. Every DAO, scoring strategy, and use-case is the production implementation
 * running against a real Robolectric in-memory Room database.
 *
 * To regenerate the golden file after an intentional scoring change, run:
 *   ./gradlew :core:database:testDebugUnitTest --tests "*.GoldenFixtureWalkForwardTest" \
 *     -Dupdate.golden=true
 * then inspect the diff of `core/database/src/test/resources/golden/scoring_walk_forward_golden.json`
 * before committing. Without that flag the suite *asserts* against the checked-in fixture -- never
 * hardcode the flag to `true`, or this lock silently stops being able to fail.
 *
 * The comparison excludes `workoutRecommendationJson`; see [GoldenEntityJson] for why.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GoldenFixtureWalkForwardTest {
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private val startDate: LocalDate = LocalDate.of(2024, 6, 1)
    private val endDate: LocalDate = LocalDate.of(2026, 5, 31)

    private lateinit var db: HealthDatabase

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `walk-forward recompute matches golden fixture`() =
        runTest(timeout = 10.minutes) {
            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    installDate =
                        startDate
                            .minusDays(1)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    age = 35,
                )

            val buildResult = GoldenFixtureDataBuilder(zoneId).build(db, startDate, endDate)

            val zoneThresholds =
                ZoneThresholds.create(
                    prefs.zone1MinBpm,
                    prefs.zone1MaxBpm,
                    prefs.zone2MaxBpm,
                    prefs.zone3MaxBpm,
                    prefs.zone4MaxBpm,
                )
            val reconciler =
                SessionLinkReconcilerImpl(
                    sleepSessionDao = db.sleepSessionDao(),
                    workoutDao = db.workoutDao(),
                    heartRateDao = db.heartRateDao(),
                    hrvDao = db.hrvDao(),
                    transactionRunner = RoomTransactionRunner(db),
                )
            val reconcileStartMs =
                startDate
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val reconcileEndMs =
                endDate
                    .plusDays(2)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            reconciler.reconcile(reconcileStartMs, reconcileEndMs, zoneThresholds)

            val scoringHistoryRepository =
                ScoringHistoryRepositoryImpl(
                    db.heartRateDao(),
                    db.hrvDao(),
                    db.sleepSessionDao(),
                    db.dailySummaryDao(),
                    db.minuteBucketDao(),
                )
            val loadScoringStrategy = LoadScoringStrategy()
            val scoringCalculator =
                CompositeScoringCalculator(
                    sleepStrategy = SleepScoringStrategy(loadScoringStrategy),
                    rasStrategy = RasScoringStrategy(),
                    loadStrategy = loadScoringStrategy,
                )
            val baselineComputer = BaselineComputer(scoringHistoryRepository, scoringCalculator)
            val scoringConfigFactory = ScoringConfigFactory()
            val sleepSessionRepository = SleepSessionRepositoryImpl(db.sleepSessionDao(), db.sleepStageDao())
            val settingsRepo = FakeSettingsRepository(prefs)
            val circadianConsistencyRepository =
                CircadianConsistencyRepository(sleepSessionRepository, settingsRepo, FakeEncryptionManager())
            val sleepModifierResolver = SleepModifierResolver(sleepSessionRepository, circadianConsistencyRepository)
            val computeSleepMetricsUseCase =
                ComputeSleepMetricsUseCase(
                    collaborators =
                        SleepMetricsCollaborators(
                            baselineComputer = baselineComputer,
                            scoringHistoryRepository = scoringHistoryRepository,
                            scoringCalculator = scoringCalculator,
                            scoringConfigFactory = scoringConfigFactory,
                            encryptionManager = FakeEncryptionManager(),
                            hrvResolver = CurrentNightHrvResolver(scoringHistoryRepository),
                            sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(scoringHistoryRepository),
                            nadirAnalyzer = SleepNadirAnalyzer(scoringCalculator),
                            coverageValidator = HrCoverageValidator(),
                            sleepModifierResolver = sleepModifierResolver,
                        ),
                )
            val dataLoader =
                ScoringDayDataLoader(db.workoutDao(), db.sleepSessionDao(), db.dailySummaryDao())
            val bodyMetricsDataLoader =
                BodyMetricsDataLoader(
                    db.weightRecordDao(), db.bodyFatRecordDao(), db.bloodPressureRecordDao(),
                    db.oxygenSaturationRecordDao(), db.bodyTemperatureRecordDao(),
                    db.vo2MaxRecordDao(),
                )
            val seriesLoader = ScoringSeriesLoader(db.workoutDao(), db.dailySummaryDao())
            val heartRateDataLoader = ScoringHeartRateDataLoader(db.heartRateDao(), db.minuteBucketDao())
            val buildLoadSeriesUseCase = BuildLoadSeriesUseCase(scoringCalculator)
            val resolveDailyBaselinesUseCase = ResolveDailyBaselinesUseCase(baselineComputer)
            val assembleDailySummaryUseCase = AssembleDailySummaryUseCase()
            val readinessSummaryCoordinator =
                ReadinessSummaryCoordinator(
                    dataLoader = dataLoader,
                    seriesLoader = seriesLoader,
                    scoringHistoryRepository = scoringHistoryRepository,
                    baselineComputer = baselineComputer,
                    buildLoadSeriesUseCase = buildLoadSeriesUseCase,
                    computeSleepMetricsUseCase = computeSleepMetricsUseCase,
                    resolveDailyBaselinesUseCase = resolveDailyBaselinesUseCase,
                    assembleDailySummaryUseCase = assembleDailySummaryUseCase,
                )

            val scoringRepository =
                ScoringRepositoryImpl(
                    loaders = ScoringDataLoaders(
                        dataLoader,
                        bodyMetricsDataLoader,
                        seriesLoader,
                        heartRateDataLoader,
                    ),
                    settingsRepo = settingsRepo,
                    baselineComputer = baselineComputer,
                    scoringConfigFactory = scoringConfigFactory,
                    useCases =
                        ScoringDayUseCases(
                            ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
                            ComputeResidualFatigueUseCase(),
                            resolveDailyBaselinesUseCase,
                            AssembleEverydayLoadInputUseCase(),
                        ComputeTrainingReadinessUseCase(scoringCalculator),
                            UthVo2MaxCalculator(),
                            Vo2MaxSourceResolver(),
                        ),
                    scoringHistoryRepository = scoringHistoryRepository,
                    readinessSummaryCoordinator = readinessSummaryCoordinator,
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
            // WP-11/HC-006 fix: this fixture's stage-less-night scenario (`stageLessNightDate`)
            // seeds a SleepSessionEntity with durationMinutes = 0 directly (mirroring a session
            // ingested before ScoringRepositoryImpl.toSleepDaySegment's defensive raw-span
            // fallback landed). Previously SleepDaySegment's `durationMinutes > 0` invariant threw
            // for every day whose aggregation window still included that session -- empirically
            // ~8 weeks after it, not just its own day -- which production silently swallowed into
            // a missing DailySummaryEntity for that whole window. The fallback (here and in
            // SleepDataMapper for freshly-ingested sessions) means every day now scores normally;
            // ComputeSleepMetricsUseCase's `stagesSuspicious` reweight (Architecture -> 0%, Duration
            // -> 75%) applies for the stage-less night itself.
            var day = startDate
            while (!day.isAfter(endDate)) {
                scoringRepository.computeAndPersistDailySummary(day, buildResult.stepsByDate[day])
                day = day.plusDays(1)
            }

            val summaries = db.dailySummaryDao().getAllSummaries().sortedBy { it.dateMidnightMs }
            val actualJson = GoldenEntityJson.encode(summaries)

            if (UPDATE_GOLDEN) {
                val target = goldenWriteTarget()
                target.parentFile?.mkdirs()
                target.writeText(actualJson)
                println("Golden fixture written to ${target.absolutePath}")
                return@runTest
            }

            val expectedJson = loadGoldenJsonOrNull()
            assertTrue(
                expectedJson != null,
                "No golden fixture found at ${GOLDEN_RESOURCE_RELATIVE_PATH}. Generate it first with " +
                    "-Dupdate.golden=true, inspect the diff, then commit it.",
            )
            kotlin.test.assertEquals(expectedJson, actualJson, "Walk-forward output diverged from the golden fixture")
        }

    private companion object {
        const val GOLDEN_RESOURCE_RELATIVE_PATH = "golden/scoring_walk_forward_golden.json"

        /**
         * Regeneration is opt-in via `-Dupdate.golden=true` (forwarded to the test JVM by
         * `core/database/build.gradle.kts`). It must never be hardcoded to `true`: a suite that
         * always rewrites its own fixture and returns before asserting can never fail, which
         * silently disables the scoring-regression lock this fixture exists to provide.
         */
        val UPDATE_GOLDEN: Boolean = System.getProperty("update.golden") == "true"
    }

    private fun goldenFileCandidates(): List<File> =
        listOf(
            File("src/test/resources/golden/scoring_walk_forward_golden.json"),
            File("core/database/src/test/resources/golden/scoring_walk_forward_golden.json"),
            File("../core/database/src/test/resources/golden/scoring_walk_forward_golden.json"),
            File("app/src/test/resources/golden/scoring_walk_forward_golden.json"),
            File("../app/src/test/resources/golden/scoring_walk_forward_golden.json"),
        )

    private fun loadGoldenJsonOrNull(): String? {
        javaClass.classLoader?.getResourceAsStream(GOLDEN_RESOURCE_RELATIVE_PATH)?.use {
            return it.bufferedReader().readText()
        }
        return goldenFileCandidates().firstOrNull { it.exists() }?.readText()
    }

    private fun goldenWriteTarget(): File =
        goldenFileCandidates().firstOrNull { it.parentFile?.exists() == true }
            ?: goldenFileCandidates().first()
}
