package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 2 Task 10: proves -- via real SQLite `EXPLAIN QUERY PLAN`, not code inspection -- that every
 * performance-sensitive query Tasks 3/5/8/9 of this plan added actually drives off the index it was
 * designed around, rather than a full table scan or a temp B-tree sort. Each SQL string below is
 * copied verbatim from the real `@Query` it characterizes (see the KDoc on each test) so that if the
 * query shape ever changes, this test changes with it instead of silently going stale.
 *
 * `EXPLAIN QUERY PLAN` inspects the query planner's chosen access path from schema + indices alone;
 * it needs no seeded rows, so every test here runs against an empty in-memory database.
 */
@RunWith(RobolectricTestRunner::class)
class Phase2QueryPlanTest {
    private lateinit var database: HealthDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = database.close()

    /** Mirrors [app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao.countSeen]'s predicate. */
    @Test
    fun stagedIdLookupUsesTheStagingIndex() {
        val plan =
            explain(
                "SELECT sourceId FROM scan_seen_ids " +
                    "WHERE runId = 'run-a' AND chunkId = '0' AND recordType = 'SLEEP'",
            )
        assertTrue("staging lookup must be index-driven: $plan", plan.none { it.scansTable("scan_seen_ids") })
    }

    /**
     * Mirrors [app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
     * .deleteSessionsNotStaged]'s predicate (projected as a SELECT so EXPLAIN QUERY PLAN applies).
     */
    @Test
    fun sleepAntiJoinDeleteUsesAnIndexOnBothSides() {
        val plan =
            explain(
                "SELECT id FROM sleep_sessions WHERE startTime >= 0 AND endTime <= 100 " +
                    "AND id NOT IN (SELECT sourceId FROM scan_seen_ids " +
                    "WHERE runId = 'run-a' AND chunkId = '0' AND recordType = 'SLEEP')",
            )
        assertTrue(
            "subquery side must use the staging index, not a bare scan: $plan",
            plan.none { it.scansTable("scan_seen_ids") },
        )
        assertTrue("outer side must not table-scan: $plan", plan.none { it.scansTable("sleep_sessions") })
    }

    /**
     * Mirrors [app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
     * .pageUnstagedAuthoritativeSources]'s predicate.
     */
    @Test
    fun unstagedSourcePageUsesTheSourceRangeIndex() {
        val plan =
            explain(
                "SELECT * FROM health_source_records WHERE recordType = 'HEART_RATE' " +
                    "AND metadataState = 'AUTHORITATIVE' AND recordStartMs < 100 " +
                    "AND recordEndExclusiveMs > 0 AND id > 0 " +
                    "AND sourceRecordId NOT IN (SELECT sourceId FROM scan_seen_ids " +
                    "WHERE runId = 'run-a' AND chunkId = '0' AND recordType = 'HEART_RATE') " +
                    "ORDER BY id ASC LIMIT 500",
            )
        assertTrue(
            "expected index_health_source_records_recordType_metadataState_recordStartMs: $plan",
            plan.any { it.contains("index_health_source_records_recordType_metadataState_recordStartMs") },
        )
    }

    /**
     * Mirrors [app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
     * .pagePlausibleSamplesForRollup]'s predicate -- the Task 8 rollup streamer's keyset page.
     */
    @Test
    fun rollupKeysetPageUsesTheTimestampIndexWithoutTempSort() {
        val plan =
            explain(
                "SELECT * FROM heart_rate_records WHERE timestampMs >= 0 AND timestampMs < 60000 " +
                    "AND beatsPerMinute BETWEEN 30 AND 230 " +
                    "AND (timestampMs > 0 OR (timestampMs = 0 AND sourceRecordRef > 0)) " +
                    "ORDER BY timestampMs ASC, sourceRecordRef ASC LIMIT 5000",
            )
        assertTrue("high-volume cursor must not temp-sort: $plan", plan.none { it.contains("USE TEMP B-TREE") })
        assertTrue(
            "expected index_hr_v10_timestamp or index_hr_v10_source_time: $plan",
            plan.any { it.contains("index_hr_v10_timestamp") || it.contains("index_hr_v10_source_time") },
        )
    }

    /**
     * Mirrors [app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
     * .pageUnreferencedSourceIds]'s predicate -- the Task 9 GC's full five-way existence check
     * (raw HR/HRV children, warm contribution evidence, staged metadata, in-flight scan staging),
     * plus the `recordType` allowlist. The `+recordType` unary-plus hint is load-bearing: without
     * it, an un-ANALYZE'd production DB has SQLite prefer
     * `index_health_source_records_recordType_metadataState_recordStartMs` (`recordType=?`) over
     * the `id > :afterRef ... ORDER BY id ASC LIMIT` keyset seek, which both defeats the keyset
     * page (forcing a full scan of every HR/HRV row before the five `NOT EXISTS` checks) and adds
     * a `USE TEMP B-TREE FOR ORDER BY`. The hint forces the planner back onto
     * `USING INTEGER PRIMARY KEY (rowid>?)`, which this test also asserts directly.
     */
    @Test
    fun sourceGcCandidatePageDoesNotScanChildTables() {
        val plan =
            explain(
                "SELECT id FROM health_source_records WHERE id > 0 " +
                    "AND +recordType IN ('HEART_RATE', 'HRV') " +
                    "AND NOT EXISTS (SELECT 1 FROM heart_rate_records " +
                    "WHERE sourceRecordRef = health_source_records.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM hrv_records " +
                    "WHERE sourceRecordRef = health_source_records.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM hr_source_minute_contributions " +
                    "WHERE sourceRecordRef = health_source_records.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM staged_hr_sources " +
                    "WHERE sourceId = health_source_records.sourceRecordId) " +
                    "AND NOT EXISTS (SELECT 1 FROM scan_seen_ids " +
                    "WHERE sourceId = health_source_records.sourceRecordId) " +
                    "ORDER BY id ASC LIMIT 500",
            )
        assertTrue("HR existence check must use an index: $plan", plan.none { it.scansTable("heart_rate_records") })
        assertTrue("HRV existence check must use an index: $plan", plan.none { it.scansTable("hrv_records") })
        assertTrue(
            "contribution existence check must use an index: $plan",
            plan.none { it.scansTable("hr_source_minute_contributions") },
        )
        assertTrue(
            "staged metadata existence check must use an index: $plan",
            plan.none { it.scansTable("staged_hr_sources") },
        )
        assertTrue(
            "scan staging existence check must use an index: $plan",
            plan.none { it.scansTable("scan_seen_ids") },
        )
        assertTrue(
            "outer keyset page must not fall back to a temp-sorted index scan: $plan",
            plan.none { it.contains("USE TEMP B-TREE") },
        )
        assertTrue(
            "expected the rowid keyset seek, not the recordType index: $plan",
            plan.any { it.contains("USING INTEGER PRIMARY KEY (rowid>?)") },
        )
    }

    /**
     * Task 10 (WP-18): the two remaining tier-visible bulk reads
     * [app.readylytics.health.core.databaseschema.data.local.dao.VisibleHeartRateDao
     * .getVisibleByTimeRange] and [.getVisibleByTypeAndTimeRange] are not keyset-paged. Every
     * production consumer of them only ever supplies a caller-bounded window (one day for the
     * dashboard/scoring range reads, one workout span or one batch of chronologically-local
     * workouts for the typed read -- see `AuthoritativeHeartRateReader.kt`'s and
     * `BASELINE.md`'s Task 10 notes), so the real question is whether the SQL itself degrades to a
     * full scan as the table grows, independent of how large any one caller's window is. EXPLAIN
     * QUERY PLAN answers that from schema alone: an empty database already proves the plan the
     * planner commits to does not change as rows accumulate.
     */
    @Test
    fun tierVisibilityPlansStayIndexed() {
        val timeRangePlan = explain(VISIBLE_BY_TIME_RANGE_SQL).joinToString(" | ")
        val typeRangePlan = explain(VISIBLE_BY_TYPE_AND_TIME_RANGE_SQL).joinToString(" | ")
        println("Task 10 EXPLAIN QUERY PLAN getVisibleByTimeRange: $timeRangePlan")
        println("Task 10 EXPLAIN QUERY PLAN getVisibleByTypeAndTimeRange: $typeRangePlan")

        for (plan in listOf(timeRangePlan, typeRangePlan)) {
            assertTrue("plan was empty", plan.isNotBlank())
            assertFalse("must not scan heart_rate_records: $plan", plan.contains("SCAN heart_rate_records"))
            assertFalse("must not temp-sort: $plan", plan.contains("USE TEMP B-TREE FOR ORDER BY"))
        }
    }

    /**
     * Task 10 (WP-18): the Task 4 type-filtered workout read
     * ([app.readylytics.health.core.databaseschema.data.local.dao.TypedHeartRateDao.visibleTypePage],
     * consumed page-by-page via [AuthoritativeHeartRateReader.typePagesInRange]) is the one bulk
     * tier-visible consumer that is already keyset-paged to a caller-supplied budget. This proves
     * paging changes nothing about the *answer*: every page concatenated is element-identical to the
     * unbounded reference ([AuthoritativeHeartRateReader.rangeInOfType]), while every individual page
     * still respects the configured budget -- on a fixture whose visible rows are split across both
     * the raw and the warm tier, so neither tier alone answers the whole range.
     */
    @Test
    fun pagedVisibilityMatchesRange() =
        runBlocking {
            val recordType = "EXERCISE"
            val configuredBudget = 7
            seedOverlapFixture(recordType)

            val reader = AuthoritativeHeartRateReader(database.heartRateDao(), database.minuteBucketDao())
            val endMs = OVERLAP_MINUTES * MINUTE_MS - 1
            val referenceVisibleRows = reader.rangeInOfType(recordType, 0L, endMs).mergedSamples()

            val pagedVisibleRows = mutableListOf<HeartRateRecordEntity>()
            var maxResultSet = 0
            reader.typePagesInRange(recordType, 0L, endMs, configuredBudget) { page ->
                maxResultSet = maxOf(maxResultSet, page.size)
                pagedVisibleRows += page
            }

            assertTrue("page exceeded the configured budget: $maxResultSet", maxResultSet <= configuredBudget)
            assertEquals(referenceVisibleRows, pagedVisibleRows)
        }

    /**
     * Seeds a window whose first half ([OVERLAP_MINUTES] / 2 minutes) is warm-covered and whose
     * second half is raw-only, so neither tier alone answers the whole range -- an "overlap" fixture
     * in this plan's terminology.
     */
    private suspend fun seedOverlapFixture(recordType: String) {
        val samplesPerMinute = OVERLAP_SAMPLES_PER_MINUTE
        val warmMinutes = 0 until OVERLAP_MINUTES / 2
        val rawMinutes = OVERLAP_MINUTES / 2 until OVERLAP_MINUTES
        val ref = database.sourceRecordDao().getOrCreateSourceRef("overlap-src", "HEART_RATE", 0L)

        val rawRows =
            rawMinutes.flatMap { minute ->
                (0 until samplesPerMinute).map { sample ->
                    HeartRateRecordEntity(
                        sourceRecordRef = ref,
                        timestampMs = minute * MINUTE_MS + sample * (MINUTE_MS / samplesPerMinute),
                        beatsPerMinute = 100 + sample,
                        recordType = recordType,
                        sessionId = null,
                    )
                }
            }
        database.heartRateDao().upsertAll(rawRows)

        warmMinutes.forEach { minute -> seedWarmMinute(minute * MINUTE_MS, recordType, samplesPerMinute) }
    }

    private suspend fun seedWarmMinute(
        bucketStartMs: Long,
        recordType: String,
        samplesPerMinute: Int,
    ) {
        database.minuteBucketDao().upsertBuckets(
            listOf(
                HrMinuteBucketEntity(
                    bucketStartMs = bucketStartMs,
                    bucketEndMs = bucketStartMs + MINUTE_MS,
                    minBpm = 100,
                    maxBpm = 100 + samplesPerMinute - 1,
                    avgBpm = 102.0,
                    sampleCount = samplesPerMinute,
                    recordType = recordType,
                    sessionId = "",
                    deviceName = "overlap-device",
                    generation = 1L,
                ),
            ),
        )
        database.minuteCoverageDao().upsertCoverage(
            listOf(
                MinuteCoverageEntity(
                    bucketStartMs = bucketStartMs,
                    visibleGeneration = 1L,
                    tier = "WARM",
                    quality = QUALITY_SOURCE_BACKED,
                    sourceSelectionId = null,
                ),
            ),
        )
    }

    /** Every `detail` row real SQLite reports for [sql], via `EXPLAIN QUERY PLAN`. */
    private fun explain(sql: String): List<String> {
        val details = mutableListOf<String>()
        database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            val detailIndex = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) {
                details += cursor.getString(detailIndex)
            }
        }
        return details
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val OVERLAP_MINUTES = 10
        const val OVERLAP_SAMPLES_PER_MINUTE = 5

        /** Verbatim copy of [app.readylytics.health.core.databaseschema.data.local.dao
         * .VisibleHeartRateDao.getVisibleByTimeRange]'s `@Query`, with bound params substituted. */
        const val VISIBLE_BY_TIME_RANGE_SQL =
            "SELECT h.* FROM heart_rate_records h " +
                "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
                "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
                "WHERE h.timestampMs >= 0 AND h.timestampMs <= 86400000 " +
                "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
                "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
                "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
                "AND b2.generation = c.visibleGeneration))) " +
                "ORDER BY h.timestampMs ASC, h.sourceRecordRef ASC"

        /** Verbatim copy of [app.readylytics.health.core.databaseschema.data.local.dao
         * .VisibleHeartRateDao.getVisibleByTypeAndTimeRange]'s `@Query`, bound params substituted. */
        const val VISIBLE_BY_TYPE_AND_TIME_RANGE_SQL =
            "SELECT h.* FROM heart_rate_records h " +
                "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
                "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
                "WHERE h.recordType = 'EXERCISE' " +
                "AND h.timestampMs >= 0 AND h.timestampMs <= 86400000 " +
                "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
                "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
                "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
                "AND b2.generation = c.visibleGeneration))) " +
                "ORDER BY h.timestampMs ASC, h.sourceRecordRef ASC"
    }
}

/**
 * True when this `EXPLAIN QUERY PLAN` detail row is a full table scan of [table]. Accepts both the
 * modern `SCAN <table>` wording and the older `SCAN TABLE <table>`; an index-driven `SEARCH`, or a
 * `SCAN ... USING ... INDEX` (including a full covering-index scan), is not a table scan.
 */
private fun String.scansTable(table: String): Boolean =
    Regex("""\bSCAN (TABLE )?\Q$table\E\b""").containsMatchIn(this) && !contains("USING")
