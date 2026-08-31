package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// R2-DB-004: percentile-sketch columns (p5Bpm..p95Bpm) added to hr_minute_buckets via five
// additive `ALTER TABLE ADD COLUMN` statements -- no table rewrite. This proves the migration is
// safe for users with existing warm-tier data: an existing row's min/max/avg/count/recordType/
// sessionId survive unchanged, and the new percentile columns read back NULL (rollup never
// reprocesses already-rolled minutes, so pre-migration buckets stay NULL forever by design).
@RunWith(RobolectricTestRunner::class)
class Migration14To15Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    @Test
    fun migrate14To15AddsNullablePercentileColumnsWithoutTouchingExistingRows() {
        helper.createDatabase(TEST_DATABASE, 14).apply {
            execSQL(
                "INSERT INTO hr_minute_buckets " +
                    "(bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount, recordType, sessionId) " +
                    "VALUES (0, 60000, 55, 70, 62.5, 12, 'SLEEP', 's1')",
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                15,
                true,
                *DatabaseMigrations.all,
            )

        database.query(
            "SELECT p5Bpm, p25Bpm, p50Bpm, p75Bpm, p95Bpm, minBpm, maxBpm, avgBpm, sampleCount, " +
                "recordType, sessionId FROM hr_minute_buckets",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0)) // p5Bpm
            assertTrue(cursor.isNull(1)) // p25Bpm
            assertTrue(cursor.isNull(2)) // p50Bpm
            assertTrue(cursor.isNull(3)) // p75Bpm
            assertTrue(cursor.isNull(4)) // p95Bpm
            assertEquals(55, cursor.getInt(5))
            assertEquals(70, cursor.getInt(6))
            assertEquals(62.5, cursor.getDouble(7), 0.001)
            assertEquals(12, cursor.getInt(8))
            assertEquals("SLEEP", cursor.getString(9))
            assertEquals("s1", cursor.getString(10))
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-14-15-test"
    }
}
