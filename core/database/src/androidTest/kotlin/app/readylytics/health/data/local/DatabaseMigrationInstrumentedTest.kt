package app.readylytics.health.data.local

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
        database
            .query(
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

        database
            .query(
                "SELECT dateMidnightMs, supplementalSleepDurationMinutes, napCount FROM daily_summaries",
            ).use { cursor ->
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
        database
            .query(
                "SELECT name FROM sqlite_master " +
                    "WHERE type = 'index' AND name = 'index_daily_summaries_dateMidnightMs'",
            ).use { cursor ->
                assertTrue("Redundant index must be dropped by MIGRATION_5_6", !cursor.moveToFirst())
            }
    }

    private companion object {
        const val TEST_DATABASE = "audit-migration-test"
    }
}
