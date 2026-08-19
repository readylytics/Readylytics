package app.readylytics.health.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.RoomTransactionRunner
import app.readylytics.health.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.SleepStageEntity
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.data.repository.ScoringDayDataLoader
import app.readylytics.health.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.domain.heartrate.ZoneThresholds
import app.readylytics.health.domain.model.RecordType
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.domain.scoring.BaselineComputer
import app.readylytics.health.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.domain.scoring.ScoringConfigFactory
import app.readylytics.health.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.domain.scoring.strategies.SleepScoringStrategy
import app.readylytics.health.domain.security.EncryptionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase-0 benchmark scaffolding (WP-02b): records current-implementation baseline numbers for the
 * three hot paths the remediation plan's later phases target -- ingest-batch persistence
 * (HC-001/PERF-004), session-link reconcile (PERF-001), and per-day recompute (PERF-002). These
 * numbers are the "before" side later phases' benchmarks compare against; see
 * internal-docs/plans/PHASE_0_BENCHMARK_BASELINE.md for how to run this and where results land.
 *
 * Uses `androidx.benchmark:benchmark-junit4` (in-process microbenchmark), not Macrobenchmark --
 * the `:benchmark` Gradle module is `com.android.test` (black-box UI automation via
 * `StartupBenchmark`) and has no visibility into internal classes like `ScoringRepositoryImpl` or
 * `SessionLinkReconcilerImpl`. This file intentionally does not share code with
 * `SyntheticDatasetGenerator`/`GoldenFixtureDataBuilder` (in `app/src/test`, a separate JVM-only
 * Gradle source set `app/src/androidTest` cannot reference) -- it seeds its own smaller, self
 * -contained dataset inline.
 *
 * Runs on a connected device/emulator only (`./gradlew :database-benchmark:connectedBenchmarkAndroidTest`;
 * this module already has the `androidx.benchmark` Gradle plugin / `benchmark` build type wired
 * up, see `database-benchmark/build.gradle.kts`).
 */
@RunWith(AndroidJUnit4::class)
class ScoringWalkForwardBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private lateinit var dbFile: File
    private lateinit var db: HealthDatabase

    @Before
    fun setUp() {
        dbFile = File.createTempFile("scoring-benchmark", ".db")
        dbFile.delete()
        db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                    dbFile.absolutePath,
                ).build()
    }

    @After
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    /** Ingest-shape: a single 5,000-row HR batch upsert, matching production's write-side batching. */
    @Test
    fun ingestBatchPersist() {
        val heartRateDao = db.heartRateDao()
        val baseMs =
            LocalDate
                .of(2026, 1, 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val sourceRef =
            runBlocking {
                db.sourceRecordDao().getOrCreateSourceRef("bench-batch-src", "HEART_RATE", 0L)
            }
        benchmarkRule.measureRepeated {
            val batch =
                (0 until 5_000).map { i ->
                    HeartRateRecordEntity(
                        sourceRecordRef = sourceRef,
                        timestampMs = baseMs + i * 1_000L,
                        beatsPerMinute = 60 + (i % 40),
                        recordType = RecordType.RESTING.name,
                    )
                }
            runBlocking { heartRateDao.upsertAll(batch) }
        }
    }

    /** Session-link reconcile over a 30-day window with a modest session count. */
    @Test
    fun reconcileThirtyDayWindow() {
        val startDate = LocalDate.of(2026, 1, 1)
        val endDate = startDate.plusDays(30)
        seedSessionsAndHr(startDate, endDate, sleepSessionCount = 30)

        val reconciler =
            SessionLinkReconcilerImpl(
                sleepSessionDao = db.sleepSessionDao(),
                workoutDao = db.workoutDao(),
                heartRateDao = db.heartRateDao(),
                hrvDao = db.hrvDao(),
                transactionRunner = RoomTransactionRunner(db),
            )
        val startMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs =
            endDate
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val zoneThresholds = ZoneThresholds.zoneThresholds(120, 140, 155, 168, 180)

        benchmarkRule.measureRepeated {
            runBlocking { reconciler.reconcile(startMs, endMs, zoneThresholds) }
        }
    }

    /** A single day's recompute via the real `ScoringRepositoryImpl`. */
    @Test
    fun recomputeSingleDay() {
        val targetDate = LocalDate.of(2026, 1, 15)
        seedSessionsAndHr(targetDate.minusDays(5), targetDate.plusDays(1), sleepSessionCount = 6)

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
        val computeSleepMetricsUseCase =
            ComputeSleepMetricsUseCase(
                baselineComputer = baselineComputer,
                scoringHistoryRepository = scoringHistoryRepository,
                scoringCalculator = scoringCalculator,
                scoringConfigFactory = scoringConfigFactory,
                encryptionManager = BenchmarkFakeEncryptionManager(),
                hrvResolver = CurrentNightHrvResolver(scoringHistoryRepository),
                sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(scoringHistoryRepository),
                nadirAnalyzer = SleepNadirAnalyzer(scoringCalculator),
                coverageValidator = HrCoverageValidator(),
            )
        val prefs = UserPreferences(scoringZoneId = zoneId.id)
        val settingsRepo = BenchmarkFakeSettingsRepository(prefs)
        val dataLoader =
            ScoringDayDataLoader(
                db.workoutDao(),
                db.sleepSessionDao(),
                db.dailySummaryDao(),
                db.heartRateDao(),
                db.minuteBucketDao(),
                db.weightRecordDao(),
                db.bodyFatRecordDao(),
                db.bloodPressureRecordDao(),
                db.oxygenSaturationRecordDao(),
                db.bodyTemperatureRecordDao(),
            )
        val buildLoadSeriesUseCase = BuildLoadSeriesUseCase(scoringCalculator)
        val resolveDailyBaselinesUseCase = ResolveDailyBaselinesUseCase(baselineComputer)
        val assembleDailySummaryUseCase = AssembleDailySummaryUseCase()
        val readinessSummaryCoordinator =
            ReadinessSummaryCoordinator(
                dataLoader = dataLoader,
                scoringHistoryRepository = scoringHistoryRepository,
                baselineComputer = baselineComputer,
                buildLoadSeriesUseCase = buildLoadSeriesUseCase,
                computeSleepMetricsUseCase = computeSleepMetricsUseCase,
                resolveDailyBaselinesUseCase = resolveDailyBaselinesUseCase,
                assembleDailySummaryUseCase = assembleDailySummaryUseCase,
            )

        val scoringRepository =
            ScoringRepositoryImpl(
                dataLoader = dataLoader,
                settingsRepo = settingsRepo,
                baselineComputer = baselineComputer,
                scoringConfigFactory = scoringConfigFactory,
                computeDailyTrimpUseCase = ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
                resolveDailyBaselinesUseCase = resolveDailyBaselinesUseCase,
                assembleEverydayLoadInputUseCase = AssembleEverydayLoadInputUseCase(),
                scoringHistoryRepository = scoringHistoryRepository,
                readinessSummaryCoordinator = readinessSummaryCoordinator,
                defaultDispatcher = kotlinx.coroutines.Dispatchers.Default,
            )

        benchmarkRule.measureRepeated {
            runBlocking { scoringRepository.computeAndPersistDailySummary(targetDate, steps = 8_000L) }
        }
    }

    private fun seedSessionsAndHr(
        startDate: LocalDate,
        endDate: LocalDate,
        sleepSessionCount: Int,
    ) {
        val sleepSessions = mutableListOf<SleepSessionEntity>()
        val sleepStages = mutableListOf<SleepStageEntity>()
        val heartRateRows = mutableListOf<HeartRateRecordEntity>()
        val sourceRef =
            runBlocking {
                db.sourceRecordDao().getOrCreateSourceRef("bench-sleep-src", "HEART_RATE", 0L)
            }

        var day = startDate.plusDays(1)
        var index = 0
        while (!day.isAfter(endDate) && index < sleepSessionCount) {
            val bedTime =
                day
                    .minusDays(1)
                    .atTime(23, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val wakeTime =
                day
                    .atTime(7, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val id = "bench_sleep_$index"
            sleepSessions +=
                SleepSessionEntity(
                    id = id,
                    startTime = bedTime,
                    endTime = wakeTime,
                    durationMinutes = 420,
                    efficiency = 90f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 210,
                    awakeMinutes = 30,
                )
            sleepStages +=
                SleepStageEntity(
                    sessionId = id,
                    stageType = "LIGHT",
                    startTime = bedTime,
                    endTime = wakeTime,
                    durationMinutes = 420,
                )
            var sampleTime = bedTime
            var sampleIndex = 0
            while (sampleTime < wakeTime) {
                heartRateRows +=
                    HeartRateRecordEntity(
                        sourceRecordRef = sourceRef,
                        timestampMs = sampleTime,
                        beatsPerMinute = 55 + (sampleIndex % 15),
                        recordType = RecordType.RESTING.name,
                    )
                sampleTime += 5 * 60_000L
                sampleIndex++
            }
            day = day.plusDays(1)
            index++
        }

        runBlocking {
            db.sleepSessionDao().upsertAll(sleepSessions)
            db.sleepStageDao().upsertAll(sleepStages)
            db.heartRateDao().upsertAll(heartRateRows)
        }
    }
}

private class BenchmarkFakeSettingsRepository(
    initial: UserPreferences,
) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val userPreferences: Flow<UserPreferences> = state

    override suspend fun bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory: Boolean) = Unit

    override suspend fun updateMaxHeartRate(bpm: Int) = Unit

    override suspend fun migrateDeviceSelectionIfNeeded() = Unit

    override suspend fun updateLastSyncTimestamp(timestamp: Long) = Unit

    override suspend fun updateBirthday(date: LocalDate) = Unit

    override suspend fun updateScoringVersion(version: Int) = Unit

    override suspend fun updateSleepScoreRecalcBaseline(
        weightProfile: SleepScoreWeightProfile,
        goalSleepHours: Float,
        hypersomniaOnsetPercent: Int,
    ) = Unit
}

private class BenchmarkFakeEncryptionManager : EncryptionManager {
    override fun encrypt(plaintext: String): String = plaintext

    override fun decrypt(ciphertext: String): String? = ciphertext
}
