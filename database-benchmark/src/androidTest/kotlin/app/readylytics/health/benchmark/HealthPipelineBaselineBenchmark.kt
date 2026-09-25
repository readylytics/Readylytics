package app.readylytics.health.benchmark

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.readylytics.health.core.database.data.local.AuthoritativeHeartRateReader
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.HealthMutationCoordinatorImpl
import app.readylytics.health.core.database.data.local.MinuteCoveragePublisher
import app.readylytics.health.core.database.data.local.RoomHealthIngestionStore
import app.readylytics.health.core.database.data.local.RoomScanStagingStore
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.core.healthconnect.domain.sync.HealthIngestionCoordinator
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.mappers.HeartRateMapper
import app.readylytics.health.databasebenchmark.data.migration.CurrentSchemaBenchmarkFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * Baseline benchmark measuring the real current-schema health data pipeline.
 * Measures each pipeline stage separately on dedicated named SQLCipher databases:
 * provider-fake read, mapping, store, full-range relink, chronological scoring,
 * rollup, and streaming backup export.
 *
 * Instruments TransactionRunner and Room query callbacks with counters only (never logging SQL values).
 *
 * `@LargeTest`: excluded from the routine `connectedDebugAndroidTest` sweep by this module's
 * `notAnnotation` filter (see `database-benchmark/build.gradle.kts`). Benchmarks produce
 * meaningless numbers on a shared/debuggable runner, and this module carries pre-existing test
 * failures that were invisible while its instrumentation could not start at all. Opt in with
 * `-Pandroid.testInstrumentationRunnerArguments.annotation=androidx.test.filters.LargeTest`.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class HealthPipelineBaselineBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private lateinit var fixture: CurrentSchemaBenchmarkFixture
    private lateinit var queryCounter: CountingQueryCallback
    private lateinit var countingTxRunner: CountingTransactionRunner
    private lateinit var db: HealthDatabase
    private lateinit var store: RoomHealthIngestionStore

    @Before
    fun setUp() {
        fixture = CurrentSchemaBenchmarkFixture(ApplicationProvider.getApplicationContext())
        queryCounter = CountingQueryCallback()
        // The counter must be attached to the database it measures. Before this it was constructed
        // and asserted on but never wired to `db`, so `statementCount` was always 0 and
        // `measurePipelineStagesSeparately` could not pass -- invisible while the module's tests
        // were not running at all.
        db = fixture.createDatabase("current-benchmark-pipeline-default.db", true, queryCounter)
        countingTxRunner = CountingTransactionRunner(RoomTransactionRunner(db))
        store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(db, countingTxRunner)
    }

    @After
    fun tearDown() {
        fixture.cleanUp()
    }

    /** Step 1 shape assertions validating parent distribution vs sample distribution. */
    @Test
    fun verifyFixtureDatasetShapes() {
        val parents = HealthParentFixture.pages(1_000_001, 1, 1000)
        assertEquals(1_000_001, parents.sumOf { it.size })
        val dense = HealthParentFixture.pages(1001, 1000, 100)
        assertEquals(1_001_000, dense.sumOf { page -> page.sumOf { it.samples.size } })

        // Phase 0: each scale point must produce exactly its nominal sample count in both shapes,
        // so a measurement at 250k/500k/1M is comparing like with like.
        for (total in BaselineScalePoints.SAMPLE_COUNTS) {
            assertEquals(
                total,
                BaselineScalePoints.densePages(total).sumOf { page -> page.sumOf { it.samples.size } },
            )
            assertEquals(
                total,
                BaselineScalePoints.sparsePages(total).sumOf { page -> page.sumOf { it.samples.size } },
            )
        }
    }

    /**
     * Review Focus 3: a 1M-row SQLCipher template plus one copy per benchmark iteration can exhaust
     * device storage or the instrumentation timeout. Measure the template once and fail with a clear
     * message rather than letting a later benchmark die opaquely.
     */
    @Test
    fun verifyLargestFixtureFitsOnDevice() =
        runBlocking {
            val largest = BaselineScalePoints.SAMPLE_COUNTS.max()
            val template =
                fixture.createTemplate("scale-guard", useSqlCipher = true) { database ->
                    val guardStore =
                        ScoringBenchmarkHelper.createRoomHealthIngestionStore(
                            database,
                            RoomTransactionRunner(database),
                        )
                    BaselineScalePoints.densePages(largest).forEach { page ->
                        guardStore.replaceHeartRateSources(
                            HeartRateMapper.mapToInputs(page, emptyList(), emptyList()),
                        )
                    }
                }
            val bytes = template.file.length()
            Log.i("Phase0Metrics", "METRIC=fixture_template_bytes, SCALE=$largest, VALUE=$bytes")
            assertTrue(
                "1M template is ${bytes / 1_000_000}MB; free space or timeout budget must be re-checked",
                bytes in 1..2_000_000_000,
            )
            fixture.delete(template)
        }

    /**
     * Benchmark feeding parent pages through HealthConnectRepository and HealthIngestionCoordinator
     * on isolated SQLCipher copies per repetition.
     */
    @Test
    fun benchmarkCoordinatorEndToEndIngestion() {
        val windowStart = Instant.parse("2026-01-01T00:00:00Z")
        val windowEnd = windowStart.plusSeconds(30L * 24 * 3600)
        val prefs = UserPreferences(scoringZoneId = zoneId.id)
        val template = fixture.createTemplate("coordinator-template", useSqlCipher = true)
        var iteration = 0

        benchmarkRule.measureRepeated {
            val iterCounter = CountingQueryCallback()
            val instance =
                runWithTimingDisabled {
                    fixture.copyTemplate(template, "coordinator-iter-${iteration++}", iterCounter)
                }
            val iterTx = CountingTransactionRunner(RoomTransactionRunner(instance.database))
            val iterStore = ScoringBenchmarkHelper.createRoomHealthIngestionStore(instance.database, iterTx)
            val fakeRepo =
                BenchmarkFakeHealthConnectRepository(
                    pagesSequence = HealthParentFixture.pages(parentCount = 500, samplesPerParent = 10, pageSize = 50),
                )
            val coordinator =
                HealthIngestionCoordinator(
                    fakeRepo,
                    iterStore,
                    RoomScanStagingStore(
                        instance.database.scanStagingDao(),
                        instance.database.scanTypeStateDao(),
                    ),
                )

            runBlocking {
                coordinator.ingestWindow(windowStart, windowEnd, prefs, reconcileDeletions = false)
            }

            runWithTimingDisabled {
                Log.i(
                    "BenchmarkMetrics",
                    "coordinator_ingest: tx=${iterTx.transactionCount}, statements=${iterCounter.statementCount}",
                )
                fixture.delete(instance)
            }
        }
    }

    /** Measures all 7 pipeline stages separately and emits structured metrics for BASELINE.md. */
    @Test
    fun measurePipelineStagesSeparately() =
        runBlocking {
            // Stage 1: Provider-fake read
            val (readPages, readNanos) =
                measured {
                    HealthParentFixture.pages(parentCount = 500, samplesPerParent = 10, pageSize = 50).toList()
                }
            logStageMetric("provider_read", readNanos, 0, 0)
            assertTrue("Read must produce pages", readPages.isNotEmpty())

            // Stage 2: Ingestion mapping
            val flatRecords = readPages.flatten()
            val (mappedInputs, mapNanos) =
                measured {
                    HeartRateMapper.mapToInputs(flatRecords, emptyList(), emptyList())
                }
            logStageMetric("mapping", mapNanos, 0, 0)
            assertEquals(5000, mappedInputs.sumOf { it.rows.size })

            // Stage 3: Store persistence
            queryCounter.reset()
            countingTxRunner.reset()
            val (_, storeNanos) =
                measured {
                    store.replaceHeartRateSources(mappedInputs)
                }
            logStageMetric("store", storeNanos, countingTxRunner.transactionCount, queryCounter.statementCount)
            assertTrue("Store must execute queries", queryCounter.statementCount > 0)

            // Stage 4: Full-range session link reconciliation
            val targetDate = LocalDate.of(2026, 1, 31)
            ScoringBenchmarkHelper.seedCalibratedHistory(db, zoneId, targetDate, historyDays = 30)
            val startDate = targetDate.minusDays(30)
            val startMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endMs =
                targetDate
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val zoneThresholds = ZoneThresholds.create(120, 140, 155, 168, 180)
            val reconciler =
                SessionLinkReconcilerImpl(
                    sleepSessionDao = db.sleepSessionDao(),
                    workoutDao = db.workoutDao(),
                    heartRateDao = db.heartRateDao(),
                    hrvDao = db.hrvDao(),
                    transactionRunner = countingTxRunner,
                    authoritativeReader =
                        AuthoritativeHeartRateReader(db.heartRateDao(), db.minuteBucketDao()),
                )

            queryCounter.reset()
            countingTxRunner.reset()
            val (_, relinkNanos) =
                measured {
                    reconciler.reconcile(startMs, endMs, zoneThresholds)
                }
            logStageMetric("relink", relinkNanos, countingTxRunner.transactionCount, queryCounter.statementCount)

            // Stage 5: Chronological scoring
            val scoringRepo = ScoringBenchmarkHelper.createScoringRepository(db, zoneId)
            queryCounter.reset()
            countingTxRunner.reset()
            val (_, scoringNanos) =
                measured {
                    scoringRepo.computeAndPersistDailySummary(targetDate, steps = 8_000L)
                }
            logStageMetric("scoring", scoringNanos, countingTxRunner.transactionCount, queryCounter.statementCount)

            // Stage 6: Rollup
            val rollupManager =
                DataRollupManager(
                    coordinator = HealthMutationCoordinatorImpl(db.healthMutationStateDao()),
                    minuteCoverageDao = db.minuteCoverageDao(),
                    heartRateDao = db.heartRateDao(),
                    publisher = MinuteCoveragePublisher(db.minuteBucketDao(), db.minuteCoverageDao()),
                    transactionRunner = countingTxRunner,
                )
            val cutoffMs =
                targetDate
                    .minusDays(7)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            queryCounter.reset()
            countingTxRunner.reset()
            val (_, rollupNanos) =
                measured {
                    rollupManager.rollupExpiredHotTier(cutoffMs)
                }
            logStageMetric("rollup", rollupNanos, countingTxRunner.transactionCount, queryCounter.statementCount)

            // Stage 7: Backup export streaming
            val output = ByteArrayOutputStream()
            val (_, exportNanos) =
                measured {
                    exportDatabaseTablesStreaming(db, output)
                }
            logStageMetric("backup_export", exportNanos, 0, output.size().toLong())
            assertTrue("Exported bytes must be non-empty", output.size() > 0)
        }

    private fun logStageMetric(
        stage: String,
        nanos: Long,
        transactions: Long,
        statementsOrBytes: Long,
    ) {
        val ms = nanos / 1_000_000.0
        Log.i("BaselineMetrics", "STAGE=$stage, DURATION_MS=$ms, TX=$transactions, EXTRA=$statementsOrBytes")
    }

    /** Streaming export of database tables matching BackupStreamWriter paging pattern. */
    private suspend fun exportDatabaseTablesStreaming(
        database: HealthDatabase,
        out: ByteArrayOutputStream,
    ) {
        val writer = out.bufferedWriter()
        writer.write("{\"tables\":{")

        writer.write("\"heartRateRecords\":[")
        var hrAfterTs = Long.MIN_VALUE
        var hrAfterRef = Long.MIN_VALUE
        var first = true
        while (true) {
            val page = database.heartRateDao().pageAfter(0, hrAfterTs, hrAfterRef, 500)
            if (page.isEmpty()) break
            for (row in page) {
                if (!first) writer.write(",")
                writer.write("{\"ts\":${row.timestampMs},\"bpm\":${row.beatsPerMinute}}")
                first = false
                hrAfterTs = row.timestampMs
                hrAfterRef = row.sourceRecordRef
            }
        }
        writer.write("],")

        writer.write("\"dailySummaries\":[")
        val summaries = database.dailySummaryDao().getAllSummaries()
        var sFirst = true
        for (summary in summaries) {
            if (!sFirst) writer.write(",")
            writer.write(
                "{\"day\":${summary.dateMidnightMs},\"score\":${summary.readinessWorkoutOnly}}",
            )
            sFirst = false
        }
        writer.write("]}}")
        writer.flush()
    }
}

/** Monotonic nanosecond timing helper. */
suspend fun <T> measured(block: suspend () -> T): Pair<T, Long> {
    val started = SystemClock.elapsedRealtimeNanos()
    val result = block()
    return result to (SystemClock.elapsedRealtimeNanos() - started)
}

/** Transaction runner with counter instrumentation only. */
class CountingTransactionRunner(
    private val delegate: TransactionRunner,
) : TransactionRunner {
    private val counter = AtomicLong(0)
    val transactionCount: Long get() = counter.get()

    fun reset() {
        counter.set(0)
    }

    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        counter.incrementAndGet()
        return delegate.runInTransaction(block)
    }
}

/** Room query callback counting executed statements without logging bound values or IDs. */
class CountingQueryCallback : RoomDatabase.QueryCallback {
    private val counter = AtomicLong(0)
    val statementCount: Long get() = counter.get()

    fun reset() {
        counter.set(0)
    }

    override fun onQuery(
        sqlQuery: String,
        bindArgs: List<Any?>,
    ) {
        counter.incrementAndGet()
    }
}
