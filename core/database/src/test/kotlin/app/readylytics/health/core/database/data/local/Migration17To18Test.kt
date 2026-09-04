package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.migration.MIGRATION_17_18
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration17To18Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HealthDatabase::class.java)

    @Test
    fun migrationExecutesExpectedSchemaAltersAndTableCreations() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        MIGRATION_17_18.migrate(db)

        verify {
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `vo2_max_records`") })
            db.execSQL(match { it.contains("CREATE INDEX IF NOT EXISTS `index_vo2_max_records_timestampMs`") })
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN vo2Max REAL DEFAULT NULL")
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN vo2MaxSource TEXT DEFAULT NULL")
        }
    }

    @Test
    fun migration17To18PreservesOldSummaryAndAddsNullableVo2MaxColumnsAndCreatesVo2MaxTable() {
        helper.createDatabase(TEST_DATABASE, 17).apply {
            seedDailySummary(this)
            close()
        }

        val database = helper.runMigrationsAndValidate(TEST_DATABASE, 18, true, *DatabaseMigrations.all)

        database.query(
            "SELECT sleepScore, vo2Max, vo2MaxSource FROM daily_summaries",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(85f, cursor.getFloat(0), 0f)
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }

        database.execSQL(
            """
            INSERT INTO vo2_max_records (id, timestampMs, vo2Max, measurementMethod, deviceName)
            VALUES ('v1', 1767225600000, 48.5, 1, 'Garmin Forerunner')
            """.trimIndent(),
        )

        database.query(
            "SELECT id, timestampMs, vo2Max, measurementMethod, deviceName FROM vo2_max_records WHERE id = 'v1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("v1", cursor.getString(0))
            assertEquals(1767225600000L, cursor.getLong(1))
            assertEquals(48.5f, cursor.getFloat(2), 0.001f)
            assertEquals(1, cursor.getInt(3))
            assertEquals("Garmin Forerunner", cursor.getString(4))
        }
    }

    private fun seedDailySummary(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO daily_summaries (
                dateMidnightMs, sleepScore, diag_isCalibrating, diag_stagesSuspicious,
                diag_lateNadir, diag_hrvMissing, diag_timezoneJump
            ) VALUES (1767225600000, 85.0, 0, 0, 0, 0, 0)
            """.trimIndent(),
        )
    }

    private companion object {
        const val TEST_DATABASE = "migration-17-18-test"
    }
}
