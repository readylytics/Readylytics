package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.migration.Migration20To21
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration20To21Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HealthDatabase::class.java)

    @Test
    fun migrationExecutesExpectedSchemaAlter() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        Migration20To21.migrate(db)

        verify {
            db.execSQL("ALTER TABLE workout_records ADD COLUMN modelTrimpSourceRevision INTEGER")
            db.execSQL("ALTER TABLE workout_records ADD COLUMN modelTrimpSnapshotId TEXT")
            db.execSQL("ALTER TABLE workout_records ADD COLUMN modelTrimpAlgorithmRevision INTEGER")
            db.execSQL("ALTER TABLE workout_records ADD COLUMN modelTrimpQuality TEXT")
        }
    }

    @Test
    fun migration20To21PreservesDataAndInitializesModelTrimpMetadata() {
        helper.createDatabase(TEST_DATABASE, 20).apply {
            execSQL(
                "INSERT INTO workout_records (id, startTime, endTime, exerciseType, durationMinutes, " +
                    "zone1Minutes, zone2Minutes, zone3Minutes, zone4Minutes, zone5Minutes, trimp, avgHr, " +
                    "routeState, modelTrimp) " +
                    "VALUES ('w-1', 1000, 2000, 'RUNNING', 16, 0.0, 0.0, 0.0, 0.0, 0.0, 25.0, 140.0, " +
                    "'NOT_REQUESTED', 25.0)",
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(TEST_DATABASE, 21, true, *DatabaseMigrations.all)

        database.query(
            "SELECT id, trimp, modelTrimp, modelTrimpSourceRevision, modelTrimpSnapshotId, " +
                "modelTrimpAlgorithmRevision, modelTrimpQuality FROM workout_records WHERE id = 'w-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("w-1", cursor.getString(0))
            assertEquals(25.0, cursor.getDouble(1), 0.001)
            assertEquals(25.0, cursor.getDouble(2), 0.001)
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-20-21-test"
    }
}
