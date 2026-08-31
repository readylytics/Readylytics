package app.readylytics.health.benchmark

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * B9: every scoring read plans through an index; never SCAN TABLE heart_rate_records. The literal
 * SQL below is copied from the DAO @Query annotations — keep it in sync when the DAOs change.
 */
@RunWith(AndroidJUnit4::class)
class QueryPlanTest {
    private lateinit var database: HealthDatabase
    private lateinit var dbContext: Context
    private val zoneId = ZoneId.of("Europe/Berlin")

    @Before
    fun setUp() {
        database =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                    "query-plan-test.db",
                ).build()
        val startMs =
            LocalDate
                .of(2026, 1, 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        // Enough rows to make the planner choose an index rather than an empty-table shortcut.
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking {
            val ref = database.sourceRecordDao().getOrCreateSourceRef("qplan-src", "HEART_RATE", 0L)
            database.heartRateDao().upsertAll(
                (0 until 1_000).map { i ->
                    HeartRateRecordEntity(
                        sourceRecordRef = ref,
                        timestampMs = startMs + i * 60_000L,
                        beatsPerMinute = 55 + (i % 40),
                        recordType = if (i % 3 == 0) "SLEEP" else "RESTING",
                        sessionId = if (i % 3 == 0) "s1" else null,
                    )
                },
            )
        }
        dbContext = context
    }

    @After
    fun tearDown() {
        database.close()
        dbContext.deleteDatabase("query-plan-test.db")
    }

    private fun queryPlans(sql: String): List<String> {
        val rows = mutableListOf<String>()
        database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursor.getString(3) // the detail column
            }
        }
        return rows
    }

    private fun assertUsesIndex(
        queryName: String,
        sql: String,
    ) {
        val plans = queryPlans(sql)
        assertTrue(
            "$queryName must use an index; got: $plans",
            plans.any { it.contains("USING INDEX") || it.contains("USING COVERING INDEX") },
        )
        assertFalse(
            "$queryName must not scan the hot table; got: $plans",
            plans.any { it.contains("SCAN TABLE heart_rate_records") },
        )
    }

    @Test
    fun getKeysetPageUsesIndex() {
        assertUsesIndex(
            "getKeysetPage",
            "SELECT * FROM heart_rate_records WHERE timestampMs > 1735689600000 " +
                "ORDER BY timestampMs, sourceRecordRef LIMIT 500",
        )
    }

    @Test
    fun getByTypeAndTimeRangeUsesIndex() {
        assertUsesIndex(
            "getByTypeAndTimeRange",
            "SELECT * FROM heart_rate_records " +
                "WHERE recordType = 'SLEEP' AND timestampMs BETWEEN 1735689600000 AND 1735776000000",
        )
    }

    @Test
    fun getSleepHrProjectionForSessionsUsesIndex() {
        assertUsesIndex(
            "getSleepHrProjectionForSessions",
            "SELECT sessionId, beatsPerMinute FROM heart_rate_records " +
                "WHERE sessionId IN ('s1') AND recordType = 'SLEEP' " +
                "AND beatsPerMinute BETWEEN 30 AND 230 " +
                "ORDER BY sessionId ASC, beatsPerMinute ASC, timestampMs ASC, sourceRecordRef ASC",
        )
    }

    @Test
    fun observeAggregateByTimeRangeUsesIndex() {
        assertUsesIndex(
            "observeAggregateByTimeRange",
            "SELECT AVG(beatsPerMinute), COUNT(*) FROM heart_rate_records " +
                "WHERE timestampMs BETWEEN 1735689600000 AND 1735776000000",
        )
    }

    @Test
    fun hotMinuteBucketsUsesIndex() {
        assertUsesIndex(
            "getMinuteBuckets (hot)",
            "SELECT (timestampMs / 60000) AS bucketStartMs, AVG(beatsPerMinute), COUNT(*) " +
                "FROM heart_rate_records " +
                "WHERE timestampMs >= 1735689600000 AND timestampMs < 1735776000000 " +
                "AND beatsPerMinute BETWEEN 30 AND 230 " +
                "GROUP BY (timestampMs / 60000)",
        )
    }

    @Test
    fun warmMinuteBucketsUsesIndex() {
        assertUsesIndex(
            "getMinuteBuckets (warm)",
            "SELECT bucketStartMs, SUM(avgBpm * sampleCount) / SUM(sampleCount), SUM(sampleCount) " +
                "FROM hr_minute_buckets " +
                "WHERE bucketStartMs >= 1735689600000 AND bucketEndMs <= 1735776000000 " +
                "AND avgBpm BETWEEN 30 AND 230 " +
                "GROUP BY bucketStartMs",
        )
    }

    @Test
    fun deleteBeforeTimestampBatchUsesIndex() {
        assertUsesIndex(
            "deleteBeforeTimestampBatch",
            "DELETE FROM heart_rate_records WHERE timestampMs < 1735689600000 " +
                "AND rowId IN (SELECT rowId FROM heart_rate_records " +
                "WHERE timestampMs < 1735689600000 ORDER BY timestampMs ASC LIMIT 10000)",
        )
    }

    // R2-PERF-002: asserts CURRENT (incorrect) behavior; flipped by WP-11's windowed rollup.
    @Test
    fun rollupIntoBucketsBeforeIsAknownFullScan() {
        val plans =
            queryPlans(
                "INSERT OR REPLACE INTO hr_minute_buckets " +
                    "(bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount, " +
                    "recordType, sessionId, deviceName) " +
                    "SELECT (timestampMs / 60000) * 60000, (timestampMs / 60000) * 60000 + 60000, " +
                    "MIN(beatsPerMinute), MAX(beatsPerMinute), AVG(beatsPerMinute), COUNT(*), " +
                    "recordType, COALESCE(sessionId, ''), NULL " +
                    "FROM heart_rate_records " +
                    "WHERE timestampMs < 1735689600000 AND beatsPerMinute BETWEEN 30 AND 230 " +
                    "GROUP BY (timestampMs / 60000), recordType, COALESCE(sessionId, '')",
            )
        // Today this plans a full scan + temp B-tree — that is the R2-PERF-002 defect being characterized.
        assertTrue(
            "rollup is expected to scan today (R2-PERF-002); got: $plans",
            plans.any { it.contains("SCAN TABLE heart_rate_records") },
        )
    }
}
