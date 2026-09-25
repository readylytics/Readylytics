package app.readylytics.health.benchmark

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.HealthMutationCoordinatorImpl
import app.readylytics.health.core.database.data.local.HealthRecordDaos
import app.readylytics.health.core.database.data.local.MinuteCoveragePublisher
import app.readylytics.health.core.database.data.local.RetentionCleanup
import app.readylytics.health.core.database.data.local.RoomScanStagingStore
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.healthconnect.domain.sync.HealthIngestionCoordinator
import app.readylytics.health.core.healthconnect.domain.sync.IngestionWindowResult
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.databasebenchmark.data.migration.CurrentSchemaBenchmarkFixture
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/**
 * Task 11 (WP-18/WP-19 acceptance): measures the three scalability claims this phase makes, using
 * the real production classes end to end rather than isolated unit assertions -- 1,000,000-parent
 * ingestion (heap plateau + transaction-batch scaling), dense single-day rollup (bounded heap +
 * interruption safety), and million-source backup export before/after the source-metadata GC
 * (bounded, FK-complete, shrinking). Reuses the same [CountingTransactionRunner]/
 * [CountingQueryCallback]/[measured] instrumentation as [HealthPipelineBaselineBenchmark] --
 * counters only, never SQL values or health values.
 *
 * `HEAP_PLATEAU_BUDGET_BYTES` is a provisional engineering budget, not a measured Phase-0 figure --
 * `benchmark/BASELINE.md` had no comparable heap measurement to inherit when this file was written
 * (see its "Phase 2 -- WP-18/WP-19" section). The first real device run of these methods should
 * become the recorded baseline and this constant should be replaced with that measurement.
 */
/*
 * @LargeTest: excluded from the routine `connectedDebugAndroidTest` sweep by this module's
 * `notAnnotation` filter (see database-benchmark/build.gradle.kts). Benchmarks produce meaningless
 * numbers on a shared/debuggable runner, and this module carries pre-existing test failures that
 * were invisible while its instrumentation could not start at all. Opt in explicitly:
 *   ./gradlew :database-benchmark:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.annotation=androidx.test.filters.LargeTest
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ScanStagingScaleBenchmark {
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private lateinit var fixture: CurrentSchemaBenchmarkFixture
    private lateinit var queryCounter: CountingQueryCallback
    private lateinit var countingTxRunner: CountingTransactionRunner
    private lateinit var db: HealthDatabase

    @Before
    fun setUp() {
        fixture = CurrentSchemaBenchmarkFixture(ApplicationProvider.getApplicationContext())
        queryCounter = CountingQueryCallback()
        val instance = fixture.createTemplate("scan-staging-scale", useSqlCipher = true)
        db = instance.database
        countingTxRunner = CountingTransactionRunner(RoomTransactionRunner(db))
    }

    @After
    fun tearDown() {
        fixture.cleanUp()
    }

    /** Step 1 shape assertion for the new page-boundary-extremes fixture (Task 11 / §11 shape 3). */
    @Test
    fun verifyPageBoundaryExtremeShape() {
        val pages = HealthParentFixture.pageBoundaryExtremes(fillerParentCount = 200, pageSize = 64).toList()
        val lastParent = pages.last().last()
        assertEquals("fixture_boundary_parent", lastParent.id)
        assertEquals(2, lastParent.samples.size)
        val boundary = HealthParentFixture.chunkBoundary().toEpochMilli()
        assertTrue(
            "first sample must be strictly before the boundary",
            lastParent.samples[0].time.toEpochMilli() < boundary,
        )
        assertTrue(
            "second sample must be at or after the boundary",
            lastParent.samples[1].time.toEpochMilli() >= boundary,
        )
        // The same instant is also a minute boundary: the fixture's 30-day window is an exact
        // multiple of one minute, which is what lets one shape exercise both boundaries at once.
        assertEquals(0L, boundary % MINUTE_MS)
    }

    /**
     * WP-18 acceptance: 1,000,000 one-sample parents through the real
     * [HealthIngestionCoordinator] + `RoomHealthIngestionStore`, with the real Room-backed
     * [RoomScanStagingStore] (not the coordinator's in-memory default) so staged ids actually live
     * in the `scan_seen_ids` table this phase added, not a heap set.
     */
    @Test
    fun benchmarkMillionParentIngestPlateau() =
        runBlocking {
            val (_, elapsedMs) = measured { ingestFixture(parents = ONE_MILLION_PARENTS, samplesEach = 1) }

            assertTrue(
                "transactions ${countingTxRunner.transactionCount} must stay far below parent count",
                countingTxRunner.transactionCount < TRANSACTION_CEILING,
            )
            val heapBytes = peakHeapBytes()
            logMetric(
                "ingest.1m.parents",
                elapsedMs,
                countingTxRunner.transactionCount,
                queryCounter.statementCount,
                heapBytes,
            )
            assertTrue(
                "heap grew with scanned ids: ${heapBytes}B >= budget ${HEAP_PLATEAU_BUDGET_BYTES}B",
                heapBytes < HEAP_PLATEAU_BUDGET_BYTES,
            )
        }

    /**
     * WP-19 acceptance: one day holding several hundred thousand raw samples across three devices,
     * rolled up through the real [DataRollupManager.rollupExpiredHotTier]. Asserts bounded heap,
     * then separately proves the crash-safety contract documented on [DataRollupManager] itself --
     * interrupting a second, identical day's pass mid-stream leaves every already-published minute
     * complete (raw rows deleted) and every not-yet-published minute untouched (still fully raw).
     */
    @Test
    fun benchmarkDenseDayRollupMemory() =
        runBlocking {
            val sourceRefs = seedThreeDeviceSources()
            seedDenseDay(DAY_ONE_START_MS, sourceRefs)
            val rollupManager = buildRollupManager()
            val cutoffMs = DAY_ONE_START_MS + DAY_MS + 1

            val (_, elapsedMs) = measured { rollupManager.rollupExpiredHotTier(cutoffMs) }
            val heapBytes = peakHeapBytes()
            logMetric(
                "rollup.dense.day",
                elapsedMs,
                countingTxRunner.transactionCount,
                queryCounter.statementCount,
                heapBytes,
            )
            assertTrue(
                "heap grew during dense rollup: ${heapBytes}B >= budget ${HEAP_PLATEAU_BUDGET_BYTES}B",
                heapBytes < HEAP_PLATEAU_BUDGET_BYTES,
            )

            verifyInterruptionSafety(sourceRefs)
        }

    /**
     * WP-19 acceptance: exports a million-source-row database, runs the real source-metadata GC
     * (via `RetentionCleanup`, the public entry point production uses -- the GC collector itself is
     * module-internal to `core:database`), and exports again. Both exports must be FK-complete for
     * every still-referenced source and bounded in heap; the second must be smaller.
     */
    @Test
    fun benchmarkMillionSourceBackupAfterGc() =
        runBlocking {
            val referencedIds = seedMillionSources()

            val (beforeResult, beforeElapsedMs) = measured { exportSources(referencedIds) }
            val heapBefore = peakHeapBytes()
            logMetric("backup.1m.sources.beforeGc", beforeElapsedMs, 0, 0, heapBefore)
            assertEquals(TOTAL_SOURCES, beforeResult.rowCount)
            assertEquals(
                "every referenced source must appear exactly once before GC",
                referencedIds,
                beforeResult.referencedIdsSeen,
            )
            assertTrue(
                "export heap grew: ${heapBefore}B >= budget ${HEAP_PLATEAU_BUDGET_BYTES}B",
                heapBefore < HEAP_PLATEAU_BUDGET_BYTES,
            )

            runGc()

            val (afterResult, afterElapsedMs) = measured { exportSources(referencedIds) }
            val heapAfter = peakHeapBytes()
            logMetric("backup.1m.sources.afterGc", afterElapsedMs, 0, 0, heapAfter)
            assertEquals(
                "every referenced source must still appear exactly once after GC",
                referencedIds,
                afterResult.referencedIdsSeen,
            )
            assertTrue(
                "GC export must be smaller: after=${afterResult.rowCount} before=${beforeResult.rowCount}",
                afterResult.rowCount < beforeResult.rowCount,
            )
            assertTrue(
                "export heap grew after GC: ${heapAfter}B >= budget ${HEAP_PLATEAU_BUDGET_BYTES}B",
                heapAfter < HEAP_PLATEAU_BUDGET_BYTES,
            )
        }

    // ---- helpers ----

    private suspend fun ingestFixture(
        parents: Int,
        samplesEach: Int,
        pageSize: Int = FIXTURE_PAGE_SIZE,
    ): IngestionWindowResult {
        val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(db, countingTxRunner)
        val staging = RoomScanStagingStore(db.scanStagingDao(), db.scanTypeStateDao())
        val fakeRepo =
            BenchmarkFakeHealthConnectRepository(
                pagesSequence = HealthParentFixture.pages(parents, samplesEach, pageSize),
            )
        val coordinator = HealthIngestionCoordinator(fakeRepo, store, staging)
        return coordinator.ingestWindow(
            windowStart = FIXTURE_WINDOW_START,
            windowEnd = FIXTURE_WINDOW_START.plusMillis(FIXTURE_WINDOW_MS),
            prefs = UserPreferences(scoringZoneId = zoneId.id),
            windowBudgetMs = INGEST_WINDOW_BUDGET_MS,
        )
    }

    private suspend fun seedThreeDeviceSources(): List<Long> =
        (0 until 3).map { idx ->
            val sourceId = "bench-dense-day-source-$idx"
            db.sourceRecordDao().getSourceRef(sourceId) ?: run {
                db.sourceRecordDao().insertIgnore(
                    HealthSourceRecordEntity(sourceRecordId = sourceId, recordType = "HEART_RATE", createdAtMs = 0L),
                )
                db.sourceRecordDao().getSourceRef(sourceId) ?: error("failed to create $sourceId")
            }
        }

    /** Several hundred thousand samples across one day, 3 devices, uniform per-minute density. */
    private suspend fun seedDenseDay(
        dayStartMs: Long,
        sourceRefs: List<Long>,
    ) {
        val minutesPerDay = (DAY_MS / MINUTE_MS).toInt()
        val sampleSpacingMs = MINUTE_MS / SAMPLES_PER_MINUTE_PER_SOURCE
        val allRows = ArrayList<HeartRateRecordEntity>(minutesPerDay * sourceRefs.size * SAMPLES_PER_MINUTE_PER_SOURCE)
        for (minuteIdx in 0 until minutesPerDay) {
            val minuteStart = dayStartMs + minuteIdx.toLong() * MINUTE_MS
            for (sourceRef in sourceRefs) {
                for (s in 0 until SAMPLES_PER_MINUTE_PER_SOURCE) {
                    allRows +=
                        HeartRateRecordEntity(
                            sourceRecordRef = sourceRef,
                            timestampMs = minuteStart + s * sampleSpacingMs,
                            beatsPerMinute = 55 + (s % 30),
                            recordType = RecordType.RESTING.name,
                            sessionId = null,
                        )
                }
            }
        }
        allRows.chunked(SEED_BATCH_SIZE).forEach { batch -> db.heartRateDao().upsertAll(batch) }
    }

    private fun buildRollupManager(): DataRollupManager =
        DataRollupManager(
            coordinator = HealthMutationCoordinatorImpl(db.healthMutationStateDao()),
            minuteCoverageDao = db.minuteCoverageDao(),
            heartRateDao = db.heartRateDao(),
            publisher = MinuteCoveragePublisher(db.minuteBucketDao(), db.minuteCoverageDao()),
            transactionRunner = countingTxRunner,
        )

    private suspend fun verifyInterruptionSafety(sourceRefs: List<Long>) =
        coroutineScope {
            seedDenseDay(DAY_TWO_START_MS, sourceRefs)
            val cutoffMs = DAY_TWO_START_MS + DAY_MS + 1
            val rollupManager = buildRollupManager()

            val job =
                launch {
                    rollupManager.rollupExpiredHotTier(cutoffMs, groupMinuteBudget = INTERRUPTION_GROUP_MINUTES)
                }
            delay(INTERRUPTION_DELAY_MS)
            job.cancelAndJoin()

            val published = db.minuteCoverageDao().getCoverageInRange(DAY_TWO_START_MS, DAY_TWO_START_MS + DAY_MS)
            assertTrue("interruption test must actually publish something to be meaningful", published.isNotEmpty())
            val publishedStarts = published.mapTo(HashSet()) { it.bucketStartMs }
            val expectedPerMinute = sourceRefs.size * SAMPLES_PER_MINUTE_PER_SOURCE
            var checkedUnpublished = 0
            for (minuteIdx in 0 until (DAY_MS / MINUTE_MS).toInt()) {
                val minuteStart = DAY_TWO_START_MS + minuteIdx.toLong() * MINUTE_MS
                val rawCount = db.heartRateDao().countInRange(minuteStart, minuteStart + MINUTE_MS - 1)
                if (minuteStart in publishedStarts) {
                    assertEquals("published minute must have its raw rows deleted", 0, rawCount)
                } else if (rawCount > 0) {
                    checkedUnpublished++
                    assertEquals("unpublished minute must still hold every raw sample", expectedPerMinute, rawCount)
                }
            }
            assertTrue(
                "interruption must leave at least one minute unpublished to be meaningful",
                checkedUnpublished > 0,
            )
        }

    private suspend fun seedMillionSources(): Set<String> {
        val referenced = (0 until REFERENCED_SOURCES).map { "bench-referenced-source-$it" }.toSet()
        val payloads =
            referenced.mapIndexed { idx, sourceId ->
                SourcePayload(
                    source = SourceMetadata(sourceId, "HEART_RATE", null, idx.toLong(), idx.toLong() + 1L, null),
                    rows =
                        listOf(
                            HeartRateInput(
                                id = "${sourceId}_0",
                                timestampMs = idx.toLong(),
                                beatsPerMinute = 60,
                                recordType = RecordType.RESTING.name,
                                sessionId = null,
                                deviceName = "fixture-origin-0",
                                sourceId = sourceId,
                            ),
                        ),
                )
            }
        val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(db, countingTxRunner)
        payloads.chunked(FIXTURE_PAGE_SIZE).forEach { batch -> store.replaceHeartRateSources(batch) }

        val orphanCount = TOTAL_SOURCES - REFERENCED_SOURCES
        var start = 0
        while (start < orphanCount) {
            val end = minOf(start + ORPHAN_BATCH_SIZE, orphanCount)
            db.sourceRecordDao().insertIgnoreAll(
                (start until end).map { idx ->
                    HealthSourceRecordEntity(
                        sourceRecordId = "bench-orphan-source-$idx",
                        recordType = "HEART_RATE",
                        createdAtMs = 0L,
                    )
                },
            )
            start = end
        }
        return referenced
    }

    private suspend fun runGc() {
        val daos =
            HealthRecordDaos(
                sleepSessionDao = db.sleepSessionDao(),
                sleepStageDao = db.sleepStageDao(),
                heartRateDao = db.heartRateDao(),
                hrvDao = db.hrvDao(),
                workoutDao = db.workoutDao(),
                workoutRoutePointDao = db.workoutRoutePointDao(),
                weightRecordDao = db.weightRecordDao(),
                bodyFatRecordDao = db.bodyFatRecordDao(),
                bloodPressureRecordDao = db.bloodPressureRecordDao(),
                oxygenSaturationRecordDao = db.oxygenSaturationRecordDao(),
                bodyTemperatureRecordDao = db.bodyTemperatureRecordDao(),
                stepRecordDao = db.stepRecordDao(),
                sourceRecordDao = db.sourceRecordDao(),
                minuteBucketMaintenanceDao = db.minuteBucketMaintenanceDao(),
            )
        val retentionCleanup =
            RetentionCleanup(
                coordinator = HealthMutationCoordinatorImpl(db.healthMutationStateDao()),
                transactionRunner = countingTxRunner,
                daos = daos,
                dailySummaryDao = db.dailySummaryDao(),
                vo2MaxRecordDao = db.vo2MaxRecordDao(),
            )
        // cutoffMs = 0 deletes no raw rows (every fixture timestamp here is > 0) -- this call
        // exercises only RetentionCleanup's trailing source-metadata GC pass, which is the real
        // production entry point for that internal-visibility collector.
        retentionCleanup.deleteBefore(
            0L,
            ScoringRunContext.capture(UserPreferences(), Instant.parse("2026-08-31T12:00:00Z")),
        )
    }

    private suspend fun exportSources(referencedIds: Set<String>): SourceExportResult {
        var rowCount = 0
        var afterRef = 0L
        val referencedSeenCounts = HashMap<String, Int>()
        while (true) {
            val page = db.sourceRecordDao().pageAfter(afterRef, EXPORT_PAGE_SIZE)
            if (page.isEmpty()) break
            for (row in page) {
                rowCount++
                if (row.sourceRecordId in referencedIds) {
                    referencedSeenCounts[row.sourceRecordId] = (referencedSeenCounts[row.sourceRecordId] ?: 0) + 1
                }
                afterRef = row.id
            }
        }
        return SourceExportResult(rowCount, referencedSeenCounts.filterValues { it == 1 }.keys)
    }

    private fun logMetric(
        stage: String,
        nanos: Long,
        transactions: Long,
        statements: Long,
        heapBytes: Long,
    ) {
        val ms = nanos / 1_000_000.0
        Log.i(
            "ScanStagingScaleMetrics",
            "STAGE=$stage, DURATION_MS=$ms, TX=$transactions, STATEMENTS=$statements, HEAP_BYTES=$heapBytes",
        )
    }

    /**
     * Post-GC sample of live JVM heap in bytes. Deliberately not a continuous peak sampler: a
     * plateau claim only needs "did heap settle back down after the measured work", not the
     * transient high-water mark during it, and continuous sampling would itself perturb the very
     * allocation pattern being measured.
     */
    private fun peakHeapBytes(): Long {
        System.gc()
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private data class SourceExportResult(
        val rowCount: Int,
        val referencedIdsSeen: Set<String>,
    )

    private companion object {
        // Provisional engineering budget -- see class KDoc. Not a measured Phase-0 figure; replace
        // with the first real device run's measurement once available.
        const val HEAP_PLATEAU_BUDGET_BYTES = 300L * 1024 * 1024

        const val ONE_MILLION_PARENTS = 1_000_000
        const val TRANSACTION_CEILING = 50_000L
        const val FIXTURE_PAGE_SIZE = 1_000
        const val INGEST_WINDOW_BUDGET_MS = 30 * 60_000L
        val FIXTURE_WINDOW_START: Instant = Instant.parse("2026-01-01T00:00:00Z")
        const val FIXTURE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        const val DAY_MS = 86_400_000L
        const val MINUTE_MS = 60_000L
        const val SAMPLES_PER_MINUTE_PER_SOURCE = 72
        const val SEED_BATCH_SIZE = 5_000
        val DAY_ONE_START_MS: Long = Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()
        val DAY_TWO_START_MS: Long = Instant.parse("2026-02-02T00:00:00Z").toEpochMilli()
        const val INTERRUPTION_GROUP_MINUTES = 5
        const val INTERRUPTION_DELAY_MS = 50L

        const val TOTAL_SOURCES = 1_000_000
        const val REFERENCED_SOURCES = 1_000
        const val ORPHAN_BATCH_SIZE = 5_000
        const val EXPORT_PAGE_SIZE = 500
    }
}
