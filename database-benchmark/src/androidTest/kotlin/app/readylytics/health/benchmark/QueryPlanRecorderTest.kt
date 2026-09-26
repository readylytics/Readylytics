package app.readylytics.health.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.model.domain.sync.mappers.HeartRateMapper
import app.readylytics.health.databasebenchmark.data.migration.CurrentSchemaBenchmarkFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
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
