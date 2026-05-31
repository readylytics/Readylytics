package com.gregor.lauritz.healthdashboard.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.data.local.DatabaseMigrations.MIGRATION_22_23
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            ApplicationProvider.getApplicationContext<Context>(),
            HealthDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun testMigration22To23() {
        // Create schema v22 database
        val db = helper.createDatabase(TEST_DB_NAME, 22)

        // Verify v22 doesn't have baseline columns
        db.query("PRAGMA table_info(daily_summaries)").use { cursor ->
            val columnNames = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columnNames.add(cursor.getString(1))
            }
            // Baseline columns should NOT exist in v22
            assert(!columnNames.contains("hrv_mu_mssd")) { "v22 should not have hrv_mu_mssd column" }
            assert(!columnNames.contains("rhr_bpm")) { "v22 should not have rhr_bpm column" }
        }

        db.close()

        // Run migration 22→23
        val migratedDb =
            helper.runMigrationsAndValidate(
                TEST_DB_NAME,
                23,
                true,
                MIGRATION_22_23,
            )

        // Verify v23 has baseline columns
        migratedDb.query("PRAGMA table_info(daily_summaries)").use { cursor ->
            val columnNames = mutableMapOf<String, String>()
            while (cursor.moveToNext()) {
                columnNames[cursor.getString(1)] = cursor.getString(2)
            }

            // Verify all baseline columns exist
            assertNotNull(columnNames["hrv_mu_mssd"], "v23 must have hrv_mu_mssd column")
            assertNotNull(columnNames["hrv_sigma_mssd"], "v23 must have hrv_sigma_mssd column")
            assertNotNull(columnNames["rhr_bpm"], "v23 must have rhr_bpm column")
            assertNotNull(
                columnNames["baseline_calculated_at_date"],
                "v23 must have baseline_calculated_at_date column",
            )
            assertNotNull(columnNames["baseline_version"], "v23 must have baseline_version column")
        }

        migratedDb.close()
    }

    companion object {
        private const val TEST_DB_NAME = "migration_test.db"
    }
}
