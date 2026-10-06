package app.readylytics.health.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.model.domain.sync.mappers.HeartRateMapper
import app.readylytics.health.databasebenchmark.data.migration.CurrentSchemaBenchmarkFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not `@LargeTest`: seeds a few thousand rows and asserts on query plans, so it is fast enough to
 * gate PRs in the routine sweep.
 */
@RunWith(AndroidJUnit4::class)
class QueryPlanRecorderTest {
    private lateinit var fixture: CurrentSchemaBenchmarkFixture
    private lateinit var database: HealthDatabase

    @Before
    fun setUp() {
        fixture = CurrentSchemaBenchmarkFixture(ApplicationProvider.getApplicationContext())
        database = fixture.createDatabase("current-benchmark-query-plan.db", useSqlCipher = true)
        // SQLite's planner will happily choose a scan for a table it knows is tiny, so the plan is
        // only meaningful once there are enough rows for an index to win.
        runBlocking {
            val store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(database, RoomTransactionRunner(database))
            BaselineScalePoints.densePages(SEED_SAMPLES).forEach { page ->
                store.replaceHeartRateSources(HeartRateMapper.mapToInputs(page, emptyList(), emptyList()))
            }
            database.openHelper.writableDatabase
                .query("ANALYZE")
                .close()
        }
    }

    @After
    fun tearDown() {
        fixture.cleanUp()
    }

    @Test
    fun heartRateRangeScanUsesAnIndexWithoutATempSort() {
        val plan =
            QueryPlanRecorder.plan(
                database,
                "SELECT * FROM heart_rate_records WHERE timestampMs >= 0 AND timestampMs <= 1 " +
                    "ORDER BY timestampMs ASC, sourceRecordRef ASC",
            )
        assertTrue("plan was empty", plan.isNotBlank())
        assertTrue("expected an index scan, got:\n$plan", plan.contains("USING INDEX"))
        assertTrue("unexpected temp b-tree sort, got:\n$plan", !plan.contains("USE TEMP B-TREE"))
    }

    /**
     * Task 10 (WP-18): the same tier-visibility plan [Phase2QueryPlanTest.tierVisibilityPlansStayIndexed]
     * proves on an empty database, now against [SEED_SAMPLES] real rows plus `ANALYZE` -- so the
     * planner's cardinality estimates, not just the schema, are what commit to an index-driven plan.
     */
    @Test
    fun tierVisiblePlansStayIndexedAtRealCardinality() {
        val timeRangePlan =
            QueryPlanRecorder.planOneLine(
                database,
                "SELECT h.* FROM heart_rate_records h " +
                    "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
                    "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
                    "WHERE h.timestampMs >= 0 AND h.timestampMs <= 86400000 " +
                    "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
                    "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
                    "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
                    "AND b2.generation = c.visibleGeneration))) " +
                    "ORDER BY h.timestampMs ASC, h.sourceRecordRef ASC",
            )
        assertTrue("plan was empty", timeRangePlan.isNotBlank())
        assertFalse(
            "must not scan heart_rate_records: $timeRangePlan",
            timeRangePlan.contains("SCAN heart_rate_records"),
        )
    }

    /** Guards Review Focus 1 in miniature: an empty plan must never read as a pass. */
    @Test
    fun aPlanIsNeverSilentlyEmpty() {
        assertTrue(QueryPlanRecorder.plan(database, "SELECT 1").isNotBlank())
    }

    @Test
    fun oneLineFormCollapsesMultiRowPlans() {
        val multi =
            QueryPlanRecorder.plan(
                database,
                "SELECT * FROM heart_rate_records WHERE timestampMs >= 0 ORDER BY timestampMs ASC",
            )
        val oneLine =
            QueryPlanRecorder.planOneLine(
                database,
                "SELECT * FROM heart_rate_records WHERE timestampMs >= 0 ORDER BY timestampMs ASC",
            )
        assertTrue("one-line form must contain no newline", !oneLine.contains("\n"))
        assertTrue("one-line form must not be empty", oneLine.isNotBlank())
        if (multi.contains("\n")) {
            assertTrue("multi-row plans must be joined with ' | '", oneLine.contains(" | "))
        }
    }

    private companion object {
        const val SEED_SAMPLES = 10_000
    }
}
