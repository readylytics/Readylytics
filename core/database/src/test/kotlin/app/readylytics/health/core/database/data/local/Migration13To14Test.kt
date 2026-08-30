package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration13To14Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    @Test
    fun migrate13To14PreservesRowsAndAddsCanonicalFatigueOrderingIndex() {
        helper.createDatabase(TEST_DATABASE, 13).apply {
            insertWorkout("workout-z")
            insertWorkout("workout-a")
            execSQL(
                "INSERT INTO daily_summaries (dateMidnightMs, sleepScore, " +
                    "diag_isCalibrating, diag_stagesSuspicious, diag_lateNadir, " +
                    "diag_hrvMissing, diag_timezoneJump) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(1_234L, 87.5f, 0, 0, 0, 0, 0),
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                14,
                true,
                *DatabaseMigrations.all,
            )

        database.query(
            "SELECT id FROM workout_records ORDER BY endTime ASC, id ASC",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("workout-a", cursor.getString(0))
            assertTrue(cursor.moveToNext())
            assertEquals("workout-z", cursor.getString(0))
        }
        database.query("SELECT dateMidnightMs, sleepScore FROM daily_summaries").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_234L, cursor.getLong(0))
            assertEquals(87.5, cursor.getFloat(1).toDouble(), 0.001)
        }
        database.query("PRAGMA index_list('workout_records')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                found = found || cursor.getString(nameIndex) == FATIGUE_ORDERING_INDEX
            }
            assertTrue("Canonical fatigue ordering index must exist", found)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertWorkout(id: String) {
        execSQL(
            "INSERT INTO workout_records (id, startTime, endTime, exerciseType, durationMinutes, " +
                "zone1Minutes, zone2Minutes, zone3Minutes, zone4Minutes, zone5Minutes, trimp, avgHr, " +
                "modelTrimp, routeState) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id, 1_000L, 2_000L, "RUNNING", 30, 0f, 5f, 10f, 0f, 0f, 45f, 140f, 45f, "NOT_AVAILABLE"),
        )
    }

    private companion object {
        const val TEST_DATABASE = "migration-13-14-test"
        const val FATIGUE_ORDERING_INDEX = "index_workout_records_endTime_id"
    }
}
