package app.readylytics.health.benchmark

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.readylytics.health.core.database.data.local.AuthoritativeHeartRateReader
import app.readylytics.health.core.database.data.local.RoomScanStagingStore
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.healthconnect.domain.sync.HealthIngestionCoordinator
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.sync.mappers.HeartRateMapper
import app.readylytics.health.databasebenchmark.data.migration.CurrentSchemaBenchmarkFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 0 "before" measurements for
 * `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`.
 *
 * Separate from [HealthPipelineBaselineBenchmark] so that class's seven-stage measurement stays
 * comparable to its own July 2026 numbers.
 *
 * Each measurement runs at all three [BaselineScalePoints.SAMPLE_COUNTS]: peak heap is expected flat,
 * wall time roughly linear, and one scale point cannot distinguish those two.
 *
 * Peak-heap figures are `Runtime.totalMemory() - freeMemory()` deltas and include GC noise. They
 * answer flat-versus-linear; they are not absolute allocation figures.
 *
 * `@LargeTest`, so the routine sweep skips these. Run with `scripts/run-database-benchmarks.sh` --
 * also the only path that delivers `androidx.benchmark.suppressErrors`, since AGP drops dotted keys.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class Phase0BaselineBenchmark {
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

    /** PERF-102 before-number: one SQL statement per row today. */
    @Test
    fun measureHeartRateUpsertAtEachScalePoint() =
        runBlocking {
            for (total in BaselineScalePoints.SAMPLE_COUNTS) {
                val callback = CountingQueryCallback()
                val database = fixture.createDatabase("current-benchmark-p0-upsert-$total.db", true, callback)
                val txRunner = CountingTransactionRunner(RoomTransactionRunner(database))
                val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, txRunner)

                callback.reset()
                txRunner.reset()
                val before = usedHeapBytes()
                var peak = before
                val (_, nanos) =
                    measured {
                        BaselineScalePoints.densePages(total).forEach { page ->
                            store.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
                            peak = maxOf(peak, usedHeapBytes())
                        }
                    }

                logMetric(
                    Metric(
                        "hr_upsert",
                        total,
                        nanos,
                        callback.statementCount,
                        txRunner.transactionCount,
                        peak - before,
                    ),
                )
                assertTrue("upsert must execute statements", callback.statementCount > 0)
                assertEquals("every sample must be persisted", total, database.heartRateDao().count())
                database.close()
            }
        }

    /**
     * Re-ingesting identical data must change no rows -- `conflictTargetedUpsert`'s no-op suppression
     * predicate, which PERF-102's multi-row rewrite has to preserve.
     */
    @Test
    fun measureIdempotentReIngest() =
        runBlocking {
            val total = BaselineScalePoints.SAMPLE_COUNTS.first()
            val callback = CountingQueryCallback()
            val database = fixture.createDatabase("current-benchmark-p0-reingest.db", true, callback)
            val store =
                ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, RoomTransactionRunner(database))

            BaselineScalePoints.densePages(total).forEach { page ->
                store.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
            }
            val rowsAfterFirst = database.heartRateDao().count()

            callback.reset()
            val (_, nanos) =
                measured {
                    BaselineScalePoints.densePages(total).forEach { page ->
                        store.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
                    }
                }

            logMetric(Metric("hr_reingest", total, nanos, callback.statementCount))
            assertEquals("re-ingest must not change the row count", rowsAfterFirst, database.heartRateDao().count())
            database.close()
        }

    /**
     * PERF-101 before-number: one unfiltered range read across a 45-day cluster span, the shape
     * `fetchHeartRateSamplesByWorkout` produces at `CLUSTER_SPAN_GUARD_MS`.
     *
     * The last assertion deliberately asserts the DEFECT exists -- it is the characterization test
     * PERF-101's fix must invert. A test passing both before and after a fix proves nothing.
     */
    @Test
    fun measureUnfilteredRangeReadAtClusterSpan() =
        runBlocking {
            for (total in BaselineScalePoints.SAMPLE_COUNTS) {
                val database = fixture.createDatabase("current-benchmark-p0-range-$total.db", useSqlCipher = true)
                val reader = AuthoritativeHeartRateReader(database.heartRateDao(), database.minuteBucketDao())
                val recorder = MaxResultSizeRecorder()
                val before = usedHeapBytes()
                var rowCount = 0
                var nanos = 0L

                // Catching OutOfMemoryError is normally indefensible. Here the OOM IS the datum:
                // PERF-101 claims this read materialises the whole range, and "the device cannot hold
                // it" is the strongest form of that evidence. Recording it as EXTRA=-1 keeps the
                // measurement honest without turning a measured outcome into a red suite. Measured on
                // SM-A576B with a 268 MB heap growth limit: 250k and 500k complete, 1M does not.
                val outcome =
                    runCatching {
                        val store =
                            ScoringBenchmarkHelper.createRoomHealthIngestionStore(
                                database,
                                RoomTransactionRunner(database),
                            )
                        BaselineScalePoints.densePages(total).forEach { page ->
                            store.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
                        }
                        val timed =
                            measured {
                                // Deliberately not retained past this block: holding one scale's rows
                                // while the next scale seeds would blur which allocation ran out.
                                rowCount =
                                    recorder
                                        .record("getVisibleByTimeRange") {
                                            reader.rangeIn(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2).rawSamples
                                        }.size
                            }
                        nanos = timed.second
                    }
                val peak = usedHeapBytes()

                if (outcome.isFailure && outcome.exceptionOrNull() is OutOfMemoryError) {
                    logMetric(Metric("workout_hr_fetch", total, nanos, peakHeapBytes = peak - before, extra = -1))
                    Log.w(
                        METRIC_TAG,
                        "workout_hr_fetch ran out of memory at scale $total: ${outcome.exceptionOrNull()?.message}",
                    )
                } else {
                    outcome.getOrThrow()
                    logMetric(
                        Metric(
                            "workout_hr_fetch",
                            total,
                            nanos,
                            peakHeapBytes = peak - before,
                            extra = recorder.maxSize,
                        ),
                    )
                    assertTrue("range read returned nothing at scale $total", rowCount > 0)
                    assertTrue(
                        "PERF-101 characterization: this read returns the whole range, " +
                            "max result ${recorder.maxSize} at scale $total",
                        recorder.maxSize >= total / 2,
                    )
                }
                database.close()
            }
        }

    /**
     * §9 Phase 0 step 4 / §7.3 criterion 1. Covers what the upsert and range measurements do not --
     * the coordinator's own per-window cost: concurrent bulk fetches, staging writes, sample-budget
     * slicing, deletion reconciliation. `reconcileDeletions = true` keeps staging and the anti-join
     * prune inside the measurement; the pre-existing 5,000-sample benchmark passes `false`.
     */
    @Test
    fun measureIngestWindowAtEachScalePoint() =
        runBlocking {
            val windowStart = Instant.parse("2026-01-01T00:00:00Z")
            val windowEnd = windowStart.plusSeconds(30L * 24 * 3600)
            val prefs = UserPreferences(scoringZoneId = zoneId.id)

            for (total in BaselineScalePoints.SAMPLE_COUNTS) {
                val callback = CountingQueryCallback()
                val database = fixture.createDatabase("current-benchmark-p0-ingest-$total.db", true, callback)
                val txRunner = CountingTransactionRunner(RoomTransactionRunner(database))
                val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, txRunner)
                val staging = RoomScanStagingStore(database.scanStagingDao(), database.scanTypeStateDao())
                val fakeRepo = BenchmarkFakeHealthConnectRepository(BaselineScalePoints.densePages(total))
                val coordinator = HealthIngestionCoordinator(fakeRepo, store, staging)

                callback.reset()
                txRunner.reset()
                val before = usedHeapBytes()
                var peak = before
                val (_, nanos) =
                    measured {
                        // Overrides the 3-minute production default: this measures cost, and a
                        // withTimeout firing mid-measurement would record a truncated number.
                        coordinator.ingestWindow(
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            prefs = prefs,
                            windowBudgetMs = INGEST_MEASUREMENT_BUDGET_MS,
                            reconcileDeletions = true,
                        )
                        peak = maxOf(peak, usedHeapBytes())
                    }

                logMetric(
                    Metric(
                        "ingest_window",
                        total,
                        nanos,
                        callback.statementCount,
                        txRunner.transactionCount,
                        peak - before,
                    ),
                )
                assertTrue("ingestWindow persisted nothing at scale $total", database.heartRateDao().count() > 0)
                database.close()
            }
        }

    /**
     * §11 measurement 7. Ascending day-by-day, the order both sync flows use -- day N reads day N-1,
     * so the loop cannot be reordered or parallelized without changing results.
     */
    @Test
    fun measureWalkForwardRecompute() =
        runBlocking {
            val targetDate = LocalDate.of(2026, 12, 31)
            val database = fixture.createDatabase("current-benchmark-p0-walkforward.db", useSqlCipher = true)
            ScoringBenchmarkHelper.seedCalibratedHistory(database, zoneId, targetDate, WALK_FORWARD_DAYS)
            val repository = ScoringBenchmarkHelper.createScoringRepository(database, zoneId)

            val before = usedHeapBytes()
            var peak = before
            var scored = 0
            val (_, nanos) =
                measured {
                    var day = targetDate.minusDays(WALK_FORWARD_DAYS.toLong())
                    while (!day.isAfter(targetDate)) {
                        // A day the pipeline declines to score (DayAssembly.Unavailable) is a valid
                        // outcome, not a measurement failure; count what was actually scored.
                        runCatching { repository.computeAndPersistDailySummary(day, steps = 8_000L) }
                            .onSuccess { scored++ }
                        peak = maxOf(peak, usedHeapBytes())
                        day = day.plusDays(1)
                    }
                }

            logMetric(
                Metric(
                    "walk_forward_recompute",
                    WALK_FORWARD_DAYS,
                    nanos,
                    peakHeapBytes = peak - before,
                    extra = scored,
                ),
            )
            assertTrue("walk-forward scored no days at all", scored > 0)
            database.close()
        }

    @Test
    fun changesFixtureHasTheDocumentedShape() {
        val upserts = ChangesPageFixture.upsertionRecords()
        val deletes = ChangesPageFixture.deletionIds()
        assertEquals(1_000, upserts.size)
        assertEquals(200, deletes.size)
        assertEquals("ids must be unique", 1_000, upserts.map { it.id }.distinct().size)
        assertTrue("deletions must target created records", upserts.map { it.id }.containsAll(deletes))
    }

    /**
     * OD-6 sizing input: the per-record write cost `processChangesPage` pays 1,000 times, one record
     * at a time exactly as that loop does today. The transaction-count assertion characterizes
     * HC-103's per-record shape and must invert when HC-103 is fixed.
     */
    @Test
    fun measurePerRecordChangeApplyCost() =
        runBlocking {
            val callback = CountingQueryCallback()
            val database = fixture.createDatabase("current-benchmark-p0-changes.db", true, callback)
            val txRunner = CountingTransactionRunner(RoomTransactionRunner(database))
            val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, txRunner)
            val records = ChangesPageFixture.upsertionRecords()

            callback.reset()
            txRunner.reset()
            val (_, nanos) =
                measured {
                    records.forEach { record ->
                        store.replaceHeartRateSources(
                            HeartRateMapper.mapToInputs(listOf(record), emptyList(), emptyList()),
                        )
                    }
                }

            logMetric(
                Metric(
                    "changes_1k_per_record_write",
                    ChangesPageFixture.UPSERTION_COUNT,
                    nanos,
                    callback.statementCount,
                    txRunner.transactionCount,
                ),
            )
            assertTrue(
                "HC-103 characterization: one transaction per record today, got ${txRunner.transactionCount}",
                txRunner.transactionCount >= ChangesPageFixture.UPSERTION_COUNT,
            )
            database.close()
        }

    /** The four hot query plans §7.3 criterion 7 names, SQL copied from HeartRateDao. */
    @Test
    fun recordHotQueryPlans() =
        runBlocking {
            val database = fixture.createDatabase("current-benchmark-p0-plans.db", useSqlCipher = true)
            val store =
                ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, RoomTransactionRunner(database))
            BaselineScalePoints.densePages(PLAN_SEED_SAMPLES).forEach { page ->
                store.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
            }
            database.openHelper.writableDatabase
                .query("ANALYZE")
                .close()

            hotQueries().forEach { (name, sql) ->
                val plan = QueryPlanRecorder.planOneLine(database, sql)
                Log.i(METRIC_TAG, "METRIC=query_plan, QUERY=$name, PLAN=$plan")
                assertTrue("plan for $name was empty", plan.isNotBlank())
                assertTrue("plan for $name must not scan heart_rate_records: $plan", !plan.contains("SCAN heart_rate"))
            }
            database.close()
        }

    private fun hotQueries(): Map<String, String> {
        val join =
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
                "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 "
        val visible =
            "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
                "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
                "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
                "AND b2.generation = c.visibleGeneration))) "
        val order = "ORDER BY h.timestampMs ASC, h.sourceRecordRef ASC"
        val keyset = "AND (timestampMs > 0 OR (timestampMs = 0 AND sourceRecordRef > 0)) "
        return mapOf(
            "getVisibleByTimeRange" to
                "SELECT h.* FROM heart_rate_records h $join" +
                "WHERE h.timestampMs >= 0 AND h.timestampMs <= 1 $visible$order",
            "getVisibleByTypeAndTimeRange" to
                "SELECT h.* FROM heart_rate_records h $join" +
                "WHERE h.recordType = 'EXERCISE' AND h.timestampMs >= 0 AND h.timestampMs <= 1 $visible$order",
            "pagePlausibleSamplesForRollup" to
                "SELECT * FROM heart_rate_records WHERE timestampMs < 1 " +
                "AND beatsPerMinute BETWEEN 30 AND 230 " + keyset +
                "ORDER BY timestampMs ASC, sourceRecordRef ASC LIMIT 1000",
            "getKeysetPage" to
                "SELECT * FROM heart_rate_records WHERE timestampMs >= 0 AND timestampMs <= 1 " + keyset +
                "ORDER BY timestampMs ASC, sourceRecordRef ASC LIMIT 1000",
        )
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** One structured logcat line per measurement; `scripts/run-database-benchmarks.sh` greps these. */
    private data class Metric(
        val name: String,
        val scale: Int,
        val nanos: Long,
        val statements: Long = 0,
        val transactions: Long = 0,
        val peakHeapBytes: Long = 0,
        val extra: Int = 0,
    )

    private fun logMetric(metric: Metric) {
        Log.i(
            METRIC_TAG,
            "METRIC=${metric.name}, SCALE=${metric.scale}, DURATION_MS=${metric.nanos / 1_000_000.0}, " +
                "STATEMENTS=${metric.statements}, TX=${metric.transactions}, " +
                "PEAK_HEAP_BYTES=${metric.peakHeapBytes}, EXTRA=${metric.extra}",
        )
    }

    private companion object {
        const val METRIC_TAG = "Phase0Metrics"
        const val INGEST_MEASUREMENT_BUDGET_MS = 30L * 60_000L
        const val WALK_FORWARD_DAYS = 365
        const val PLAN_SEED_SAMPLES = 10_000
    }
}
