package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationInstrumentedTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    @Test
    fun migrate3To4CreatesAuditSchemaAndPreservesExistingData() {
        helper.createDatabase(TEST_DATABASE, 3).apply {
            execSQL(
                "INSERT INTO insight_dismissals (dateMidnightMs, type) VALUES (?, ?)",
                arrayOf<Any>(1_234L, "REST"),
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                4,
                true,
                *DatabaseMigrations.all,
            )

        database.query("SELECT dateMidnightMs, type FROM insight_dismissals").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_234L, cursor.getLong(0))
            assertEquals("REST", cursor.getString(1))
        }
        database.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'audit_events'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        database.query(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'index' AND name = 'index_audit_events_occurredAtEpochMs'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
    }

    @Test
    fun migrate4To5AddsNapColumnsAndPreservesExistingData() {
        helper.createDatabase(TEST_DATABASE, 4).apply {
            execSQL(
                "INSERT INTO daily_summaries (dateMidnightMs, diag_isCalibrating, diag_stagesSuspicious, diag_lateNadir, diag_hrvMissing, diag_timezoneJump) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(1_234L, 0, 0, 0, 0, 0),
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                5,
                true,
                *DatabaseMigrations.all,
            )

        database.query("SELECT dateMidnightMs, supplementalSleepDurationMinutes, napCount FROM daily_summaries").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_234L, cursor.getLong(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
    }

    @Test
    fun migrate5To6AddsModelTrimpAndStepRecordsAndDropsRedundantIndex() {
        helper.createDatabase(TEST_DATABASE, 5).apply {
            execSQL(
                "INSERT INTO workout_records (id, startTime, endTime, exerciseType, durationMinutes, " +
                    "zone1Minutes, zone2Minutes, zone3Minutes, zone4Minutes, zone5Minutes, trimp, avgHr) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("w1", 1_000L, 2_000L, "RUNNING", 30, 0f, 5f, 10f, 0f, 0f, 45f, 140f),
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                6,
                true,
                *DatabaseMigrations.all,
            )

        // Existing workout rows survive, unified-TRIMP column is additive/nullable.
        database.query("SELECT id, trimp, modelTrimp FROM workout_records WHERE id = 'w1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(45.0, cursor.getFloat(1).toDouble(), 0.001)
            assertTrue(cursor.isNull(2))
        }

        // New step_records table exists and accepts a row.
        database.execSQL(
            "INSERT INTO step_records (id, startTime, endTime, count, deviceName) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any>("s1", 1_000L, 2_000L, 500L, "Watch"),
        )
        database.query("SELECT id, count FROM step_records WHERE id = 's1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(500L, cursor.getLong(1))
        }

        // The redundant secondary index on daily_summaries' own PK column is gone.
        database.query(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'index' AND name = 'index_daily_summaries_dateMidnightMs'",
        ).use { cursor ->
            assertTrue("Redundant index must be dropped by MIGRATION_5_6", !cursor.moveToFirst())
        }
    }

    @Test
    fun migrate7To9CreatesBodyTemperatureTableAndDailySummaryColumn() {
        helper.createDatabase(TEST_DATABASE, 7).apply { close() }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                9,
                true,
                *DatabaseMigrations.all,
            )

        database.query("SELECT * FROM body_temperature_records LIMIT 1").use { cursor ->
            assertTrue(cursor.columnNames.toList().containsAll(listOf("id", "timestampMs", "celsius", "deviceName")))
        }
        database.query("SELECT avgSleepingBodyTemp FROM daily_summaries LIMIT 1").use { cursor ->
            assertTrue(cursor.columnNames.contains("avgSleepingBodyTemp"))
        }
    }

    @Test
    fun migrate9To10NormalizesSourceRecordRefsAndPreservesData() {
        helper.createDatabase(TEST_DATABASE, 9).apply {
            execSQL(
                "INSERT INTO heart_rate_records (sourceRecordId, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
                    "VALUES ('uuid-abc_1000', 1000, 72, 'RESTING', NULL, NULL)",
            )
            execSQL(
                "INSERT INTO heart_rate_records (sourceRecordId, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
                    "VALUES ('uuid-abc_2000', 2000, 80, 'RESTING', NULL, NULL)",
            )
            execSQL(
                "INSERT INTO heart_rate_records (sourceRecordId, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
                    "VALUES ('uuid-xyz_1000', 1000, 65, 'RESTING', NULL, NULL)",
            )
            execSQL(
                "INSERT INTO hrv_records (sourceRecordId, timestampMs, rmssdMs, recordType, sessionId, deviceName) " +
                    "VALUES ('uuid-hrv_3000', 3000, 42.5, 'SLEEP', NULL, NULL)",
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                10,
                true,
                *DatabaseMigrations.all,
            )

        // Dimension table holds the distinct base UUIDs, one row per source.
        database.query("SELECT sourceRecordId, recordType FROM health_source_records ORDER BY sourceRecordId").use { cursor ->
            assertTrue(cursor.moveToFirst())
            val rows = mutableListOf<Pair<String, String>>()
            do {
                rows.add(cursor.getString(0) to cursor.getString(1))
            } while (cursor.moveToNext())
            assertEquals(3, rows.size)
            assertTrue(rows.any { it == "uuid-abc" to "HEART_RATE" })
            assertTrue(rows.any { it == "uuid-xyz" to "HEART_RATE" })
            assertTrue(rows.any { it == "uuid-hrv" to "HRV" })
        }

        // Heart-rate rows survive losslessly and share one ref per base UUID.
        database.query(
            "SELECT hr.timestampMs, hr.beatsPerMinute, sr.sourceRecordId " +
                "FROM heart_rate_records hr JOIN health_source_records sr ON sr.id = hr.sourceRecordRef",
        ).use { cursor ->
            val byKey = mutableMapOf<Pair<Long, String>, Int>()
            while (cursor.moveToNext()) {
                byKey[cursor.getLong(0) to cursor.getString(2)] = cursor.getInt(1)
            }
            assertEquals(3, byKey.size)
            assertEquals(72, byKey[1000L to "uuid-abc"])
            assertEquals(80, byKey[2000L to "uuid-abc"])
            assertEquals(65, byKey[1000L to "uuid-xyz"])
        }

        // HRV row survives with its ref normalized.
        database.query(
            "SELECT hrv.rmssdMs, sr.sourceRecordId " +
                "FROM hrv_records hrv JOIN health_source_records sr ON sr.id = hrv.sourceRecordRef",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(42.5, cursor.getFloat(0).toDouble(), 0.001)
            assertEquals("uuid-hrv", cursor.getString(1))
        }

        // Warm-tier table exists.
        database.query("SELECT * FROM hr_minute_buckets LIMIT 1").use { cursor ->
            assertTrue(cursor.columnNames.toList().containsAll(listOf("bucketStartMs", "bucketEndMs", "avgBpm", "sampleCount", "recordType")))
        }
    }

    @Test
    fun migrate10To11CreatesWorkoutRoutePointsAndAddsWorkoutColumns() {
        helper.createDatabase(TEST_DATABASE, 10).apply {
            execSQL(
                "INSERT INTO workout_records (id, startTime, endTime, exerciseType, durationMinutes, " +
                    "zone1Minutes, zone2Minutes, zone3Minutes, zone4Minutes, zone5Minutes, trimp, avgHr) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("w1", 1_000L, 2_000L, "RUNNING", 30, 0f, 0f, 0f, 0f, 0f, 15f, 140f),
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                11,
                true,
                *DatabaseMigrations.all,
            )

        // Existing workout row survives; new columns are additive with routeState defaulted.
        database.query(
            "SELECT id, routeState, totalDistanceMeters, avgSpeedKmh, elevationGainMeters " +
                "FROM workout_records WHERE id = 'w1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("w1", cursor.getString(0))
            assertEquals("NOT_AVAILABLE", cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }

        // New route table exists and accepts rows with the cascade FK.
        database.execSQL(
            "INSERT INTO workout_route_points (workoutId, latitude, longitude, altitude, timestampMs, " +
                "horizontalAccuracy, verticalAccuracy) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>("w1", 52.52, 13.405, 34.5, 1_000L, 5f, 2f),
        )
        database.query(
            "SELECT workoutId, latitude, longitude, altitude FROM workout_route_points WHERE workoutId = 'w1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("w1", cursor.getString(0))
            assertEquals(52.52, cursor.getDouble(1), 0.0001)
            assertEquals(13.405, cursor.getDouble(2), 0.0001)
            assertEquals(34.5, cursor.getDouble(3), 0.0001)
        }
    }

    private companion object {
        const val TEST_DATABASE = "audit-migration-test"
    }
}
