package app.readylytics.health.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.AuthoritativeHeartRateReader
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.databasebenchmark.data.migration.CurrentSchemaBenchmarkFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Records current-implementation baseline numbers for the three hot paths:
 * ingest-batch persistence (HC-001/PERF-004), session-link reconcile (PERF-001),
 * and per-day recompute (PERF-002).
 *
 * Exercises dedicated named SQLCipher databases with copy-on-write isolation per repetition
 * and pre-allocated batches outside measureRepeated.
 */
@RunWith(AndroidJUnit4::class)
class ScoringWalkForwardBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private lateinit var fixture: CurrentSchemaBenchmarkFixture

    @Before
    fun setUp() {
        fixture = CurrentSchemaBenchmarkFixture(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        fixture.cleanUp()
    }

    /** Ingest-shape: a single 5,000-row HR batch upsert through RoomHealthIngestionStore on fresh SQLCipher copy. */
    @Test
    fun ingestBatchPersist() {
        val baseMs =
            LocalDate
                .of(2026, 1, 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()

        // Pre-allocate batch outside measureRepeated to eliminate synthetic allocation overhead
        val preallocatedBatch =
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

        val preallocatedPayload =
            listOf(
                SourcePayload(
                    source = SourceMetadata("bench_batch", baseMs, baseMs + 5_000 * 1_000L),
                    rows = preallocatedBatch.map { it.copy(sourceId = "bench_batch") },
                ),
            )

        val template = fixture.createTemplate("ingest-template", useSqlCipher = true)
        var iteration = 0

        benchmarkRule.measureRepeated {
            val instance =
                runWithTimingDisabled {
                    fixture.copyTemplate(template, "ingest-iter-${iteration++}")
                }
            val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(instance.database)
            runBlocking { store.replaceHeartRateSources(preallocatedPayload) }
            runWithTimingDisabled {
                fixture.delete(instance)
            }
        }
    }

    /** Session-link reconcile over a 30-day window on fresh SQLCipher copy. */
    @Test
    fun reconcileThirtyDayWindow() {
        val startDate = LocalDate.of(2026, 1, 1)
        val targetDate = startDate.plusDays(30)
        val startMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs =
            targetDate
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val zoneThresholds = ZoneThresholds.create(120, 140, 155, 168, 180)

        val template =
            fixture.createTemplate("reconcile-template", useSqlCipher = true) { db ->
                ScoringBenchmarkHelper.seedCalibratedHistory(db, zoneId, targetDate, historyDays = 30)
            }
        var iteration = 0

        benchmarkRule.measureRepeated {
            val instance =
                runWithTimingDisabled {
                    fixture.copyTemplate(template, "reconcile-iter-${iteration++}")
                }
            val reconciler =
                SessionLinkReconcilerImpl(
                    sleepSessionDao = instance.database.sleepSessionDao(),
                    workoutDao = instance.database.workoutDao(),
                    heartRateDao = instance.database.heartRateDao(),
                    hrvDao = instance.database.hrvDao(),
                    transactionRunner = RoomTransactionRunner(instance.database),
                    authoritativeReader =
                        AuthoritativeHeartRateReader(
                            instance.database.heartRateDao(),
                            instance.database.minuteBucketDao(),
                        ),
                )
            runBlocking { reconciler.reconcile(startMs, endMs, zoneThresholds) }
            runWithTimingDisabled {
                fixture.delete(instance)
            }
        }
    }

    /** A single day's recompute via real ScoringRepositoryImpl traversing calibrated scoring on SQLCipher copy. */
    @Test
    fun recomputeSingleDay() {
        val targetDate = LocalDate.of(2026, 1, 31)
        val template =
            fixture.createTemplate("scoring-template", useSqlCipher = true) { db ->
                ScoringBenchmarkHelper.seedCalibratedHistory(db, zoneId, targetDate, historyDays = 30)
            }
        var iteration = 0

        benchmarkRule.measureRepeated {
            val instance =
                runWithTimingDisabled {
                    fixture.copyTemplate(template, "scoring-iter-${iteration++}")
                }
            val scoringRepository = ScoringBenchmarkHelper.createScoringRepository(instance.database, zoneId)
            runBlocking { scoringRepository.computeAndPersistDailySummary(targetDate, steps = 8_000L) }
            runWithTimingDisabled {
                fixture.delete(instance)
            }
        }
    }
}
