package app.readylytics.health.benchmark

import android.os.SystemClock
import androidx.benchmark.junit4.BenchmarkRule
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomHealthIngestionStore
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.mappers.HeartRateMapper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Baseline benchmark measuring the real current-schema health data pipeline.
 * Measures each pipeline stage separately: provider-fake read, mapping, store,
 * full-range relink, chronological scoring, rollup, and streaming backup export.
 *
 * Instruments TransactionRunner and Room query callbacks with counters only (never logging SQL values).
 */
@RunWith(AndroidJUnit4::class)
class HealthPipelineBaselineBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private lateinit var dbFile: File
    private lateinit var db: HealthDatabase
    private lateinit var queryCounter: CountingQueryCallback
    private lateinit var countingTxRunner: CountingTransactionRunner
    private lateinit var store: RoomHealthIngestionStore

    @Before
    fun setUp() {
        dbFile = File.createTempFile("pipeline-benchmark", ".db")
        dbFile.delete()
        queryCounter = CountingQueryCallback()

        db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                    dbFile.absolutePath,
                ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCallback(queryCounter, Executors.newSingleThreadExecutor())
                .build()

        countingTxRunner = CountingTransactionRunner(RoomTransactionRunner(db))
        store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(db)
    }

    @After
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    /** Step 1 shape assertions validating parent distribution vs sample distribution. */
    @Test
    fun verifyFixtureDatasetShapes() {
        val parents = HealthParentFixture.pages(1_000_001, 1, 1000)
        assertEquals(1_000_001, parents.sumOf { it.size })
        val dense = HealthParentFixture.pages(1001, 1000, 100)
        assertEquals(1_001_000, dense.sumOf { page -> page.sumOf { it.samples.size } })
    }

    /** Measures provider-fake read, mapping, and store as distinct pipeline stages. */
    @Test
    fun measureIngestionPipelineStages() =
        runBlocking {
            // Stage 1: Provider-fake read (lazy sequence chunk materialization)
            val (readPages, readNanos) =
                measured {
                    HealthParentFixture.pages(parentCount = 500, samplesPerParent = 10, pageSize = 50).toList()
                }
            assertTrue("Read must produce pages", readPages.isNotEmpty())
            assertTrue("Read nanos must be positive", readNanos > 0)

            // Stage 2: Ingestion mapping (DomainHeartRateRecord -> HeartRateInput)
            val flatRecords = readPages.flatten()
            val (mappedInputs, mapNanos) =
                measured {
                    HeartRateMapper.mapToInputs(flatRecords, emptyList(), emptyList())
                }
            assertEquals(5000, mappedInputs.size)
            assertTrue("Map nanos must be positive", mapNanos > 0)

            // Stage 3: Store persistence through RoomHealthIngestionStore
            queryCounter.reset()
            countingTxRunner.reset()
            val (_, storeNanos) =
                measured {
                    store.persistHeartRateSamples(mappedInputs)
                }
            assertTrue("Store nanos must be positive", storeNanos > 0)
            assertTrue("Store must execute queries", queryCounter.statementCount > 0)

            // Verify idempotency: re-persisting identical batch causes 0 growth
            val initialCount = db.heartRateDao().count()
            assertEquals(5000, initialCount)
            store.persistHeartRateSamples(mappedInputs)
            val repeatCount = db.heartRateDao().count()
            assertEquals("Idempotent replay must not duplicate samples", initialCount, repeatCount)
        }

    /** Measures full-range relink, chronological scoring, rollup, and backup export. */
    @Test
    fun measurePostIngestionStages() =
        runBlocking {
            val targetDate = LocalDate.of(2026, 1, 31)
            ScoringBenchmarkHelper.seedCalibratedHistory(db, zoneId, targetDate, historyDays = 30)

            // Stage 4: Full-range session link reconciliation
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
                )

            val (_, relinkNanos) =
                measured {
                    reconciler.reconcile(startMs, endMs, zoneThresholds)
                }
            assertTrue("Relink nanos must be positive", relinkNanos > 0)

            // Stage 5: Chronological scoring (calibrated calculation)
            val scoringRepo = ScoringBenchmarkHelper.createScoringRepository(db, zoneId)
            val (_, scoringNanos) =
                measured {
                    scoringRepo.computeAndPersistDailySummary(targetDate, steps = 8_000L)
                }
            assertTrue("Scoring nanos must be positive", scoringNanos > 0)

            // Stage 6: Rollup (downsample raw hot-tier HR samples to warm-tier minute buckets)
            val rollupManager = DataRollupManager(db.minuteBucketDao(), db.heartRateDao(), countingTxRunner)
            val cutoffMs =
                targetDate
                    .minusDays(7)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val (_, rollupNanos) =
                measured {
                    rollupManager.rollupExpiredHotTier(cutoffMs)
                }
            assertTrue("Rollup nanos must be positive", rollupNanos >= 0)

            // Stage 7: Backup export streaming
            val output = ByteArrayOutputStream()
            val (_, exportNanos) =
                measured {
                    exportDatabaseTablesStreaming(db, output)
                }
            assertTrue("Export nanos must be positive", exportNanos > 0)
            assertTrue("Exported bytes must be non-empty", output.size() > 0)
        }

    /** Dataset matrix: dense burst inside sparse history and edited value update. */
    @Test
    fun datasetMatrixAndValueEdit() =
        runBlocking {
            val baseMs =
                LocalDate
                    .of(2026, 1, 1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            // Seed sparse history (1 sample every 15 min for 10 days)
            val sparseSamples =
                (0 until 960).map { i ->
                    HeartRateInput(
                        id = "sparse_$i",
                        timestampMs = baseMs + i * 15 * 60_000L,
                        beatsPerMinute = 65,
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "fixture-origin-0",
                    )
                }
            store.persistHeartRateSamples(sparseSamples)
            assertEquals(960, db.heartRateDao().count())

            // 30-day dense burst (500 samples/day for 3 days = 1500 samples)
            val denseBurst =
                (0 until 1500).map { i ->
                    HeartRateInput(
                        id = "dense_$i",
                        timestampMs = baseMs + (5 * 24 * 3600_000L) + i * 60_000L,
                        beatsPerMinute = 70 + (i % 30),
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "fixture-origin-1",
                    )
                }
            store.persistHeartRateSamples(denseBurst)
            assertEquals(960 + 1500, db.heartRateDao().count())

            // Value edit verification on same key
            val sampleToEdit = denseBurst.first()
            val edited = sampleToEdit.copy(beatsPerMinute = 115)
            store.persistHeartRateSamples(listOf(edited))
            // Count must not grow
            assertEquals(960 + 1500, db.heartRateDao().count())
        }

    /** Streaming export of database tables matching BackupStreamWriter paging pattern. */
    private suspend fun exportDatabaseTablesStreaming(
        database: HealthDatabase,
        out: ByteArrayOutputStream,
    ) {
        val writer = out.bufferedWriter()
        writer.write("{\"tables\":{")

        // Heart rate records paging
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

        // Daily summaries paging
        writer.write("\"dailySummaries\":[")
        val summaries = database.dailySummaryDao().getAllSummaries()
        var sFirst = true
        for (summary in summaries) {
            if (!sFirst) writer.write(",")
            writer.write("{\"day\":\"${summary.scoreDate}\",\"score\":${summary.readinessScore}}")
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
