package app.readylytics.health.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.sync.HeartRateInput
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
 * Exercises dense current store (RoomHealthIngestionStore) and actual calibrated scoring.
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

    /** Ingest-shape: a single 5,000-row HR batch upsert through current RoomHealthIngestionStore. */
    @Test
    fun ingestBatchPersist() {
        val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(db)
        val baseMs =
            LocalDate
                .of(2026, 1, 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()

        benchmarkRule.measureRepeated {
            val batch =
                (0 until 5_000).map { i ->
                    val timestamp = baseMs + i * 1_000L
                    HeartRateInput(
                        id = "bench_batch_${timestamp}_$i",
                        timestampMs = timestamp,
                        beatsPerMinute = 60 + (i % 40),
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "fixture-origin-0",
                    )
                }
            runBlocking { store.persistHeartRateSamples(batch) }
        }
    }

    /** Session-link reconcile over a 30-day window with a realistic session count. */
    @Test
    fun reconcileThirtyDayWindow() {
        val startDate = LocalDate.of(2026, 1, 1)
        val targetDate = startDate.plusDays(30)
        ScoringBenchmarkHelper.seedCalibratedHistory(db, zoneId, targetDate, historyDays = 30)

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
            targetDate
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val zoneThresholds = ZoneThresholds.create(120, 140, 155, 168, 180)

        benchmarkRule.measureRepeated {
            runBlocking { reconciler.reconcile(startMs, endMs, zoneThresholds) }
        }
    }

    /** A single day's recompute via the real `ScoringRepositoryImpl` traversing calibrated scoring. */
    @Test
    fun recomputeSingleDay() {
        val targetDate = LocalDate.of(2026, 1, 31)
        ScoringBenchmarkHelper.seedCalibratedHistory(db, zoneId, targetDate, historyDays = 30)

        val scoringRepository = ScoringBenchmarkHelper.createScoringRepository(db, zoneId)

        benchmarkRule.measureRepeated {
            runBlocking { scoringRepository.computeAndPersistDailySummary(targetDate, steps = 8_000L) }
        }
    }
}
