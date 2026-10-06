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
import app.readylytics.health.core.database.data.repository.TypedHeartRateRepositoryImpl
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

    /**
     * WP-15: dense steps paged-ingestion benchmark -- one dense day (43,200 one-second-cadence
     * records) plus the Phase 0 250k/500k/1M scale points. Steps now streams page-by-page through
     * [HealthIngestionCoordinator] exactly like HR/HRV (HC-001), so peak retained heap is expected
     * flat across all four scale points rather than growing with record count.
     */
    @Test
    fun measureDenseStepsIngestAtEachScalePoint() =
        runBlocking {
            val windowStart = Instant.parse("2026-01-01T00:00:00Z")
            val windowEnd = windowStart.plusSeconds(30L * 24 * 3600)
            val prefs = UserPreferences(scoringZoneId = zoneId.id)

            for (total in DENSE_STEPS_SCALE_POINTS) {
                val callback = CountingQueryCallback()
                val database = fixture.createDatabase("current-benchmark-steps-$total.db", true, callback)
                val txRunner = CountingTransactionRunner(RoomTransactionRunner(database))
                val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, txRunner)
                val fakeRepo =
                    BenchmarkFakeHealthConnectRepository(
                        stepsPagesSequence = BaselineScalePoints.stepsPages(total),
                    )
                val coordinator =
                    HealthIngestionCoordinator(
                        fakeRepo,
                        store,
                        RoomScanStagingStore(database.scanStagingDao(), database.scanTypeStateDao()),
                    )

                val before = usedHeapBytes()
                var maximumObservedUsedHeap = before
                var maximumResultSetSize = 0
                fakeRepo.onStepsPageProcessed = { size ->
                    maximumResultSetSize = maxOf(maximumResultSetSize, size)
                    maximumObservedUsedHeap = maxOf(maximumObservedUsedHeap, usedHeapBytes())
                }
                val (_, nanos) =
                    measured {
                        coordinator.ingestWindow(windowStart, windowEnd, prefs, reconcileDeletions = false)
                    }

                Log.i(
                    "BaselineMetrics",
                    "METRIC=steps_ingest, SCALE=$total, DURATION_MS=${nanos / 1_000_000.0}, " +
                        "TX=${txRunner.transactionCount}, STATEMENTS=${callback.statementCount}, " +
                        "MAX_OBSERVED_USED_HEAP_DELTA=${maximumObservedUsedHeap - before}, " +
                        "MAX_RESULT_SET_SIZE=$maximumResultSetSize",
                )
                assertEquals(total, database.stepRecordDao().count())
                database.close()
            }
        }

    /**
     * Task 10 (WP-18) decision evidence. Measures the two remaining tier-visible bulk reads that are
     * NOT keyset-paged (`getVisibleByTimeRange`, `getVisibleByTypeAndTimeRange`) alongside the two
     * that already are (`pagePlausibleSamplesForRollup` from Task 8, and Task 4's typed page via
     * `TypedHeartRateRepositoryImpl.forEachByTimeRangeOfTypePage`), at the Phase 0 250k/500k/1M scale
     * points.
     *
     * The two unpaged methods are read with a single caller-bounded one-day window at every scale --
     * the real shape every production consumer uses (`ScoringHistoryRepositoryImpl`,
     * `HeartRateRepositoryImpl`, `ScoringHeartRateDataLoader`, `SessionLinkReconcilerImpl`; see
     * `AuthoritativeHeartRateReader.kt`'s Task 10 notes) -- to prove the *answer* size tracks the
     * window, not the table: `TIME_RANGE_ROWS`/`TYPE_RANGE_ROWS` must stay far below `SCALE` even at
     * 1M rows. The two already-paged methods are driven across the FULL seeded range with their real
     * production limits (5,000 for the rollup page -- `MinuteRollupStreamer.SAMPLE_PAGE_SIZE` -- and
     * 50,000 for the typed page -- `WorkoutHeartRateBatcher.MAX_CLUSTER_SAMPLES`) to prove every page
     * still respects that limit at 1M rows.
     */
    @Test
    fun measureTierVisibleReadsAtEachScalePoint() =
        runBlocking {
            val windowStart = Instant.parse("2026-01-01T00:00:00Z")
            val oneDayWindowEndMs = windowStart.toEpochMilli() + ONE_DAY_MS

            for (total in BaselineScalePoints.SAMPLE_COUNTS) {
                val database = fixture.createDatabase("current-benchmark-tiervis-$total.db", true)
                try {
                    val seedStore =
                        ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, RoomTransactionRunner(database))
                    for (page in BaselineScalePoints.densePages(total)) {
                        seedStore.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
                    }

                    val (dayRaw, dayRawNanos) =
                        measured {
                            database.heartRateDao().getVisibleByTimeRange(windowStart.toEpochMilli(), oneDayWindowEndMs)
                        }
                    val (dayTyped, dayTypedNanos) =
                        measured {
                            database
                                .heartRateDao()
                                .getVisibleByTypeAndTimeRange("RESTING", windowStart.toEpochMilli(), oneDayWindowEndMs)
                        }
                    Log.i(
                        "BaselineMetrics",
                        "METRIC=tier_visible_day_window, SCALE=$total, " +
                            "TIME_RANGE_ROWS=${dayRaw.size}, TIME_RANGE_MS=${dayRawNanos / 1_000_000.0}, " +
                            "TYPE_RANGE_ROWS=${dayTyped.size}, TYPE_RANGE_MS=${dayTypedNanos / 1_000_000.0}",
                    )
                    assertTrue(
                        "a one-day window's result must not balloon toward the table size: " +
                            "${dayRaw.size} rows at SCALE=$total",
                        dayRaw.size < total,
                    )
                    assertTrue(
                        "a one-day typed window's result must not balloon toward the table size: " +
                            "${dayTyped.size} rows at SCALE=$total",
                        dayTyped.size < total,
                    )

                    val reader = AuthoritativeHeartRateReader(database.heartRateDao(), database.minuteBucketDao())
                    val typedRepo = TypedHeartRateRepositoryImpl(reader)
                    var maxTypedPage = 0
                    var typedTotal = 0
                    val (_, typedPagedNanos) =
                        measured {
                            typedRepo.forEachByTimeRangeOfTypePage(
                                "RESTING",
                                0L,
                                Long.MAX_VALUE,
                                TYPED_PAGE_LIMIT,
                            ) { page ->
                                maxTypedPage = maxOf(maxTypedPage, page.size)
                                typedTotal += page.size
                            }
                        }
                    assertTrue(
                        "typed page exceeded its configured limit: $maxTypedPage",
                        maxTypedPage <= TYPED_PAGE_LIMIT,
                    )

                    var maxRollupPage = 0
                    var rollupTotal = 0
                    var afterTs = Long.MIN_VALUE
                    var afterRef = Long.MIN_VALUE
                    val rollupStartedNanos = SystemClock.elapsedRealtimeNanos()
                    while (true) {
                        val page =
                            database.heartRateDao().pagePlausibleSamplesForRollup(
                                0L,
                                Long.MAX_VALUE,
                                afterTs,
                                afterRef,
                                ROLLUP_PAGE_LIMIT,
                            )
                        if (page.isEmpty()) break
                        maxRollupPage = maxOf(maxRollupPage, page.size)
                        rollupTotal += page.size
                        val last = page.last()
                        afterTs = last.timestampMs
                        afterRef = last.sourceRecordRef
                    }
                    val rollupPagedNanos = SystemClock.elapsedRealtimeNanos() - rollupStartedNanos
                    assertTrue(
                        "rollup page exceeded its configured limit: $maxRollupPage",
                        maxRollupPage <= ROLLUP_PAGE_LIMIT,
                    )

                    Log.i(
                        "BaselineMetrics",
                        "METRIC=tier_visible_paged, SCALE=$total, " +
                            "TYPED_PAGE_MAX=$maxTypedPage, TYPED_TOTAL=$typedTotal, " +
                            "TYPED_MS=${typedPagedNanos / 1_000_000.0}, " +
                            "ROLLUP_PAGE_MAX=$maxRollupPage, ROLLUP_TOTAL=$rollupTotal, " +
                            "ROLLUP_MS=${rollupPagedNanos / 1_000_000.0}",
                    )
                } finally {
                    database.close()
                }
            }
        }

    @Test
    fun successfulStepsPageThenDenialPreservesPriorRoomRows() =
        runBlocking {
            val database = fixture.createDatabase("denied-steps.db", true, CountingQueryCallback())
            try {
                val store =
                    ScoringBenchmarkHelper.createRoomHealthIngestionStore(
                        database,
                        CountingTransactionRunner(RoomTransactionRunner(database)),
                    )
                val staging =
                    RoomScanStagingStore(database.scanStagingDao(), database.scanTypeStateDao())
                val repo =
                    BenchmarkFakeHealthConnectRepository(stepsPagesSequence = BaselineScalePoints.stepsPages(100))
                val coordinator = HealthIngestionCoordinator(repo, store, staging)
                val start = Instant.parse("2026-01-01T00:00:00Z")
                val end = start.plusSeconds(30L * 24 * 3600)
                val prefs = UserPreferences(scoringZoneId = zoneId.id)
                coordinator.ingestWindow(start, end, prefs)
                val existingRows = database.stepRecordDao().getBetween(start.toEpochMilli(), end.toEpochMilli()).toSet()
                assertEquals(100, existingRows.size)
                repo.stepsPagesSequence = BaselineScalePoints.stepsPages(10)
                repo.stepsOutcome = app.readylytics.health.core.model.domain.repository.ReadOutcome.Denied
                coordinator.ingestWindow(
                    start,
                    end,
                    prefs,
                    scanIdentity =
                        app.readylytics.health.core.model.domain.sync
                            .ScanIdentity("denied", "0"),
                )
                assertEquals(
                    existingRows,
                    database.stepRecordDao().getBetween(start.toEpochMilli(), end.toEpochMilli()).toSet(),
                )
            } finally {
                database.close()
            }
        }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
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

/** 43,200 = one dense day at one-second cadence, plus the Phase 0 250k/500k/1M scale points. */
private val DENSE_STEPS_SCALE_POINTS: List<Int> = listOf(43_200) + BaselineScalePoints.SAMPLE_COUNTS

private const val ONE_DAY_MS = 24L * 3600 * 1000

/** Mirrors `MinuteRollupStreamer.SAMPLE_PAGE_SIZE`, the real production rollup page budget. */
private const val ROLLUP_PAGE_LIMIT = 5_000

/** Mirrors `WorkoutHeartRateBatcher.MAX_CLUSTER_SAMPLES`, the real production typed-page budget. */
private const val TYPED_PAGE_LIMIT = 50_000

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
    private val heartRateInserts = AtomicLong(0)
    val heartRateInsertCount: Long get() = heartRateInserts.get()

    fun reset() {
        counter.set(0)
        heartRateInserts.set(0)
    }

    override fun onQuery(
        sqlQuery: String,
        bindArgs: List<Any?>,
    ) {
        counter.incrementAndGet()
        if (sqlQuery.startsWith("INSERT INTO heart_rate_records")) heartRateInserts.incrementAndGet()
    }
}
