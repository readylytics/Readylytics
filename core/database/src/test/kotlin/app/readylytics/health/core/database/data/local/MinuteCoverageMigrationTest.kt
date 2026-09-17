package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.migration.Migration21To22
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MinuteCoverageMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HealthDatabase::class.java)

    @Test
    fun migrationExecutesExpectedSchemaAlter() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        Migration21To22.migrate(db)

        verify {
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `minute_coverage`") })
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `hr_source_minute_contributions`") })
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `staged_hr_sources`") })
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `staged_hr_samples`") })
            db.execSQL("ALTER TABLE `hr_minute_buckets` ADD COLUMN `generation` INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Test
    fun migrate21To22_preservesLegacyWarmBucketsAndChecksFk() {
        helper.createDatabase(TEST_DATABASE, 21).apply {
            execSQL(
                "INSERT INTO health_source_records (id, sourceRecordId, recordType, createdAtMs, metadataState, sourceRevision) " +
                "VALUES (1, 'src-1', 'HEART_RATE', 0, 'UNKNOWN', 0)"
            )
            
            // Seed a raw heart rate record (for a raw-only minute and mixed minute)
            execSQL(
                "INSERT INTO heart_rate_records (sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
                    "VALUES (1, 1000, 60, 'RESTING', NULL, 'device-1')",
            )
            // Seed legacy warm buckets
            // bucketStartMs 0 is warm-only
            execSQL(
                "INSERT INTO hr_minute_buckets (bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount, recordType, " +
                "sessionId, deviceName) VALUES (0, 60000, 50, 70, 60.0, 10, 'RESTING', '', '')",
            )
            // bucketStartMs 60000 is warm, let's say mixed with raw (not physically enforced, just logically)
            execSQL(
                "INSERT INTO hr_minute_buckets (bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount, recordType, " +
                "sessionId, deviceName) VALUES (60000, 120000, 55, 75, 65.0, 15, 'RESTING', '', '')",
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(TEST_DATABASE, 22, true, *DatabaseMigrations.all)

        // Verify generation column added and set to 0
        database.query("SELECT bucketStartMs, generation FROM hr_minute_buckets ORDER BY bucketStartMs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            
            assertTrue(cursor.moveToNext())
            assertEquals(60000L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            
            assertFalse(cursor.moveToNext())
        }

        // Verify minute_coverage initialized
        database.query("SELECT bucketStartMs, visibleGeneration, tier, quality FROM minute_coverage ORDER BY bucketStartMs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            assertEquals("LEGACY_WARM", cursor.getString(2))
            assertEquals("LEGACY_UNKNOWN", cursor.getString(3))

            assertTrue(cursor.moveToNext())
            assertEquals(60000L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            assertEquals("LEGACY_WARM", cursor.getString(2))
            assertEquals("LEGACY_UNKNOWN", cursor.getString(3))

            assertFalse(cursor.moveToNext())
        }

        // Verify raw rows are intact
        database.query("SELECT COUNT(*) FROM heart_rate_records").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        // Check foreign keys
        database.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse(cursor.moveToFirst()) // Should be empty
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-21-22-test"
    }
}
