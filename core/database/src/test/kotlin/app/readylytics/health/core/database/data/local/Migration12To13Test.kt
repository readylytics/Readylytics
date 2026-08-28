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
class Migration12To13Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    @Test
    fun migrate12To13PreservesExistingDataAndAddsNullResidualFatigueColumn() {
        helper.createDatabase(TEST_DATABASE, 12).apply {
            execSQL(
                "INSERT INTO daily_summaries (dateMidnightMs, sleepScore, napCount, " +
                    "diag_isCalibrating, diag_stagesSuspicious, diag_lateNadir, " +
                    "diag_hrvMissing, diag_timezoneJump) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(1_234L, 87.5f, 2, 0, 0, 0, 0, 0),
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                13,
                true,
                *DatabaseMigrations.all,
            )

        // Existing row survives losslessly; new column is additive and NULL for legacy rows.
        database.query(
            "SELECT dateMidnightMs, sleepScore, napCount, residualFatigue FROM daily_summaries",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_234L, cursor.getLong(0))
            assertEquals(87.5, cursor.getFloat(1).toDouble(), 0.001)
            assertEquals(2, cursor.getInt(2))
            assertTrue(cursor.isNull(3))
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-12-13-test"
    }
}
