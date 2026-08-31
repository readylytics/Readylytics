package app.readylytics.health.core.database.data.local.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.DatabaseMigrations
import app.readylytics.health.core.database.data.local.HealthDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration15To16Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    @Test
    fun migrate15To16PreservesMinuteBucketsAndNormalizesVitalsDeviceNames() {
        helper.createDatabase(TEST_DATABASE, 15).apply {
            seedV15MinuteBuckets(this)
            seedV15VitalsTables(this)
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                16,
                true,
                *DatabaseMigrations.all,
            )

        verifyHrMinuteBuckets(database)
        verifyMultiDeviceInSameMinute(database)
        verifyVitalsNormalization(database)
    }

    private fun seedV15MinuteBuckets(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO hr_minute_buckets (
                bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount,
                recordType, sessionId, deviceName, p5Bpm, p25Bpm, p50Bpm, p75Bpm, p95Bpm
            ) VALUES (
                0, 60000, 50, 70, 60.0, 10, 'SLEEP', 's1', 'Watch A', 51, 55, 60, 65, 69
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO hr_minute_buckets (
                bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount,
                recordType, sessionId, deviceName, p5Bpm, p25Bpm, p50Bpm, p75Bpm, p95Bpm
            ) VALUES (
                60000, 120000, 55, 75, 65.0, 10, 'RESTING', '', NULL, NULL, NULL, NULL, NULL, NULL
            )
            """.trimIndent(),
        )
    }

    private fun seedV15VitalsTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO weight_records (id, timestampMs, weightKg, deviceName) VALUES ('w1', 1000, 70.0, '')",
        )
        db.execSQL(
            "INSERT INTO weight_records (id, timestampMs, weightKg, deviceName) VALUES ('w2', 2000, 71.0, 'Scale')",
        )
        db.execSQL(
            "INSERT INTO weight_records (id, timestampMs, weightKg, deviceName) VALUES ('w3', 3000, 72.0, NULL)",
        )

        db.execSQL(
            "INSERT INTO body_fat_records (id, timestampMs, bodyFatPercent, deviceName) VALUES ('bf1', 1000, 15.0, '')",
        )
        db.execSQL(
            "INSERT INTO body_fat_records (id, timestampMs, bodyFatPercent, deviceName) " +
                "VALUES ('bf2', 2000, 15.5, 'Scale')",
        )
        db.execSQL(
            "INSERT INTO body_fat_records (id, timestampMs, bodyFatPercent, deviceName) " +
                "VALUES ('bf3', 3000, 16.0, NULL)",
        )

        db.execSQL(
            "INSERT INTO blood_pressure_records (id, timestampMs, systolicMmHg, diastolicMmHg, deviceName) " +
                "VALUES ('bp1', 1000, 120, 80, '')",
        )
        db.execSQL(
            "INSERT INTO blood_pressure_records (id, timestampMs, systolicMmHg, diastolicMmHg, deviceName) " +
                "VALUES ('bp2', 2000, 122, 82, 'Cuff')",
        )
        db.execSQL(
            "INSERT INTO blood_pressure_records (id, timestampMs, systolicMmHg, diastolicMmHg, deviceName) " +
                "VALUES ('bp3', 3000, 124, 84, NULL)",
        )

        db.execSQL(
            "INSERT INTO oxygen_saturation_records (id, timestampMs, percentage, deviceName) " +
                "VALUES ('ox1', 1000, 98.0, '')",
        )
        db.execSQL(
            "INSERT INTO oxygen_saturation_records (id, timestampMs, percentage, deviceName) " +
                "VALUES ('ox2', 2000, 97.5, 'Oximeter')",
        )
        db.execSQL(
            "INSERT INTO oxygen_saturation_records (id, timestampMs, percentage, deviceName) " +
                "VALUES ('ox3', 3000, 99.0, NULL)",
        )

        db.execSQL(
            "INSERT INTO body_temperature_records (id, timestampMs, celsius, deviceName) " +
                "VALUES ('bt1', 1000, 36.6, '')",
        )
        db.execSQL(
            "INSERT INTO body_temperature_records (id, timestampMs, celsius, deviceName) " +
                "VALUES ('bt2', 2000, 36.8, 'Thermometer')",
        )
        db.execSQL(
            "INSERT INTO body_temperature_records (id, timestampMs, celsius, deviceName) " +
                "VALUES ('bt3', 3000, 37.0, NULL)",
        )
    }

    private fun verifyHrMinuteBuckets(database: SupportSQLiteDatabase) {
        database.query(
            """
            SELECT bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount,
                   recordType, sessionId, deviceName, p5Bpm, p25Bpm, p50Bpm, p75Bpm, p95Bpm
            FROM hr_minute_buckets
            ORDER BY bucketStartMs ASC
            """.trimIndent(),
        ).use { cursor ->
            assertEquals(2, cursor.count)

            // Row 1
            assertTrue(cursor.moveToNext())
            assertEquals(0L, cursor.getLong(0))
            assertEquals(60000L, cursor.getLong(1))
            assertEquals(50, cursor.getInt(2))
            assertEquals(70, cursor.getInt(3))
            assertEquals(60.0, cursor.getDouble(4), 0.001)
            assertEquals(10, cursor.getInt(5))
            assertEquals("SLEEP", cursor.getString(6))
            assertEquals("s1", cursor.getString(7))
            assertEquals("Watch A", cursor.getString(8))
            assertEquals(51, cursor.getInt(9))
            assertEquals(55, cursor.getInt(10))
            assertEquals(60, cursor.getInt(11))
            assertEquals(65, cursor.getInt(12))
            assertEquals(69, cursor.getInt(13))

            // Row 2 (NULL deviceName coerced to empty string, percentiles remain NULL)
            assertTrue(cursor.moveToNext())
            assertEquals(60000L, cursor.getLong(0))
            assertEquals(120000L, cursor.getLong(1))
            assertEquals(55, cursor.getInt(2))
            assertEquals(75, cursor.getInt(3))
            assertEquals(65.0, cursor.getDouble(4), 0.001)
            assertEquals(10, cursor.getInt(5))
            assertEquals("RESTING", cursor.getString(6))
            assertEquals("", cursor.getString(7))
            assertEquals("", cursor.getString(8))
            assertTrue(cursor.isNull(9))
            assertTrue(cursor.isNull(10))
            assertTrue(cursor.isNull(11))
            assertTrue(cursor.isNull(12))
            assertTrue(cursor.isNull(13))
        }
    }

    private fun verifyMultiDeviceInSameMinute(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO hr_minute_buckets (
                bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount,
                recordType, sessionId, deviceName, p5Bpm, p25Bpm, p50Bpm, p75Bpm, p95Bpm
            ) VALUES (
                0, 60000, 52, 72, 62.0, 10, 'SLEEP', 's1', 'Watch B', 53, 57, 62, 67, 71
            )
            """.trimIndent(),
        )
        database.query("SELECT COUNT(*) FROM hr_minute_buckets WHERE bucketStartMs = 0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
    }

    private fun verifyVitalsNormalization(database: SupportSQLiteDatabase) {
        val vitalsTables =
            listOf(
                "weight_records" to "w",
                "body_fat_records" to "bf",
                "blood_pressure_records" to "bp",
                "oxygen_saturation_records" to "ox",
                "body_temperature_records" to "bt",
            )

        for ((table, prefix) in vitalsTables) {
            database.query("SELECT id, deviceName FROM `$table` ORDER BY id ASC").use { cursor ->
                assertEquals(3, cursor.count)

                // Row 1: was '', should now be NULL
                assertTrue(cursor.moveToNext())
                assertEquals("${prefix}1", cursor.getString(0))
                assertTrue("Expected deviceName in $table for ${prefix}1 to be NULL", cursor.isNull(1))

                // Row 2: was named device, should remain untouched
                assertTrue(cursor.moveToNext())
                assertEquals("${prefix}2", cursor.getString(0))
                assertEquals(false, cursor.isNull(1))

                // Row 3: was NULL, should remain NULL
                assertTrue(cursor.moveToNext())
                assertEquals("${prefix}3", cursor.getString(0))
                assertTrue("Expected deviceName in $table for ${prefix}3 to be NULL", cursor.isNull(1))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-15-16-test"
    }
}
