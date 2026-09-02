package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration16To17Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HealthDatabase::class.java)

    @Test
    fun migration16To17PreservesOldSummaryAndAddsNullableProjectionColumns() {
        helper.createDatabase(TEST_DATABASE, 16).apply {
            seedDailySummary(this)
            close()
        }

        val database = helper.runMigrationsAndValidate(TEST_DATABASE, 17, true, *DatabaseMigrations.all)

        database.query(
            "SELECT sleepScore, acuteLoadRecovery, trainingLoadReadinessWorkoutOnly, " +
                "trainingLoadReadinessEverydayHr, trainingReadinessWorkoutOnly, " +
                "trainingReadinessEverydayHr FROM daily_summaries",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(85f, cursor.getFloat(0), 0f)
            for (columnIndex in 1..5) assertTrue(cursor.isNull(columnIndex))
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
        const val TEST_DATABASE = "migration-16-17-test"
    }
}
