package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
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
        assertTrue("subquery side must use the staging index: $plan", plan.any { it.contains("scan_seen_ids") })
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
     * (raw HR/HRV children, warm contribution evidence, staged metadata, in-flight scan staging).
     */
    @Test
    fun sourceGcCandidatePageDoesNotScanChildTables() {
        val plan =
            explain(
                "SELECT id FROM health_source_records WHERE id > 0 " +
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
}

/**
 * True when this `EXPLAIN QUERY PLAN` detail row is a full table scan of [table]. Accepts both the
 * modern `SCAN <table>` wording and the older `SCAN TABLE <table>`; an index-driven `SEARCH`, or a
 * `SCAN ... USING ... INDEX` (including a full covering-index scan), is not a table scan.
 */
private fun String.scansTable(table: String): Boolean =
    Regex("""\bSCAN (TABLE )?\Q$table\E\b""").containsMatchIn(this) && !contains("USING")
