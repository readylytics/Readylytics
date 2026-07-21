package app.readylytics.health.domain.scoring.golden

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.RoomTransactionRunner
import app.readylytics.health.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.domain.heartrate.ZoneThresholds
import app.readylytics.health.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.domain.scoring.BaselineComputer
import app.readylytics.health.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.domain.scoring.ScoringConfigFactory
import app.readylytics.health.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.domain.scoring.strategies.SleepScoringStrategy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertTrue

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
 *   ./gradlew :app:testDebugUnitTest --tests "*.GoldenFixtureWalkForwardTest" -Dupdate.golden=true
 * then inspect the diff of `app/src/test/resources/golden/scoring_walk_forward_golden.json` before
 * committing.
 *
 * **Known-stale as of WP-10 (SCORE-001/SCORE-005 TRIMP unification):** `ScoringRepositoryImpl`
 * now persists `WorkoutRecordEntity.modelTrimp` per workout and `WorkoutDao.getTrimpPoints` reads
 * `COALESCE(modelTrimp, trimp)`, so the workout-only ATL/CTL series in the checked-in golden JSON
 * (computed under the old zone-weighted-only read) is expected to diverge from a fresh run wherever
 * this fixture's default `BANISTER` model produces a different per-workout value than the
 * zone-weighted formula.
 *
 * **Known-stale as of WP-11 (HC-006 stage-less-night fallback):** the stage-less-night scenario
 * (`stageLessNightDate`) no longer throws `IllegalArgumentException("durationMinutes must be >
 * 0")` -- see `toSleepDaySegment`'s defensive raw-span fallback in `ScoringRepositoryImpl` and
 * `BaselineComputer`. The ~57 days previously left unscored by that exception (its own night plus
 * every day whose baseline/aggregation lookback still included it) now produce normally-scored
 * rows, so the checked-in golden JSON is missing entries for that whole window.
 *
 * This environment has no working Gradle (see `internal-docs/plans/PHASE_1_IMPLEMENTATION_PLAN.md`),
 * so the fixture could not be regenerated here for either change above -- the next CI/Gradle-capable
 * pass must run the `-Dupdate.golden=true` regeneration above, review the combined score deltas it
 * produces, and commit the refreshed JSON separately per the remediation plan's migration-risk
 * requirement.
 */
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
        runTest {
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
                ZoneThresholds.zoneThresholds(
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
                ScoringHistoryRepositoryImpl(db.heartRateDao(), db.hrvDao(), db.sleepSessionDao(), db.dailySummaryDao())
            val loadScoringStrategy = LoadScoringStrategy()
            val scoringCalculator =
                CompositeScoringCalculator(
                    sleepStrategy = SleepScoringStrategy(loadScoringStrategy),
                    rasStrategy = RasScoringStrategy(),
                    loadStrategy = loadScoringStrategy,
                )
            val baselineComputer = BaselineComputer(scoringHistoryRepository, scoringCalculator)
            val scoringConfigFactory = ScoringConfigFactory()
            val computeSleepMetricsUseCase =
                ComputeSleepMetricsUseCase(
                    baselineComputer = baselineComputer,
                    dailySummaryDao = db.dailySummaryDao(),
                    heartRateDao = db.heartRateDao(),
                    scoringCalculator = scoringCalculator,
                    scoringConfigFactory = scoringConfigFactory,
                    encryptionManager = FakeEncryptionManager(),
                    hrvResolver = CurrentNightHrvResolver(scoringHistoryRepository),
                    sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(scoringHistoryRepository),
                    nadirAnalyzer = SleepNadirAnalyzer(scoringCalculator),
                    coverageValidator = HrCoverageValidator(),
                )

            val settingsRepo = FakeSettingsRepository(prefs)
            val scoringRepository =
                ScoringRepositoryImpl(
                    workoutDao = db.workoutDao(),
                    sleepSessionDao = db.sleepSessionDao(),
                    dailySummaryDao = db.dailySummaryDao(),
                    settingsRepo = settingsRepo,
                    scoringCalculator = scoringCalculator,
                    baselineComputer = baselineComputer,
                    buildLoadSeriesUseCase = BuildLoadSeriesUseCase(scoringCalculator),
                    assembleEverydayLoadInputUseCase = AssembleEverydayLoadInputUseCase(),
                    computeSleepMetricsUseCase = computeSleepMetricsUseCase,
                    scoringConfigFactory = scoringConfigFactory,
                    computeWorkoutTrimpUseCase = ComputeWorkoutTrimpUseCase(),
                    heartRateDao = db.heartRateDao(),
                    weightRecordDao = db.weightRecordDao(),
                    bodyFatRecordDao = db.bodyFatRecordDao(),
                    bloodPressureRecordDao = db.bloodPressureRecordDao(),
                    oxygenSaturationRecordDao = db.oxygenSaturationRecordDao(),
                    sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(scoringHistoryRepository),
                    scoringHistoryRepository = scoringHistoryRepository,
                    defaultDispatcher = UnconfinedTestDispatcher(),
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
            val actualJson =
                Json { prettyPrint = true }.encodeToString(ListSerializer(DailySummaryEntity.serializer()), summaries)

            if (System.getProperty("update.golden") == "true") {
                val target = goldenWriteTarget()
                target.parentFile?.mkdirs()
                target.writeText(actualJson)
                println("Golden fixture written to ${target.absolutePath} (${summaries.size} days)")
                return@runTest
            }

            val expectedJson = loadGoldenJsonOrNull()
            assertTrue(
                expectedJson != null,
                "No golden fixture found at ${goldenResourceRelativePath()}. Generate it first with " +
                    "-Dupdate.golden=true, inspect the diff, then commit it.",
            )
            kotlin.test.assertEquals(expectedJson, actualJson, "Walk-forward output diverged from the golden fixture")
        }

    private fun goldenResourceRelativePath(): String = "golden/scoring_walk_forward_golden.json"

    private fun goldenFileCandidates(): List<File> =
        listOf(
            // Gradle's testDebugUnitTest JVM runs with the :app module directory as its working
            // directory, not the repo root -- this must be first, or a write falls back to the
            // next candidate and creates a spurious app/app/... directory.
            File("src/test/resources/golden/scoring_walk_forward_golden.json"),
            File("app/src/test/resources/golden/scoring_walk_forward_golden.json"),
            File("../app/src/test/resources/golden/scoring_walk_forward_golden.json"),
        )

    private fun loadGoldenJsonOrNull(): String? {
        javaClass.classLoader.getResourceAsStream(goldenResourceRelativePath())?.use {
            return it.bufferedReader().readText()
        }
        return goldenFileCandidates().firstOrNull { it.exists() }?.readText()
    }

    private fun goldenWriteTarget(): File =
        goldenFileCandidates().firstOrNull { it.parentFile?.exists() == true }
            ?: goldenFileCandidates().first()
}
